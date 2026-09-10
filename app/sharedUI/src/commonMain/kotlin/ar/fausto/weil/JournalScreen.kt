package ar.fausto.weil

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** Journal of transactions, newest first, with indented postings per header. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JournalScreen(
    ledger: TransactionsRepository,
    accounts: AccountsRepository,
    onNavigateBack: () -> Unit,
    onNavigateToNew: () -> Unit,
    onNavigateToEdit: (id: String) -> Unit,
) {
    var items by remember { mutableStateOf(emptyList<Transaction>()) }
    var cursor by remember { mutableStateOf<LedgerCursor?>(null) }
    var hasMore by remember { mutableStateOf(true) }
    var isSyncing by remember { mutableStateOf(false) }
    var isLoadingMore by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var paths by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    suspend fun loadFirst() {
        val page = ledger.page()
        cursor = page.lastOrNull()?.let { LedgerCursor(it.date, it.id) }
        hasMore = page.size == LIST_PAGE_SIZE
        paths = accountPaths(accounts)
        items = page
    }

    suspend fun refreshAll() {
        isSyncing = true
        error = null
        try {
            ledger.syncNow()
            loadFirst()
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            error = e.message ?: e.toString()
        } finally {
            isSyncing = false
        }
    }

    fun sync() = scope.launch { refreshAll() }

    fun loadMore() {
        val current = cursor ?: return
        if (isLoadingMore || !hasMore) return
        isLoadingMore = true
        scope.launch {
            try {
                val page = ledger.page(before = current)
                cursor = page.lastOrNull()?.let { LedgerCursor(it.date, it.id) }
                hasMore = page.size == LIST_PAGE_SIZE
                items = items + page
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                error = e.message ?: e.toString()
            } finally {
                isLoadingMore = false
            }
        }
    }

    LaunchedEffect(Unit) { refreshAll() }

    // Live refresh after edits made anywhere (mutations emit to `changes`).
    LaunchedEffect(Unit) {
        ledger.changes.collect { loadFirst() }
    }

    // Infinite scroll.
    LaunchedEffect(listState, hasMore, items.size) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .distinctUntilChanged()
            .collect { last ->
                if (last != null && last >= items.size - 5) loadMore()
            }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Journal") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onNavigateToNew) {
                Icon(Icons.Filled.Add, contentDescription = "New transaction")
            }
        },
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = isSyncing,
            onRefresh = { sync() },
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            ) {
                error?.let { err ->
                    item(key = "error") {
                        Text(
                            err,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }
                }
                if (items.isEmpty() && !isSyncing && error == null) {
                    item(key = "empty") {
                        Text(
                            "No transactions yet — press + to record one",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }
                }
                itemsIndexed(items, key = { _, it -> it.id }) { _, tx ->
                    TransactionCard(
                        tx = tx,
                        paths = paths,
                        onOpen = { id -> onNavigateToEdit(id) },
                    )
                }
                if (isLoadingMore) {
                    item(key = "loading") {
                        Text(
                            "Loading more…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

/** id → colon-joined full path for picker and journal rendering. */
suspend fun accountPaths(accounts: AccountsRepository): Map<String, String> =
    accounts.tree()
        .flatMap { it.selfAndDescendants }
        .associate { it.account.id to it.path }

@Composable
private fun TransactionCard(
    tx: Transaction,
    paths: Map<String, String>,
    onOpen: (id: String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen(tx.id) }
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            formatTimestamp(tx.date) + "  " + tx.payee,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        tx.note?.let {
            if (it.isNotBlank()) {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        tx.postings.forEach { posting ->
            Row(modifier = Modifier.padding(start = 16.dp)) {
                Text(
                    paths[posting.accountId] ?: posting.accountId,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    formatMinorUnits(posting.amountMinor),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (posting.amountMinor >= 0) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
        }
        // per-commodity totals row when a transaction mixes commodities
        val totals = tx.postings.groupBy({ it.commodity }, { it.amountMinor })
            .mapValues { (_, amounts) -> amounts.sum() }
        if (totals.size > 1) {
            Text(
                formatTotals(totals),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, top = 2.dp),
            )
        }
        Spacer(Modifier.height(4.dp))
    }
}
