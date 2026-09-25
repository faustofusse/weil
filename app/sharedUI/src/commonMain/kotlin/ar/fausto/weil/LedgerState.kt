package ar.fausto.weil

import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Account-tree state hoisted above the nav host next to the old [AccountsState]
 * style: the tree, per-node rollups and the mutations survive navigating away.
 * Journal/register paging lives in the screens themselves.
 */
@Stable
class LedgerState(
    val accounts: AccountsRepository,
    val ledger: TransactionsRepository,
    val settings: SettingsRepository,
    /** Commodities and prices for [displayTotals]; null prints raw balances. */
    private val brokers: BrokersRepository? = null,
) {
    /**
     * Per-type default account ids as stored (not resolved): the account a
     * new transaction preselects for that type. Raw ids, because the account
     * may have been deleted on another device — [resolveDefault] validates
     * them against [tree] at the point of use.
     */
    var defaultAccounts by mutableStateOf<Map<AccountType, String>>(emptyMap())
        private set
    var tree by mutableStateOf<List<AccountNode>>(emptyList())
        private set
    /** Stored order of Home's account tiles; see [HOME_ACCOUNT_ORDER_KEY]. */
    var homeOrder by mutableStateOf<List<String>>(emptyList())
        private set

    /** Root asset accounts in the user's Home order; Home shows the first few. */
    val homeAccounts: List<AccountNode> by derivedStateOf {
        applyHomeOrder(tree.filter { it.account.type == AccountType.Asset }, homeOrder)
    }
    /** Survives navigation because the state lives above the nav host. */
    var expandedIds by mutableStateOf<Set<String>>(emptySet())
        private set

    /**
     * Masks every amount on Home (hero, tiles and movements alike) behind
     * dots. Deliberately *not* persisted: it's a "someone is looking over my
     * shoulder" gesture, not a preference, and a hidden balance surviving a
     * restart would read as data that failed to load.
     */
    var amountsHidden by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set
    /**
     * Bound to the pull-to-refresh spinner: true only for a user-initiated
     * pull, never for the automatic first load or a silent background sync —
     * both of those already have data on screen a moment later and don't
     * need a top-of-screen animation to say so.
     */
    var pullRefreshing by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    /** What the last database read returned; [recent] is this plus [pending]. */
    private var storedRecent by mutableStateOf<List<Transaction>>(emptyList())
    private var storedLeafTotals by mutableStateOf<Map<String, Map<String, Long>>>(emptyMap())

    /**
     * Rows written from this device that the database reads haven't returned
     * yet — see [record]. They are folded into [recent] and [leafTotals] so
     * the ledger on screen is the ledger the user just changed, without
     * waiting for the write (which queues behind whatever sync happens to be
     * holding the single database thread).
     *
     * A pending row drops out the moment a read brings it back, not when the
     * write returns: those are different instants, and dropping it on the
     * write would let the row blink out until the next read.
     */
    private var pending by mutableStateOf<List<Transaction>>(emptyList())
    private val unseen: List<Transaction> by derivedStateOf {
        if (pending.isEmpty()) emptyList()
        else pending.filter { p -> storedRecent.none { it.id == p.id } }
    }

    /**
     * id → that account's own postings only, no descendants. Diffing this
     * against [totals] is how a row knows whether the number it shows is a
     * subtree rollup (someone should say so) or the account's own balance.
     */
    val leafTotals: Map<String, Map<String, Long>> by derivedStateOf {
        if (unseen.isEmpty()) return@derivedStateOf storedLeafTotals
        val merged = storedLeafTotals.mapValues { (_, v) -> v.toMutableMap() }.toMutableMap()
        for (tx in unseen) {
            for (posting in tx.postings) {
                val byCommodity = merged.getOrPut(posting.accountId) { mutableMapOf() }
                byCommodity[posting.commodity] =
                    (byCommodity[posting.commodity] ?: 0L) + posting.amountMinor
            }
        }
        merged
    }

    /**
     * id → subtree totals per commodity; the map includes leaf-only values
     * for every node, rolled up from the postings of the whole subtree.
     */
    val totals: Map<String, Map<String, Long>> by derivedStateOf {
        rollupSubtrees(tree, leafTotals)
    }

    /** Prices and commodity descriptions, reloaded with every [refresh]. */
    var valuation by mutableStateOf(Valuation())
        private set

    /**
     * [leafTotals] as money: instruments valued at their latest price into
     * their quote currency, zero lines dropped (see [Valuation.value]). What
     * every balance on screen prints; the raw per-commodity maps stay for the
     * positions view, which is the one place that wants quantities.
     */
    val displayLeafTotals: Map<String, Map<String, Long>> by derivedStateOf {
        val v = valuation
        leafTotals.mapValues { (_, byCommodity) -> v.value(byCommodity) }
    }

    /** Subtree rollups of [displayLeafTotals]; valuing is linear, so rollup-then-value is the same. */
    val displayTotals: Map<String, Map<String, Long>> by derivedStateOf {
        rollupSubtrees(tree, displayLeafTotals)
    }

    /**
     * Latest [RECENT_COUNT] transactions for Home's "recent" section. Lives
     * here, not in a screen-local `remember`, so it survives navigating away
     * and back: Home would otherwise dispose its composition and briefly
     * show nothing while it re-fetched.
     */
    val recent: List<Transaction> by derivedStateOf {
        if (unseen.isEmpty()) storedRecent
        else (unseen + storedRecent)
            .sortedWith(compareByDescending<Transaction> { it.date }.thenByDescending { it.id })
            .take(RECENT_COUNT)
    }

    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    var loaded = false
        private set

    init {
        // Re-read the tree whenever any screen mutates the ledger, so Home
        // balances stay live across navigation.
        scope.launch {
            ledger.changes.collect { refresh() }
        }
        scope.launch {
            settings.changes.collect {
                defaultAccounts = settings.defaultAccounts()
                reloadHomeOrder()
            }
        }
    }

    fun refresh(userInitiated: Boolean = false) {
        scope.launch {
            busy = true
            if (userInitiated) pullRefreshing = true
            error = null
            try {
                // Local-first: the replica already has the last known state, so
                // paint it immediately instead of waiting on a sync round trip.
                loadLocal()
                loaded = true
                // Then reconcile with the server in the background and repaint
                // if anything changed; a failed sync must not hide the local view.
                try {
                    ledger.syncNow()
                    loadLocal()
                } catch (e: Throwable) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                }
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                error = e.message ?: e.toString()
            } finally {
                busy = false
                pullRefreshing = false
            }
        }
    }

    private suspend fun loadLocal() {
        val newTree = accounts.tree()
        val leafs = ledger.leafBalances()
        tree = newTree
        storedLeafTotals = leafs
        brokers?.let { b -> runCatching { b.valuation() }.getOrNull()?.let { valuation = it } }
        defaultAccounts = settings.defaultAccounts()
        reloadHomeOrder()
        storedRecent = ledger.page(limit = RECENT_COUNT)
    }

    /**
     * Records a transaction the way a local-first app should: the row is on
     * screen before the database is touched.
     *
     * The write itself is local and quick, but every database call shares one
     * thread (the Rust engine owns a single connection), so an insert issued
     * while a `sync()` is in flight waits for it — seconds, on a slow network,
     * with the form still open and spinning for a write that has nothing to
     * do with the network. Here the postings are resolved synchronously (so a
     * validation error is still immediate and the caller can keep the form
     * open), the row joins [pending], and the insert happens in the
     * background under the *same* id the UI is already showing.
     *
     * Throws [LedgerValidationException] for unbalanced or empty drafts.
     */
    @OptIn(ExperimentalUuidApi::class)
    fun record(
        date: Long,
        payee: String,
        note: String?,
        drafts: List<DraftPosting>,
        timeKnown: Boolean = true,
    ): String {
        val txId = Uuid.random().toString()
        val postings = resolvePostings(drafts).map { it.copy(transactionId = txId) }
        pending = pending + Transaction(
            id = txId,
            date = date,
            payee = payee,
            note = note,
            createdAt = epochMillis(),
            postings = postings,
            timeKnown = timeKnown,
        )
        scope.launch {
            error = null
            try {
                ledger.add(date, payee, note, drafts, timeKnown, id = txId)
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                // The row never reached the database, so take it back off the
                // screen rather than leave a transaction that only exists in
                // this process.
                pending = pending.filterNot { it.id == txId }
                error = e.message ?: e.toString()
            }
        }
        return txId
    }

    fun mutate(action: suspend () -> Unit) {
        scope.launch {
            busy = true
            error = null
            try {
                action()
                tree = accounts.tree()
                storedLeafTotals = ledger.leafBalances()
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                error = e.message ?: e.toString()
            } finally {
                busy = false
            }
        }
    }

    fun addAccount(
        name: String,
        type: AccountType,
        parentId: String?,
        icon: String? = null,
        commodity: String? = null,
        color: String? = null,
    ) = mutate { accounts.add(name, type, parentId, icon, commodity, color) }

    fun rename(id: String, name: String) =
        mutate { accounts.rename(id, name) }

    /** Icon key from the [AccountIcons] catalog; null clears it back to the type default. */
    fun setIcon(id: String, icon: String?) =
        mutate { accounts.setIcon(id, icon) }

    /** Palette key from [AccountColor]; null paints the account neutral. */
    fun setColor(id: String, color: String?) =
        mutate { accounts.setColor(id, color) }

    /** [parentId] null moves the account to the root of its type. */
    fun reparent(id: String, parentId: String?) =
        mutate { accounts.reparent(id, parentId) }

    fun deleteAccount(id: String) =
        mutate { accounts.delete(id) }

    /**
     * Makes [id] the preselected account for its type, or clears the default
     * when it already is it (the row is a toggle).
     */
    fun toggleDefaultAccount(account: Account) = mutate {
        val current = defaultAccounts[account.type]
        settings.setDefaultAccount(account.type, account.id.takeIf { it != current })
        defaultAccounts = settings.defaultAccounts()
    }

    /**
     * Declares (or clears, with null) the currency an Asset/Liability account
     * holds. Existing postings are untouched: the currency restricts what the
     * entry screens offer, not what the ledger already recorded.
     */
    fun setCommodity(id: String, commodity: String?) =
        mutate { accounts.setCommodity(id, commodity) }

    /** Only meaningful for Asset/Liability; see [excludedFromNetWorth]. */
    fun setInNetWorth(id: String, included: Boolean) =
        mutate { accounts.setInNetWorth(id, included) }

    /**
     * Saves the full order of Home's account tiles. Applied to the screen
     * first: the write syncs, and a drag that snaps back until the network
     * answers reads as a drag that failed.
     */
    fun saveHomeOrder(ids: List<String>) {
        homeOrder = ids
        homeOrderWrites++
        scope.launch {
            try {
                settings.setHomeAccountOrder(ids)
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
            } finally {
                homeOrderWrites--
            }
            // The write is local and the sync after it is what usually fails
            // (offline), so show whatever the file actually holds rather than
            // assume the edit was lost.
            runCatching { reloadHomeOrder() }
        }
    }

    /**
     * Saves still queued behind the database thread. While any is, a read
     * would return the order from *before* the drop and snap the tiles back
     * until the write lands, so reads leave [homeOrder] alone.
     */
    private var homeOrderWrites = 0

    private suspend fun reloadHomeOrder() {
        if (homeOrderWrites > 0) return
        val stored = settings.homeAccountOrder()
        if (homeOrderWrites == 0) homeOrder = stored
    }

    fun toggleAmountsHidden() {
        amountsHidden = !amountsHidden
    }

    fun toggleExpanded(id: String) {
        expandedIds = if (id in expandedIds) expandedIds - id else expandedIds + id
    }

    companion object {
        /** How many recent transactions Home shows. */
        const val RECENT_COUNT = 5

        /** Leaf sums merged bottom-up; children first so each node holds its subtree. */
        fun rollupSubtrees(
            tree: List<AccountNode>,
            leafTotals: Map<String, Map<String, Long>>,
        ): Map<String, Map<String, Long>> {
            val result = mutableMapOf<String, Map<String, Long>>()
            fun post(node: AccountNode): Map<String, Long> {
                val merged = mutableMapOf<String, Long>()
                for ((c, v) in leafTotals[node.account.id].orEmpty()) merged[c] = v
                for (child in node.children) {
                    for ((c, v) in post(child)) {
                        merged[c] = (merged[c] ?: 0L) + v
                    }
                }
                result[node.account.id] = merged
                return merged
            }
            tree.forEach { post(it) }
            return result
        }

        /**
         * Ids excluded from the Home net-worth sum: a node's own
         * [Account.inNetWorth] flag, or any ancestor's — excluding a parent
         * cascades to every descendant regardless of what they're
         * individually set to.
         */
        fun excludedFromNetWorth(tree: List<AccountNode>): Set<String> {
            val result = mutableSetOf<String>()
            fun walk(node: AccountNode, ancestorExcluded: Boolean) {
                val excluded = ancestorExcluded || !node.account.inNetWorth
                if (excluded) result += node.account.id
                node.children.forEach { walk(it, excluded) }
            }
            tree.forEach { walk(it, false) }
            return result
        }

        /**
         * Net worth per commodity: sums each Asset/Liability node's own
         * postings (not the subtree rollup, since an excluded child must
         * drop out even when its parent is counted), skipping any id in
         * [excludedFromNetWorth].
         */
        fun netWorth(
            tree: List<AccountNode>,
            leafTotals: Map<String, Map<String, Long>>,
        ): Map<String, Long> {
            val excluded = excludedFromNetWorth(tree)
            val acc = mutableMapOf<String, Long>()
            fun walk(node: AccountNode) {
                if (node.account.id in excluded) return
                if (node.account.type == AccountType.Asset || node.account.type == AccountType.Liability) {
                    for ((c, v) in leafTotals[node.account.id].orEmpty()) {
                        acc[c] = (acc[c] ?: 0L) + v
                    }
                }
                node.children.forEach { walk(it) }
            }
            tree.forEach { walk(it) }
            return acc
        }
    }
}

/**
 * "US$ 1.234,56 · $ -500,00"; empty map renders "$ 0,00". Signed, because a
 * bare total line is never colored by its caller.
 */
fun formatTotals(totals: Map<String, Long>): String =
    if (totals.isEmpty()) {
        formatMoney(0L, Money.DEFAULT_COMMODITY)
    } else {
        totals.entries.sortedByDescending { it.value }
            .joinToString(" · ") { (c, v) -> formatMoney(v, c, signed = true) }
    }
