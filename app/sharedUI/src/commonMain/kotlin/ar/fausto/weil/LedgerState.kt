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
 * Account-tree state hoisted above the nav host next to the old [AccountsState]
 * style: the tree, per-node rollups and the mutations survive navigating away.
 * Journal/register paging lives in the screens themselves.
 */
@Stable
class LedgerState(
    val accounts: AccountsRepository,
    val ledger: TransactionsRepository,
    val settings: SettingsRepository,
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
    /** Survives navigation because the state lives above the nav host. */
    var expandedIds by mutableStateOf<Set<String>>(emptySet())
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
    /**
     * id → subtree totals per commodity; the map includes leaf-only values
     * for every node, rolled up from the postings of the whole subtree.
     */
    var totals by mutableStateOf<Map<String, Map<String, Long>>>(emptyMap())
        private set
    /**
     * id → that account's own postings only, no descendants. Diffing this
     * against [totals] is how a row knows whether the number it shows is a
     * subtree rollup (someone should say so) or the account's own balance.
     */
    var leafTotals by mutableStateOf<Map<String, Map<String, Long>>>(emptyMap())
        private set
    /**
     * Latest [RECENT_COUNT] transactions for Home's "recent" section. Lives
     * here, not in a screen-local `remember`, so it survives navigating away
     * and back: Home would otherwise dispose its composition and briefly
     * show nothing while it re-fetched.
     */
    var recent by mutableStateOf<List<Transaction>>(emptyList())
        private set

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
            settings.changes.collect { defaultAccounts = settings.defaultAccounts() }
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
        leafTotals = leafs
        totals = rollupSubtrees(newTree, leafs)
        defaultAccounts = settings.defaultAccounts()
        recent = ledger.page(limit = RECENT_COUNT)
    }

    fun mutate(action: suspend () -> Unit) {
        scope.launch {
            busy = true
            error = null
            try {
                action()
                tree = accounts.tree()
                val leafs = ledger.leafBalances()
                leafTotals = leafs
                totals = rollupSubtrees(tree, leafs)
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                error = e.message ?: e.toString()
            } finally {
                busy = false
            }
        }
    }

    fun addAccount(name: String, type: AccountType, parentId: String?) =
        mutate { accounts.add(name, type, parentId) }

    fun rename(id: String, name: String) =
        mutate { accounts.rename(id, name) }

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

    /** Only meaningful for Asset/Liability; see [excludedFromNetWorth]. */
    fun setInNetWorth(id: String, included: Boolean) =
        mutate { accounts.setInNetWorth(id, included) }

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

/** "USD 1,234.56 · ARS -500.00"; empty map renders "0". */
fun formatTotals(totals: Map<String, Long>): String =
    if (totals.isEmpty()) {
        "0"
    } else {
        totals.entries.sortedByDescending { it.value }
            .joinToString(" · ") { (c, v) -> "$c ${formatMinorUnits(v)}" }
    }
