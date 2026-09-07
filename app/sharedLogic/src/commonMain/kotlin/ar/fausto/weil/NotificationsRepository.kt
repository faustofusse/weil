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

/** True when the notification text looks like it carries a transaction amount. */
fun NotificationItem.potentialTransaction(): Boolean =
    text.contains('$') || title.contains('$')

/** Package-manager enrichment the Android listener passes along with each capture. */
data class AppInfo(
    val id: String,
    val name: String,
    val icon: ByteArray?,
    val isSystemApp: Boolean,
)

class NotificationsRepository(private val db: DatabaseProvider) {

    /** Emitted after every local record; the UI collects it to refresh the list. */
    val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    private val scope = CoroutineScope(SupervisorJob() + CoroutineName("notifications-sync"))
    private val syncMutex = Mutex()

    @Volatile
    private var lastSyncAt = 0L

    private var pendingSync = false

    suspend fun list(): List<NotificationItem> = db.use { d ->
        d.sync()
        d.query(NOTIFICATIONS_SQL, null) { rows ->
            rows.filter { it.size >= 8 }
                .map { row ->
                    NotificationItem(
                        id = row[0]?.toString() ?: "",
                        packageName = row[1]?.toString() ?: "",
                        title = row[2]?.toString() ?: "",
                        text = row[3]?.toString() ?: "",
                        category = row[4]?.toString(),
                        postTime = (row[5] as? Number)?.toLong() ?: 0L,
                        receivedAt = (row[6] as? Number)?.toLong() ?: 0L,
                        appName = row[7]?.toString() ?: row[1]?.toString() ?: "",
                        appIcon = row[8] as? ByteArray,
                    )
                }
                .toList()
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

    private companion object {
        const val SYNC_INTERVAL_MILLIS = 30_000L
        const val NOTIFICATIONS_SQL =
            "select n.id, n.package_name, n.title, n.text, n.category, n.post_time, n.received_at," +
                " a.name, a.icon" +
                " from notifications n left join applications a on a.id = n.package_name" +
                " order by n.post_time desc, n.received_at desc"
    }
}
