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
    var isSyncing by mutableStateOf(false)
        private set
    var syncError by mutableStateOf<String?>(null)
        private set
    var totalCount by mutableStateOf(0L)
        private set

    /** Guards [ensureLoaded] against firing twice before the first run lands. */
    private var loadStarted = false
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    /** Called once per screen visit; no-ops on a return trip from the detail screen. */
    fun ensureLoaded() {
        if (loadStarted) return
        loadStarted = true
        scope.launch {
            isInitialLoading = true
            syncError = null
            try {
                emails.syncNow()
                loadFirst()
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                syncError = e.message ?: e.toString()
            } finally {
                isInitialLoading = false
            }
        }
    }

    fun sync() {
        scope.launch {
            isSyncing = true
            syncError = null
            try {
                emails.syncNow()
                loadFirst()
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                syncError = e.message ?: e.toString()
            } finally {
                isSyncing = false
            }
        }
    }

    fun loadMore() {
        val currentCursor = cursor ?: return
        if (isLoadingMore || !hasMore) return
        isLoadingMore = true
        scope.launch {
            try {
                val page = emails.page(before = currentCursor)
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
        val page = emails.page()
        items = page.items
        cursor = page.nextCursor
        hasMore = page.nextCursor != null
        totalCount = emails.count()
    }
}
