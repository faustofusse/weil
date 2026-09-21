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
 * Emails paging state hoisted above the nav host, same idea as [JournalState]:
 * living outside the screen means it survives navigating to an email's
 * detail and back, instead of the screen re-fetching (and re-showing a
 * skeleton) from scratch every time it re-enters composition.
 */
@Stable
class EmailsState(private val emails: EmailsRepository) {
    var items by mutableStateOf<List<Email>>(emptyList())
        private set
    var cursor by mutableStateOf<EmailCursor?>(null)
        private set
    var hasMore by mutableStateOf(true)
        private set
    var isInitialLoading by mutableStateOf(false)
        private set
    var isLoadingMore by mutableStateOf(false)
        private set
    /** Any sync in flight, background reconcile included (disables the refresh action). */
    var isSyncing by mutableStateOf(false)
        private set

    /** Only a *user-initiated* refresh: drives the pull-to-refresh indicator. */
    var pullRefreshing by mutableStateOf(false)
        private set
    var syncError by mutableStateOf<String?>(null)
        private set
    var totalCount by mutableStateOf(0L)
        private set
    var filteredCount by mutableStateOf(0L)
        private set
    var showOnlyTransactions by mutableStateOf(false)
        private set

    /** Guards [ensureLoaded] against firing twice before the first run lands. */
    private var loadStarted = false
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    private suspend fun refreshCounts() {
        totalCount = emails.count()
        filteredCount = emails.count(onlyTransactions = true)
    }

    /** Called once per screen visit; no-ops on a return trip from the detail screen. */
    fun ensureLoaded() {
        if (loadStarted) return
        loadStarted = true
        scope.launch {
            // Local-first: the replica already holds the last known state, so
            // paint it immediately instead of waiting on a sync round trip.
            isInitialLoading = true
            syncError = null
            try {
                loadFirst()
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                syncError = e.message ?: e.toString()
            } finally {
                isInitialLoading = false
            }
            // Then reconcile with the server and repaint; a failed sync must
            // not hide (or error out) the local view already on screen.
            isSyncing = true
            try {
                emails.syncNow()
                loadFirst()
                syncError = null
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (items.isEmpty()) syncError = e.message ?: e.toString()
            } finally {
                isSyncing = false
            }
        }
    }

    fun sync() {
        scope.launch {
            isSyncing = true
            pullRefreshing = true
            syncError = null
            try {
                emails.syncNow()
                loadFirst()
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                syncError = e.message ?: e.toString()
            } finally {
                isSyncing = false
                pullRefreshing = false
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
                val page = emails.page(before = currentCursor, onlyTransactions = showOnlyTransactions)
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

    private suspend fun loadFirst() {
        val page = emails.page(onlyTransactions = showOnlyTransactions)
        items = page.items
        cursor = page.nextCursor
        hasMore = page.nextCursor != null
        refreshCounts()
    }
}
