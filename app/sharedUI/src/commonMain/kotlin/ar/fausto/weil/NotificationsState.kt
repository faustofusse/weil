package ar.fausto.weil

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Notifications paging state hoisted above the nav host, same idea as
 * [JournalState]/[EmailsState]: living outside the screen means it survives
 * a round trip through any future detail screen instead of re-fetching from
 * scratch every time the screen re-enters composition.
 */
@Stable
class NotificationsState(private val notifications: NotificationsRepository) {
    var items by mutableStateOf<List<NotificationItem>>(emptyList())
        private set
    var cursor by mutableStateOf<NotificationCursor?>(null)
        private set
    var hasMore by mutableStateOf(true)
        private set
    var isInitialLoading by mutableStateOf(false)
        private set
    var isLoadingMore by mutableStateOf(false)
        private set
    var isSyncing by mutableStateOf(false)
        private set
    var syncError by mutableStateOf<String?>(null)
        private set
    var totalCount by mutableStateOf(0L)
        private set
    var filteredCount by mutableStateOf(0L)
        private set
    var showOnlyTransactions by mutableStateOf(false)
        private set

    private var loadStarted = false
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    private suspend fun refreshCounts() {
        totalCount = notifications.count(onlyTransactions = false)
        filteredCount = notifications.count(onlyTransactions = true)
    }

    private suspend fun loadFirst() {
        val page = notifications.page(onlyTransactions = showOnlyTransactions)
        items = page.items
        cursor = page.nextCursor
        hasMore = page.nextCursor != null
        refreshCounts()
    }

    private fun reloadWindow() {
        scope.launch {
            try {
                val page = notifications.page(
                    limit = maxOf(items.size, LIST_PAGE_SIZE),
                    onlyTransactions = showOnlyTransactions,
                )
                items = page.items
                cursor = page.nextCursor
                hasMore = page.nextCursor != null
                refreshCounts()
            } catch (_: Throwable) {
            }
        }
    }

    /** Called once per screen visit; no-ops on a return trip from a detail screen. */
    fun ensureLoaded() {
        if (loadStarted) return
        loadStarted = true
        scope.launch {
            isInitialLoading = true
            syncError = null
            try {
                notifications.syncNow()
                loadFirst()
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                syncError = e.message ?: e.toString()
            } finally {
                isInitialLoading = false
            }
            notifications.changes.collect { reloadWindow() }
        }
    }

    fun sync() {
        scope.launch {
            isSyncing = true
            syncError = null
            try {
                notifications.syncNow()
                loadFirst()
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                syncError = e.message ?: e.toString()
            } finally {
                isSyncing = false
            }
        }
    }

    fun toggleShowOnlyTransactions(value: Boolean) {
        if (showOnlyTransactions == value) return
        showOnlyTransactions = value
        scope.launch {
            syncError = null
            try {
                loadFirst()
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                syncError = e.message ?: e.toString()
            }
        }
    }

    fun loadMore() {
        val currentCursor = cursor ?: return
        if (isLoadingMore || !hasMore) return
        isLoadingMore = true
        scope.launch {
            try {
                val page = notifications.page(
                    before = currentCursor,
                    onlyTransactions = showOnlyTransactions,
                )
                items = items + page.items
                cursor = page.nextCursor
                hasMore = page.nextCursor != null
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                syncError = e.message ?: e.toString()
            } finally {
                isLoadingMore = false
            }
        }
    }
}
