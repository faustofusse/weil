@file:OptIn(ExperimentalMaterial3Api::class)

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource
import weil.app.sharedui.generated.resources.Res
import weil.app.sharedui.generated.resources.action_back
import weil.app.sharedui.generated.resources.day_date
import weil.app.sharedui.generated.resources.day_date_year
import weil.app.sharedui.generated.resources.day_today
import weil.app.sharedui.generated.resources.day_yesterday
import weil.app.sharedui.generated.resources.journal_empty
import weil.app.sharedui.generated.resources.journal_more_postings
import weil.app.sharedui.generated.resources.journal_title
import weil.app.sharedui.generated.resources.months_short
import weil.app.sharedui.generated.resources.weekdays_short
import weil.app.sharedui.generated.resources.new_transaction
import weil.app.sharedui.generated.resources.new_transaction_hint

/** Journal of transactions, newest first, grouped under day headers. */
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
                title = { Text(stringResource(Res.string.journal_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(Res.string.action_back))
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
        PullToRefreshBox(
            isRefreshing = isSyncing,
            onRefresh = { sync() },
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 16.dp,
                    vertical = 8.dp,
                ),
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
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                stringResource(Res.string.journal_empty),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                stringResource(Res.string.new_transaction_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                var lastGroup: DayGroup? = null
                for (tx in items) {
                    val group = dayGroup(tx.date)
                    if (group != lastGroup) {
                        lastGroup = group
                        item(key = "day-${group.key}") {
                            DayHeader(group)
                        }
                    }
                    item(key = tx.id) {
                        TransactionCard(
                            tx = tx,
                            paths = paths,
                            onOpen = { onNavigateToEdit(tx.id) },
                        )
                    }
                }
                if (isLoadingMore) {
                    item(key = "loading") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp),
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Localized day header: Hoy / Ayer / "lun 8 sep" (plus the year when not the current one). */
@Composable
internal fun DayHeader(group: DayGroup, modifier: Modifier = Modifier) {
    Text(
        dayLabel(group),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(top = 16.dp, bottom = 4.dp),
    )
}

@Composable
internal fun dayLabel(group: DayGroup): String = when (group) {
    DayToday -> stringResource(Res.string.day_today)
    DayYesterday -> stringResource(Res.string.day_yesterday)
    is DayDate -> {
        val weekday = stringArrayResource(Res.array.weekdays_short).getOrElse(group.weekday) { "" }
        val month = stringArrayResource(Res.array.months_short).getOrElse(group.month - 1) { "" }
        if (group.year == null) {
            stringResource(Res.string.day_date, weekday, group.day, month)
        } else {
            stringResource(Res.string.day_date_year, weekday, group.day, month, group.year)
        }
    }
}

/** id → colon-joined full path for picker and journal rendering. */
suspend fun accountPaths(accounts: AccountsRepository): Map<String, String> =
    accounts.tree()
        .flatMap { it.selfAndDescendants }
        .associate { it.account.id to it.path }

@Composable
internal fun TransactionCard(
    tx: Transaction,
    paths: Map<String, String>,
    onOpen: () -> Unit,
) {
    Card(
        onClick = onOpen,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        tx.payee,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    tx.note?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Text(
                    timeShort(tx.date),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            Spacer(Modifier.height(4.dp))
            // Two lines of posting detail, "+n more" below — a psychic
            // 5-split stays one glance-tall card.
            val shown = tx.postings.take(2)
            for (posting in shown) {
                Row(modifier = Modifier.padding(top = 2.dp)) {
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
                        color = amountColor(posting.amountMinor),
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
            }
            val hidden = tx.postings.size - shown.size
            if (hidden > 0) {
                Text(
                    stringResource(Res.string.journal_more_postings, hidden),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            // per-commodity totals row when a transaction mixes commodities
            val totals = tx.postings.groupBy({ it.commodity }, { it.amountMinor })
                .mapValues { (_, amounts) -> amounts.sum() }
            if (totals.size > 1) {
                Row(
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    totals.forEach { (commodity, amount) ->
                        Text(
                            "$commodity ${formatMinorUnits(amount)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
