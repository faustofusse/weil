@file:OptIn(ExperimentalMaterial3Api::class)

package ar.fausto.weil

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
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

// Enough rows to cover any screen height while loading — the list is
// lazy, so declaring more than fit on screen costs nothing.
private const val JOURNAL_SKELETON_COUNT = 16

/** Journal of transactions, newest first, grouped under day headers. */
@Composable
fun JournalScreen(
    ledger: TransactionsRepository,
    accounts: AccountsRepository,
    onNavigateBack: () -> Unit,
    onNavigateToNew: () -> Unit,
    onOpenTransaction: (id: String) -> Unit,
) {
    var items by remember { mutableStateOf(emptyList<Transaction>()) }
    var cursor by remember { mutableStateOf<LedgerCursor?>(null) }
    var hasMore by remember { mutableStateOf(true) }
    var isInitialLoading by remember { mutableStateOf(true) }
    var isSyncing by remember { mutableStateOf(false) }
    var isLoadingMore by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var paths by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var types by remember { mutableStateOf<Map<String, AccountType>>(emptyMap()) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    suspend fun loadFirst() {
        val page = ledger.page()
        cursor = page.lastOrNull()?.let { LedgerCursor(it.date, it.id) }
        hasMore = page.size == LIST_PAGE_SIZE
        paths = accountPaths(accounts)
        types = accountTypes(accounts)
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
            isInitialLoading = false
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
                if (isInitialLoading) {
                    repeat(JOURNAL_SKELETON_COUNT) { i ->
                        item(key = "skeleton-$i") { TransactionCardSkeleton() }
                    }
                    return@LazyColumn
                }
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
                            types = types,
                            onOpen = { onOpenTransaction(tx.id) },
                        )
                    }
                }
                if (isLoadingMore) {
                    item(key = "loading") {
                        Column {
                            TransactionCardSkeleton()
                            TransactionCardSkeleton()
                        }
                    }
                }
            }
        }
    }
}

/**
 * Localized day header: Hoy / Ayer / "lun 8 sep" (plus the year when not the
 * current one). [top] is the gap above it — Home stacks day headers right
 * under a section title and needs a tighter one than the journal's own runs.
 */
@Composable
internal fun DayHeader(group: DayGroup, modifier: Modifier = Modifier, top: Dp = 16.dp) {
    Text(
        dayLabel(group),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(top = top, bottom = 4.dp, start = 4.dp),
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

/** id → account type, so journal/home rows can color a posting by what it did to an asset account. */
suspend fun accountTypes(accounts: AccountsRepository): Map<String, AccountType> =
    accounts.tree()
        .flatMap { it.selfAndDescendants }
        .associate { it.account.id to it.account.type }

@Composable
internal fun TransactionCard(
    tx: Transaction,
    paths: Map<String, String>,
    types: Map<String, AccountType> = emptyMap(),
    onOpen: () -> Unit,
) {
    // Same tonal surface as the account rows and empty-state cards — the
    // plain M3 Card default (containerColor = surface, a 1dp shadow) sat on
    // top of a background that's the same color, so it read flatter and
    // darker than every other card in the app.
    Surface(
        onClick = onOpen,
        shape = RoundedCornerShape(GroupRadius),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
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
                        color = postingColor(types[posting.accountId], posting.amountMinor),
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

/**
 * The two ends of a transaction as a person reads it: where the money left,
 * where it landed, and how much of the user's own money actually moved.
 *
 * [direction] is the only sign a user parses at a glance: +1 money came in,
 * -1 money went out, 0 an internal move (asset→asset, or paying off a card),
 * where a red/green amount would be a lie.
 */
internal data class TxnFlow(
    val fromId: String?,
    val toId: String?,
    val amountMinor: Long,
    val commodity: String,
    val direction: Int,
)

internal fun flowOf(tx: Transaction, types: Map<String, AccountType>): TxnFlow? {
    if (tx.postings.isEmpty()) return null
    // A mixed-commodity transaction (an FX trade) is summarised by its biggest
    // leg; the editor is where the full split lives.
    val commodity = tx.postings
        .groupBy { it.commodity }
        .maxByOrNull { (_, ps) -> ps.sumOf { abs(it.amountMinor) } }
        ?.key ?: Money.DEFAULT_COMMODITY
    val legs = tx.postings.filter { it.commodity == commodity }
    // Only the user's own money (assets + debts) signals a direction — the
    // category leg of an expense is bookkeeping, not a balance moving.
    val net = legs
        .filter { types[it.accountId] == AccountType.Asset || types[it.accountId] == AccountType.Liability }
        .sumOf { it.amountMinor }
    val size = legs.maxOfOrNull { abs(it.amountMinor) } ?: 0L
    return TxnFlow(
        fromId = legs.minByOrNull { it.amountMinor }?.accountId,
        toId = legs.maxByOrNull { it.amountMinor }?.accountId,
        amountMinor = if (net != 0L) net else size,
        commodity = commodity,
        direction = if (net > 0L) 1 else if (net < 0L) -1 else 0,
    )
}

@Composable
internal fun flowColor(direction: Int): Color = when {
    direction > 0 -> MaterialTheme.colorScheme.tertiary
    direction < 0 -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurface
}

/**
 * One movement on a single line — description first, the accounts it moved
 * between trailing it in a dimmer, smaller span ("Efectivo → Comida"), amount
 * right-aligned. Same 56.dp row metrics and grouped-card [skin] as the account
 * rows, so Home reads as two cards of the same list instead of two designs.
 * The full posting detail is one tap away in the editor.
 */
@Composable
internal fun TransactionRow(
    tx: Transaction,
    names: Map<String, String>,
    types: Map<String, AccountType>,
    onOpen: () -> Unit,
    skin: RowSkin? = null,
) {
    val flow = flowOf(tx, types)
    val from = flow?.fromId?.let { names[it] }
    val to = flow?.toId?.let { names[it] }
    // A split (more than the two ends shown) advertises what it's hiding.
    val extra = tx.postings.size - 2
    val moreLabel = if (extra > 0) stringResource(Res.string.journal_more_postings, extra) else null
    val route = buildString {
        when {
            from != null && to != null && from != to -> append("$from → $to")
            else -> append(from ?: to ?: "")
        }
        moreLabel?.let { if (isNotEmpty()) append("  ") ; append(it) }
    }
    // Well under onSurfaceVariant: at full strength the route read as a
    // second description instead of as context hanging off the first one.
    val dim = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    val dimSize = MaterialTheme.typography.bodySmall.fontSize
    val label = buildAnnotatedString {
        if (tx.payee.isNotBlank()) {
            append(tx.payee)
            if (route.isNotEmpty()) append("  ")
        }
        if (route.isNotEmpty()) {
            withStyle(SpanStyle(color = dim, fontSize = dimSize)) { append(route) }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (skin == null) Modifier else Modifier.clip(skin.shape).background(skin.container),
            ),
    ) {
        if (skin?.divider == true) {
            HorizontalDivider(
                modifier = Modifier.padding(start = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpen)
                .heightIn(min = 56.dp)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                // The payee is what identifies the row, so the accounts are
                // what gets cut when space runs out.
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (flow != null) {
                Text(
                    // The commodity is spelled out only when it isn't the
                    // default one — an all-ARS ledger doesn't need "ARS" on
                    // every row, a USD movement must never be mistaken for one.
                    if (flow.commodity == Money.DEFAULT_COMMODITY) {
                        formatMinorUnits(flow.amountMinor)
                    } else {
                        "${flow.commodity} ${formatMinorUnits(flow.amountMinor)}"
                    },
                    style = MaterialTheme.typography.titleSmall,
                    color = flowColor(flow.direction),
                    maxLines = 1,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
        }
    }
}
