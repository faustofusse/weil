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
) {
    var tree by mutableStateOf<List<AccountNode>>(emptyList())
        private set
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    /**
     * id → subtree totals per commodity; the map includes leaf-only values
     * for every node, rolled up from the postings of the whole subtree.
     */
    var totals by mutableStateOf<Map<String, Map<String, Long>>>(emptyMap())
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
    }

    fun refresh() {
        scope.launch {
            busy = true
            error = null
            try {
                // Pull server state first; a failed sync must not hide the local view.
                try {
                    ledger.syncNow()
                } catch (e: Throwable) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                }
                tree = accounts.tree()
                val leafs = ledger.leafBalances()
                totals = rollupSubtrees(tree, leafs)
                loaded = true
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                error = e.message ?: e.toString()
            } finally {
                busy = false
            }
        }
    }

    fun mutate(action: suspend () -> Unit) {
        scope.launch {
            busy = true
            error = null
            try {
                action()
                tree = accounts.tree()
                totals = rollupSubtrees(tree, ledger.leafBalances())
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

    fun reparent(id: String, parentId: String) =
        mutate { accounts.reparent(id, parentId) }

    fun deleteAccount(id: String) =
        mutate { accounts.delete(id) }

    companion object {
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
