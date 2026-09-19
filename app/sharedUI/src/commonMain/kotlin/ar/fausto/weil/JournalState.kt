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
 * Journal paging state hoisted above the nav host, same idea as [LedgerState]:
 * living outside the screen means it survives navigating to a transaction and
 * back, instead of the screen re-fetching from scratch every time it re-enters
 * composition.
 *
 * [seed] lets Home hand over the few transactions it already has loaded
 * ([LedgerState.recent]) so the journal never has to show a skeleton for rows
 * the app already painted a second ago — it only has to fetch what Home
 * didn't: the rest of the first page, then further ones.
 */
@Stable
class JournalState(
    private val ledger: TransactionsRepository,
    private val accounts: AccountsRepository,
) {
    var items by mutableStateOf<List<Transaction>>(emptyList())
        private set
    var cursor by mutableStateOf<LedgerCursor?>(null)
        private set
    var hasMore by mutableStateOf(true)
        private set

    /** True once there is *something* on screen — seeded or fetched. Gates the skeleton. */
    var loaded by mutableStateOf(false)
        private set

    /** True only while a real, authoritative page load/sync is in flight from a fresh [items]. */
    var isInitialLoading by mutableStateOf(false)
        private set

    /** Bound to the pull-to-refresh spinner: true only for a user-initiated pull, never for background work. */
    var pullRefreshing by mutableStateOf(false)
        private set
    var isLoadingMore by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var paths by mutableStateOf<Map<String, String>>(emptyMap())
        private set
    var types by mutableStateOf<Map<String, AccountType>>(emptyMap())
        private set

    /** Leaf names, for the compact [MovementRow] shared with Home. */
    var names by mutableStateOf<Map<String, String>>(emptyMap())
        private set

    /** Leaf icon keys, same row. */
    var icons by mutableStateOf<Map<String, String?>>(emptyMap())
        private set

    /** Leaf palette keys, same row. */
    var colors by mutableStateOf<Map<String, String?>>(emptyMap())
        private set

    /** Set once a real fetch (not a Home seed) has populated [items]. */
    private var fetchedOwnPage = false

    /** Guards [ensureLoaded] against firing twice before the first run lands. */
    private var loadStarted = false
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    init {
        scope.launch { ledger.changes.collect { loadFirst() } }
    }

    /** Home hands over its already-loaded recent transactions so the journal opens painted. */
    fun seed(recent: List<Transaction>) {
        if (fetchedOwnPage || recent.isEmpty()) return
        items = recent
        cursor = recent.lastOrNull()?.let { LedgerCursor(it.date, it.id) }
        loaded = true
    }

    /**
     * Called once per screen visit; no-ops after the first real page load.
     * When [items] is already seeded from Home, this only fetches what Home
     * didn't have — the rest of the first page — with no skeleton and no
     * spinner, since there's already something on screen. A cold start (no
     * seed) falls back to a normal skeleton-then-page load.
     */
    fun ensureLoaded() {
        if (fetchedOwnPage || loadStarted) return
        loadStarted = true
        scope.launch {
            error = null
            try {
                val seeded = items.isNotEmpty()
                if (seeded) {
                    extendSeed()
                } else {
                    isInitialLoading = true
                    loadFirst()
                }
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                error = e.message ?: e.toString()
            } finally {
                isInitialLoading = false
            }
            // Then reconcile with the server silently, whichever path got us
            // here — a failed sync must not disturb what's already on screen.
            try {
                ledger.syncNow()
                loadFirst()
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
            }
        }
    }

    fun refresh() {
        scope.launch {
            pullRefreshing = true
            error = null
            try {
                ledger.syncNow()
                loadFirst()
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                error = e.message ?: e.toString()
            } finally {
                pullRefreshing = false
            }
        }
    }

    /** Appends whatever comes after the seeded rows, using them as the starting cursor. */
    private suspend fun extendSeed() {
        accountIndex(accounts).let { paths = it.paths; types = it.types; names = it.names; icons = it.icons; colors = it.colors }
        val after = cursor
        if (after != null) {
            val page = ledger.page(before = after)
            cursor = page.lastOrNull()?.let { LedgerCursor(it.date, it.id) } ?: after
            hasMore = page.size == LIST_PAGE_SIZE
            append(page)
        }
        loaded = true
    }

    /**
     * Appends a page, dropping rows already on screen. Page loads can overlap:
     * [loadMore]/[extendSeed] capture a cursor, suspend on the DB, and in the
     * meantime a [ledger.changes] emission (or the post-sync reconcile) can
     * replace [items] with a fresh first page — appending blind then puts the
     * same transaction in the list twice and LazyColumn dies on the duplicate
     * key.
     */
    private fun append(page: List<Transaction>) {
        val seen = items.mapTo(HashSet()) { it.id }
        items = items + page.filter { seen.add(it.id) }
    }

    private suspend fun loadFirst() {
        val page = ledger.page()
        cursor = page.lastOrNull()?.let { LedgerCursor(it.date, it.id) }
        hasMore = page.size == LIST_PAGE_SIZE
        accountIndex(accounts).let { paths = it.paths; types = it.types; names = it.names; icons = it.icons; colors = it.colors }
        items = page
        loaded = true
        fetchedOwnPage = true
    }

    /**
     * Dev utility exposed from the journal's overflow menu: hard-deletes
     * every transaction dated within [from, to] and reloads the first page.
     * Returns the number of transactions removed.
     */
    suspend fun deleteRange(from: Long, to: Long): Int {
        val count = ledger.deleteRange(from, to)
        loadFirst()
        return count
    }

    fun loadMore() {
        val current = cursor ?: return
        if (isLoadingMore || !hasMore) return
        isLoadingMore = true
        scope.launch {
            try {
                val page = ledger.page(before = current)
                cursor = page.lastOrNull()?.let { LedgerCursor(it.date, it.id) }
                hasMore = page.size == LIST_PAGE_SIZE
                append(page)
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                error = e.message ?: e.toString()
            } finally {
                isLoadingMore = false
            }
        }
    }
}
