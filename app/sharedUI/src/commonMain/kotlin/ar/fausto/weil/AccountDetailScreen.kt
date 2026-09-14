package ar.fausto.weil

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import org.jetbrains.compose.resources.stringResource
import weil.app.sharedui.generated.resources.Res
import weil.app.sharedui.generated.resources.action_back
import weil.app.sharedui.generated.resources.detail_account_fallback
import weil.app.sharedui.generated.resources.detail_include_subaccounts
import weil.app.sharedui.generated.resources.detail_no_postings
import weil.app.sharedui.generated.resources.more_options
import weil.app.sharedui.generated.resources.new_transaction

/**
 * Per-account register: postings of the account (or its whole subtree, via the
 * chip shown for parent accounts) newest first under day headers, with a
 * running balance. The overflow opens the same rename/move/delete sheet as a
 * long press on the account row.
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
    val node = findNode(ledgerState.tree, accountId)
    var includeSubtree by remember { mutableStateOf(false) }
    var entries by remember { mutableStateOf(emptyList<RegisterEntry>()) }
    var loaded by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    var hasMore by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var actions by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    fun ids(): List<String> =
        if (includeSubtree) accounts.subtreeIds(ledgerState.tree, accountId) else listOf(accountId)

    suspend fun loadAll() {
        error = null
        try {
            entries = ledger.register(subtreeIds = ids())
            hasMore = entries.size >= LIST_PAGE_SIZE
            loaded = true
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            error = e.message ?: e.toString()
        }
    }

    LaunchedEffect(includeSubtree) { loadAll() }

    // Edits made from this register (or anywhere else) show up on return.
    LaunchedEffect(Unit) {
        ledger.changes.collect { loadAll() }
    }

    fun loadMore() {
        if (loadingMore || !hasMore || !loaded) return
        val last = entries.lastOrNull() ?: return
        loadingMore = true
        scope.launch {
            try {
                val nextPage = ledger.register(
                    subtreeIds = ids(),
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
                if (last != null && last >= listState.layoutInfo.totalItemsCount - 5) loadMore()
            }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        node?.account?.name ?: stringResource(Res.string.detail_account_fallback),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(Res.string.action_back))
                    }
                },
                actions = {
                    if (node != null) {
                        IconButton(onClick = { actions = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(Res.string.more_options))
                        }
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
                Icon(Icons.Filled.Add, contentDescription = stringResource(Res.string.new_transaction))
            }
        },
    ) { innerPadding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 88.dp),
        ) {
            item(key = "header") {
                Column(modifier = Modifier.padding(bottom = 8.dp)) {
                    node?.let { n ->
                        if (n.path != n.account.name) {
                            Text(
                                n.path,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Text(
                        formatTotals(ledgerState.totals[accountId].orEmpty()),
                        style = MaterialTheme.typography.headlineMedium,
                    )
                    (error ?: ledgerState.error)?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    }
                    if (node?.children?.isNotEmpty() == true) {
                        FilterChip(
                            selected = includeSubtree,
                            onClick = { includeSubtree = !includeSubtree },
                            label = { Text(stringResource(Res.string.detail_include_subaccounts)) },
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
            if (entries.isEmpty() && loaded && error == null) {
                item(key = "empty") {
                    EmptyHint(title = stringResource(Res.string.detail_no_postings))
                }
            }
            var lastGroup: DayGroup? = null
            for (entry in entries) {
                val group = dayGroup(entry.date)
                if (group != lastGroup) {
                    lastGroup = group
                    item(key = "day-${group.key}") { DayHeader(group) }
                }
                item(key = entry.posting.id) {
                    RegisterRowView(
                        entry = entry,
                        onOpen = { id -> onNavigateToEdit(id) },
                    )
                }
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

    if (actions && node != null) {
        AccountActionsSheet(
            state = ledgerState,
            account = node.account,
            onDismiss = { actions = false },
            onDeleted = onNavigateBack,
        )
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
            .heightIn(min = 56.dp)
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
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(start = 12.dp)) {
            Text(
                formatMinorUnits(entry.posting.amountMinor),
                style = MaterialTheme.typography.bodyLarge,
                color = amountColor(entry.posting.amountMinor),
            )
            Text(
                entry.balanceAfter.commodity + " " + entry.balanceAfter.format(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
