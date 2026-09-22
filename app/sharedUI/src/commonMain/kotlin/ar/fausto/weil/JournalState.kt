package ar.fausto.weil

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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
/** Gasto / Ingreso / Traspaso, or no filter at all. */
enum class JournalFilter { All, Expense, Income, Transfer }
@Stable
class JournalState(
    private val ledger: TransactionsRepository,
    private val accounts: AccountsRepository,
) {
    var items by mutableStateOf<List<Transaction>>(emptyList())
        private set

    /**
     * Hoisted with everything else here, not `remember`ed inside the screen:
     * a plain `remember` lives only as long as the composable stays on the
     * back stack, so leaving Movimientos for a transaction and coming back
     * would silently reset the pick.
     */
    var filter by mutableStateOf(JournalFilter.All)
        private set
    fun pickFilter(next: JournalFilter) {
        filter = next
    }

    /**
     * Free-text query over payee/note/account names. Unlike [filter] this is
     * **not** a lens over the pages already loaded: it goes into the SQL
     * ([TransactionsRepository.page]'s `query`), because matching in memory
     * meant the search only ever saw rows the user had scrolled to, so "Coto"
     * missed every Coto older than the first page and read as the app having
     * forgotten the purchase. Hoisted for the same reason as [filter] —
     * opening a transaction from a result and coming back shouldn't clear it.
     */
    var query by mutableStateOf("")
        private set

    /** The term [items] were actually fetched with. */
    private var appliedQuery = ""

    /**
     * Cancelled on every keystroke, which is the entire debounce: a slower
     * in-flight page for "co" cannot land on top of the results for "coto",
     * because its job is already dead by the time it returns.
     */
    private var searchJob: Job? = null

    fun updateQuery(next: String) {
        if (next == query) return
        query = next
        val wanted = next.trim()
        searchJob?.cancel()
        // Trimming means "coto" and "coto " are the same fetch.
        if (wanted == appliedQuery) return
        searchJob = scope.launch {
            // Clearing the field restores the plain journal at once; typing
            // waits, so a four-letter word costs one query and not four.
            if (wanted.isNotEmpty()) delay(SEARCH_DEBOUNCE_MS)
            appliedQuery = wanted
            // Skeleton only for a cold search: re-querying with rows already
            // on screen would flash it on every keystroke.
            isInitialLoading = items.isEmpty()
            error = null
            try {
                loadFirst()
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                error = e.message ?: e.toString()
            } finally {
                isInitialLoading = false
            }
        }
    }

    /** True while the inline search field in the top bar is open. */
    var searching by mutableStateOf(false)
        private set

    /**
     * One-shot: [startSearch] sets it so the screen grabs focus (and opens
     * the keyboard) exactly once, for the tap that opened the field. Without
     * this, hoisting [searching] above the nav host — needed so it survives
     * a trip to a transaction and back — means the field re-enters
     * composition on every return visit, and a plain `LaunchedEffect(Unit)`
     * would request focus (and the keyboard) again even after the user had
     * dismissed it on purpose.
     */
    var pendingFocus by mutableStateOf(false)
        private set
    fun focusConsumed() {
        pendingFocus = false
    }
    fun startSearch() {
        searching = true
        pendingFocus = true
    }
    fun closeSearch() {
        searching = false
        pendingFocus = false
        updateQuery("")
    }


    // Multi-select: entered by long-pressing a row, exited by tapping the
    // back arrow, a successful delete, or a page load that no longer shows
    // every selected id (a sync removing a row under us). Kept in the state
    // rather than the screen so navigation to a transaction and back doesn't
    // drop the pick mid-selection.
    private var selection = mutableStateListOf<String>()

    /** Ids currently ticked, in pick order. */
    val selected: List<String> get() = selection.toList()

    /** True once a long-press has started a selection run. */
    val isSelecting: Boolean get() = selection.isNotEmpty()

    fun toggleSelected(id: String) {
        if (id in selection) selection.remove(id) else selection.add(id)
    }

    fun clearSelection() {
        selection.clear()
    }

    private fun pruneSelection() {
        val known = items.mapTo(HashSet()) { it.id }
        selection.removeAll { it !in known }
    }
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
        // Home's rows are the unfiltered journal: seeding them under an open
        // search would paint non-matching transactions as results.
        if (fetchedOwnPage || recent.isEmpty() || appliedQuery.isNotEmpty()) return
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

    /**
     * [userInitiated] drives the pull-to-refresh spinner, same rule as
     * [LedgerState.refresh]: the indicator answers a gesture. Recording a
     * transaction also reconciles with the server, but the row is already on
     * screen by then, so a spinner would only say "something is happening"
     * about work nobody asked for.
     */
    fun refresh(userInitiated: Boolean = false) {
        scope.launch {
            if (userInitiated) pullRefreshing = true
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
            val page = ledger.page(before = after, query = appliedQuery.ifEmpty { null })
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
        val page = ledger.page(query = appliedQuery.ifEmpty { null })
        cursor = page.lastOrNull()?.let { LedgerCursor(it.date, it.id) }
        hasMore = page.size == LIST_PAGE_SIZE
        accountIndex(accounts).let { paths = it.paths; types = it.types; names = it.names; icons = it.icons; colors = it.colors }
        items = page
        loaded = true
        fetchedOwnPage = true
        pruneSelection()
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

    /**
     * Multi-select delete: hard-deletes every ticked transaction and reloads
     * the first page. Returns a backup of what was removed, in input order,
     * for the caller's Deshacer — [restore] re-creates it verbatim (date,
     * payee, note, postings, provenance), so undo is a true inverse rather
     * than the single-row approximation the editor can get away with.
     */
    suspend fun deleteSelected(): List<Backup> {
        val ids = selection.toList()
        if (ids.isEmpty()) return emptyList()
        val backup = ids.mapNotNull { ledger.get(it) }
        val sources = ledger.sourcesFor(ids)
        ledger.deleteAll(ids)
        selection.clear()
        loadFirst()
        return backup.map { Backup(it, sources[it.id].orEmpty().map { s -> TransactionSource(s.kind, s.ref, s.eventKey) }) }
    }

    /** Everything needed to re-create a deleted transaction. */
    data class Backup(val tx: Transaction, val sources: List<TransactionSource>)

    /** Re-creates rows deleted by [deleteSelected]; the Deshacer half. */
    suspend fun restore(backups: List<Backup>) {
        if (backups.isEmpty()) return
        ledger.addAll(
            backups.map { b ->
                NewTransaction(
                    date = b.tx.date,
                    payee = b.tx.payee,
                    note = b.tx.note,
                    drafts = b.tx.postings.map { p ->
                        DraftPosting(p.accountId, formatMinorUnits(p.amountMinor), p.commodity)
                    },
                    timeKnown = b.tx.timeKnown,
                    sources = b.sources,
                )
            },
        )
    }

    private companion object {
        /**
         * Long enough that a typed word is one query rather than one per
         * letter, short enough that the list is already right by the time the
         * thumb leaves the keyboard. The query is local SQL, so this is about
         * doing less work, not about latency.
         */
        const val SEARCH_DEBOUNCE_MS = 200L
    }

    fun loadMore() {
        val current = cursor ?: return
        if (isLoadingMore || !hasMore) return
        isLoadingMore = true
        scope.launch {
            try {
                val page = ledger.page(before = current, query = appliedQuery.ifEmpty { null })
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
