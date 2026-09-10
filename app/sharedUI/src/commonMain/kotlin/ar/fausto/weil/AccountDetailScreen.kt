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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Per-account register: postings of the account (or its whole subtree, via the
 * toggle) newest first, with a running balance.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountDetailScreen(
    ledgerState: LedgerState,
    accountId: String,
    onNavigateBack: () -> Unit,
    onNavigateToEdit: (id: String) -> Unit,
    onNavigateToNew: () -> Unit,
) {
    val ledger = ledgerState.ledger
    val accounts = ledgerState.accounts
    var node by remember { mutableStateOf<AccountNode?>(null) }
    var includeSubtree by remember { mutableStateOf(false) }
    var entries by remember { mutableStateOf(emptyList<RegisterEntry>()) }
    var loadingMore by remember { mutableStateOf(false) }
    var hasMore by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    suspend fun loadAll() {
        node = ledgerState.tree
            .flatMap { it.selfAndDescendants }
            .firstOrNull { it.account.id == accountId }
        val ids = if (includeSubtree) {
            accounts.subtreeIds(ledgerState.tree, accountId)
        } else {
            listOf(accountId)
        }
        entries = ledger.register(subtreeIds = ids)
        hasMore = entries.size >= LIST_PAGE_SIZE
    }

    fun reload() {
        scope.launch {
            error = null
            try {
                loadAll()
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                error = e.message ?: e.toString()
            }
        }
    }

    LaunchedEffect(includeSubtree) { reload() }

    fun loadMore() {
        if (loadingMore || !hasMore || node == null) return
        loadingMore = true
        scope.launch {
            try {
                val ids = if (includeSubtree) {
                    accounts.subtreeIds(ledgerState.tree, accountId)
                } else {
                    listOf(accountId)
                }
                val last = entries.lastOrNull() ?: return@launch
                val nextPage = ledger.register(
                    subtreeIds = ids,
                    before = LedgerCursor(last.date, last.posting.transactionId),
                )
                hasMore = nextPage.size >= LIST_PAGE_SIZE
                entries = entries + nextPage
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                error = e.message ?: e.toString()
            } finally {
                loadingMore = false
            }
        }
    }

    // Consistent with the journal: infinite scroll instead of a manual button.
    LaunchedEffect(listState, hasMore, entries.size) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .distinctUntilChanged()
            .collect { last ->
                if (last != null && last >= entries.size - 5) loadMore()
            }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        node?.path ?: "Account",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onNavigateToNew,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Icon(Icons.Filled.Add, contentDescription = "New transaction")
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    formatTotals(ledgerState.totals[accountId].orEmpty()),
                    style = MaterialTheme.typography.bodyLarge,
                )
                ledgerState.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = includeSubtree, onCheckedChange = { includeSubtree = it })
                    Spacer(Modifier.width(8.dp))
                    Text("Include sub-accounts", style = MaterialTheme.typography.bodyMedium)
                }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            ) {
                if (entries.isEmpty() && !loadingMore && error == null) {
                    item(key = "empty") {
                        Text(
                            "No postings yet",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }
                }
                itemsIndexed(entries, key = { _, it -> it.posting.id }) { _, entry ->
                    RegisterRowView(
                        entry = entry,
                        onOpen = { id -> onNavigateToEdit(id) },
                    )
                }
                if (loadingMore) {
                    item(key = "loading") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp),
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RegisterRowView(
    entry: RegisterEntry,
    onOpen: (id: String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen(entry.posting.transactionId) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                entry.payee,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                timeShort(entry.date),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(horizontalArrangement = Arrangement.End) {
            Text(
                formatMinorUnits(entry.posting.amountMinor),
                style = MaterialTheme.typography.bodyMedium,
                color = amountColor(entry.posting.amountMinor),
                modifier = Modifier.padding(end = 12.dp),
            )
            Text(
                entry.balanceAfter.format() + " " + entry.balanceAfter.commodity,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
