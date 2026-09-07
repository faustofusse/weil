package ar.fausto.weil

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

data class NotificationItem(
    val id: String,
    val packageName: String,
    val title: String,
    val text: String,
    val category: String?,
    val postTime: Long,
    val receivedAt: Long,
    val appName: String,
    val appIcon: ByteArray?,
)

/** Package-manager enrichment the Android listener passes along with each capture. */
data class AppInfo(
    val id: String,
    val name: String,
    val icon: ByteArray?,
    val isSystemApp: Boolean,
)

/** Last row of a page; querying strictly before it yields the next page. */
data class NotificationCursor(
    val postTime: Long,
    val receivedAt: Long,
    val id: String,
)

/** Rows fetched per list page (notifications and emails). */
const val LIST_PAGE_SIZE = 100

data class NotificationsPage(
    val items: List<NotificationItem>,
    val nextCursor: NotificationCursor?,
)

class NotificationsRepository(private val db: DatabaseProvider) {

    /** Emitted after every local record; the UI collects it to refresh the list. */
    val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    private val scope = CoroutineScope(SupervisorJob() + CoroutineName("notifications-sync"))
    private val syncMutex = Mutex()

    @Volatile
    private var lastSyncAt = 0L

    private var pendingSync = false

    /** Pulls remote changes into the local replica. */
    suspend fun syncNow() {
        db.use { it.sync() }
    }

    /** Total rows, optionally restricted to potential-transaction notifications. */
    suspend fun count(onlyTransactions: Boolean): Long = db.use { d ->
        val where = if (onlyTransactions) " where $TRANSACTION_FILTER" else ""
        d.query("select count(*) from notifications$where", null) { rows ->
            (rows.firstOrNull()?.firstOrNull() as? Number)?.toLong() ?: 0L
        }
    }

    /**
     * Keyset-paginated page of notifications, newest first. Does not sync —
     * call [syncNow] explicitly when a remote pull is wanted (refresh), and
     * skip it for local-change reloads where the row is already durable.
     *
     * The join against `applications` is done in memory ([loadApps]) instead
     * of in SQL: an icon blob would otherwise repeat on every row of the page.
     */
    suspend fun page(
        limit: Int = LIST_PAGE_SIZE,
        before: NotificationCursor? = null,
        onlyTransactions: Boolean = false,
    ): NotificationsPage = db.use { d ->
        val apps = loadApps(d)
        val where = buildList {
            if (onlyTransactions) add(TRANSACTION_FILTER)
            if (before != null) add(CURSOR_FILTER)
        }.joinToString(" and ")
        val sql = "select id, package_name, title, text, category, post_time, received_at" +
            " from notifications" +
            (if (where.isEmpty()) "" else " where $where") +
            " order by post_time desc, received_at desc, id desc" +
            " limit $limit"
        d.query(sql, cursorParams(before)) { rows ->
            val items = rows.filter { it.size >= 7 }
                .map { row -> toNotificationItem(row, apps) }
                .toList()
            val next = if (items.size < limit) {
                null
            } else {
                items.lastOrNull()?.let {
                    NotificationCursor(postTime = it.postTime, receivedAt = it.receivedAt, id = it.id)
                }
            }
            NotificationsPage(items, next)
        }
    }

    /**
     * Records a captured notification locally and schedules a throttled push:
     * at most one `sync()` per [SYNC_INTERVAL_MILLIS], with a trailing sync so
     * the last item of a burst still uploads. Sync failures are swallowed —
     * the row is durable locally and goes out on the next sync.
     */
    suspend fun record(
        packageName: String,
        title: String,
        text: String,
        category: String?,
        postTime: Long,
        app: AppInfo?,
    ) {
        val now = epochMillis()
        db.use { d ->
            if (app != null) upsertApplication(d, app, now)
            d.execute(
                "insert into notifications(id, package_name, title, text, category, post_time, received_at)" +
                    " values(:id, :package_name, :title, :text, :category, :post_time, :received_at)",
                buildMap {
                    put(":id", Uuid.random().toString())
                    put(":package_name", packageName)
                    put(":title", title)
                    put(":text", text)
                    if (category != null) put(":category", category)
                    put(":post_time", postTime)
                    put(":received_at", now)
                },
            )
        }
        changes.tryEmit(Unit)
        scheduleSync()
    }

    private suspend fun scheduleSync() {
        scope.launch {
            // A trailing sync is already queued for this burst — nothing to add.
            if (pendingSync) return@launch
            val elapsed = epochMillis() - lastSyncAt
            if (elapsed < SYNC_INTERVAL_MILLIS) {
                pendingSync = true
                delay(SYNC_INTERVAL_MILLIS - elapsed)
                pendingSync = false
            }
            syncMutex.withLock {
                try {
                    lastSyncAt = epochMillis()
                    db.use { it.sync() }
                } catch (_: Throwable) {
                }
            }
        }
    }

    private fun upsertApplication(d: Database, app: AppInfo, now: Long) {
        d.execute(
            "insert into applications(id, name, icon, is_system_app, first_seen_at," +
                " notification_count, last_notification_at)" +
                " values(:id, :name, :icon, :is_system_app, :first_seen_at, 1, :first_seen_at)" +
                " on conflict(id) do update set" +
                " notification_count = notification_count + 1, last_notification_at = :first_seen_at",
            buildMap {
                put(":id", app.id)
                put(":name", app.name)
                if (app.icon != null) put(":icon", app.icon)
                put(":is_system_app", if (app.isSystemApp) 1L else 0L)
                put(":first_seen_at", now)
            },
        )
    }

    /** One row per app, so a full scan is bounded by installed apps — no join blobs. */
    private fun loadApps(d: Database): Map<String, AppMeta> =
        d.query("select id, name, icon from applications", null) { rows ->
            rows.filter { it.size >= 3 }
                .mapNotNull { row ->
                    val id = row[0]?.toString() ?: return@mapNotNull null
                    AppMeta(
                        id = id,
                        name = row[1]?.toString() ?: id,
                        icon = row[2] as? ByteArray,
                    )
                }
                .associateBy { it.id }
        }

    private fun toNotificationItem(
        row: List<Any?>,
        apps: Map<String, AppMeta>,
    ): NotificationItem {
        val packageName = row[1]?.toString() ?: ""
        val app = apps[packageName]
        return NotificationItem(
            id = row[0]?.toString() ?: "",
            packageName = packageName,
            title = row[2]?.toString() ?: "",
            text = row[3]?.toString() ?: "",
            category = row[4]?.toString(),
            postTime = (row[5] as? Number)?.toLong() ?: 0L,
            receivedAt = (row[6] as? Number)?.toLong() ?: 0L,
            appName = app?.name ?: packageName,
            appIcon = app?.icon,
        )
    }

    private fun cursorParams(before: NotificationCursor?): Map<String, Any>? =
        if (before == null) {
            null
        } else {
            buildMap {
                put(":pt", before.postTime)
                put(":ra", before.receivedAt)
                put(":id", before.id)
            }
        }

    private class AppMeta(val id: String, val name: String, val icon: ByteArray?)

    private companion object {
        const val SYNC_INTERVAL_MILLIS = 30_000L
        const val TRANSACTION_FILTER = "(text like '%\$%' or title like '%\$%')"
        const val CURSOR_FILTER =
            "(post_time < :pt" +
                " or (post_time = :pt and received_at < :ra)" +
                " or (post_time = :pt and received_at = :ra and id < :id))"
    }
}
