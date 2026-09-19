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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.layout.Box
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
import weil.app.sharedui.generated.resources.action_cancel
import weil.app.sharedui.generated.resources.action_delete
import weil.app.sharedui.generated.resources.action_ok
import weil.app.sharedui.generated.resources.action_sync
import weil.app.sharedui.generated.resources.day_date
import weil.app.sharedui.generated.resources.day_date_year
import weil.app.sharedui.generated.resources.day_today
import weil.app.sharedui.generated.resources.day_yesterday
import weil.app.sharedui.generated.resources.journal_dev_delete_range
import weil.app.sharedui.generated.resources.journal_dev_delete_range_body
import weil.app.sharedui.generated.resources.journal_dev_delete_range_confirm
import weil.app.sharedui.generated.resources.journal_dev_delete_range_done
import weil.app.sharedui.generated.resources.journal_dev_delete_range_from
import weil.app.sharedui.generated.resources.journal_dev_delete_range_invalid
import weil.app.sharedui.generated.resources.journal_dev_delete_range_title
import weil.app.sharedui.generated.resources.journal_dev_delete_range_to
import weil.app.sharedui.generated.resources.journal_empty
import weil.app.sharedui.generated.resources.journal_more_postings
import weil.app.sharedui.generated.resources.journal_title
import weil.app.sharedui.generated.resources.months_full
import weil.app.sharedui.generated.resources.nav_movements
import weil.app.sharedui.generated.resources.more_options
import weil.app.sharedui.generated.resources.weekdays_full
import weil.app.sharedui.generated.resources.new_transaction
import weil.app.sharedui.generated.resources.new_transaction_hint

// Enough rows to cover any screen height while loading — the list is
// lazy, so declaring more than fit on screen costs nothing.
private const val JOURNAL_SKELETON_COUNT = 16

private const val DAY_MILLIS = 24L * 60 * 60 * 1000

/** Journal of transactions, newest first, grouped under day headers. */
@Composable
fun JournalScreen(
    state: JournalState,
    onNavigateBack: () -> Unit,
    onNavigateToNew: () -> Unit,
    onOpenTransaction: (id: String) -> Unit,
    /**
     * Non-null when this screen is a bottom-bar root, which also means there
     * is nothing to go back to: the back arrow is dropped in that case rather
     * than left on screen as a no-op.
     */
    bottomBar: (@Composable () -> Unit)? = null,
) {
    val listState = rememberLazyListState()
    var showDeleteRangeDialog by remember { mutableStateOf(false) }

    // Consecutive same-day runs, computed once per page rather than inside
    // the LazyListScope builder (which isn't @Composable, so a plain
    // grouping there would redo the work — and worse, lose its identity —
    // on every recomposition). Items arrive newest-first from the server, so
    // a day only ever starts one run: no need to merge non-adjacent slices.
    val dayRuns = remember(state.items) {
        buildList {
            var current: DayGroup? = null
            var bucket = mutableListOf<Transaction>()
            for (tx in state.items) {
                val group = dayGroup(tx.date)
                if (group != current) {
                    current?.let { add(it to bucket) }
                    current = group
                    bucket = mutableListOf()
                }
                bucket.add(tx)
            }
            current?.let { add(it to bucket) }
        }
    }

    // No-ops after the first real page has been fetched, so returning from a
    // transaction (this composable re-entering composition) never re-fetches
    // — the state survives navigation because it's hoisted above the nav host.
    LaunchedEffect(Unit) { state.ensureLoaded() }

    // Infinite scroll.
    LaunchedEffect(listState, state.hasMore, state.items.size) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .distinctUntilChanged()
            .collect { last ->
                if (last != null && last >= state.items.size - 5) state.loadMore()
            }
    }

    Scaffold(
        topBar = {
            // As a tab root it wears the shared header (same height and title
            // style as Inicio/Categorías/Mi perfil); pushed from elsewhere it
            // keeps the stock bar with its back arrow.
            val actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {
                IconButton(onClick = { state.refresh() }, enabled = !state.pullRefreshing) {
                    Icon(Icons.Filled.Refresh, contentDescription = stringResource(Res.string.action_sync))
                }
                Box {
                    var menuOpen by remember { mutableStateOf(false) }
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = stringResource(Res.string.more_options))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(Res.string.journal_dev_delete_range)) },
                            leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                showDeleteRangeDialog = true
                            },
                        )
                    }
                }
            }
            if (bottomBar != null) {
                AppTopBar(title = stringResource(Res.string.nav_movements), actions = actions)
            } else {
                TopAppBar(
                    title = { Text(stringResource(Res.string.journal_title)) },
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(Res.string.action_back))
                        }
                    },
                    actions = actions,
                )
            }
        },
        bottomBar = bottomBar ?: {},
        floatingActionButton = {
            // As a tab root the bar carries the create button already; a
            // second one in the corner would be the same action twice.
            if (bottomBar == null) {
                FloatingActionButton(
                    onClick = onNavigateToNew,
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(Res.string.new_transaction))
                }
            }
        },
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = state.pullRefreshing,
            onRefresh = { state.refresh() },
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
                if (state.isInitialLoading && state.items.isEmpty()) {
                    repeat(JOURNAL_SKELETON_COUNT) { i ->
                        item(key = "skeleton-$i") { TransactionCardSkeleton() }
                    }
                    return@LazyColumn
                }
                state.error?.let { err ->
                    item(key = "error") {
                        Text(
                            err,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }
                }
                if (state.items.isEmpty() && !state.isInitialLoading && state.error == null) {
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
                dayRuns.forEach { (group, txs) ->
                    item(key = "day-${group.key}") {
                        DayHeader(group)
                    }
                    // Same row as Home's preview — the two read as one list
                    // design now, not two that happen to sit in the same tab
                    // bar. Unlike Home, the day header stays a run's own
                    // heading here: with dozens of rows a day it can't be
                    // mistaken for a caption on the first one, the ambiguity
                    // that made Home drop it.
                    items(txs, key = { it.id }) { tx ->
                        MovementRow(
                            tx = tx,
                            names = state.names,
                            types = state.types,
                            icons = state.icons,
                            colors = state.colors,
                            hidden = false,
                            onOpen = { onOpenTransaction(tx.id) },
                        )
                    }
                }
                if (state.isLoadingMore) {
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

    if (showDeleteRangeDialog) {
        DeleteRangeDialog(state = state, onDismiss = { showDeleteRangeDialog = false })
    }
}

/**
 * Dev-only utility from the journal's overflow menu: pick a from/to date and
 * hard-delete every transaction in that (inclusive) range. No undo — this is
 * for clearing test data, not something a real user flow should ever offer.
 */
@Composable
private fun DeleteRangeDialog(state: JournalState, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var fromText by remember { mutableStateOf<String?>(null) }
    var toText by remember { mutableStateOf<String?>(null) }
    var pickingFrom by remember { mutableStateOf(false) }
    var pickingTo by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var resultCount by remember { mutableStateOf<Int?>(null) }

    val invalidMessage = stringResource(Res.string.journal_dev_delete_range_invalid)
    val doneMessage = resultCount?.let { stringResource(Res.string.journal_dev_delete_range_done, it) }
    LaunchedEffect(doneMessage) {
        doneMessage?.let {
            Feedback.show(it)
            onDismiss()
        }
    }

    fun confirm() {
        val from = fromText?.let { parseDateInput(it) }
        // Inclusive of the whole "to" day: its parsed midnight plus one day
        // minus a millisecond.
        val toStart = toText?.let { parseDateInput(it) }
        val to = toStart?.plus(DAY_MILLIS - 1)
        if (from == null || to == null || from > to) {
            Feedback.show(invalidMessage)
            return
        }
        deleting = true
        scope.launch {
            try {
                resultCount = state.deleteRange(from, to)
            } finally {
                deleting = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!deleting) onDismiss() },
        title = { Text(stringResource(Res.string.journal_dev_delete_range_title)) },
        text = {
            Column {
                Text(
                    stringResource(Res.string.journal_dev_delete_range_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { pickingFrom = true }) {
                        Text(fromText ?: stringResource(Res.string.journal_dev_delete_range_from))
                    }
                    TextButton(onClick = { pickingTo = true }) {
                        Text(toText ?: stringResource(Res.string.journal_dev_delete_range_to))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { confirm() },
                enabled = !deleting && fromText != null && toText != null,
            ) { Text(stringResource(Res.string.action_delete)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !deleting) {
                Text(stringResource(Res.string.action_cancel))
            }
        },
    )

    if (pickingFrom) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = fromText?.let { parseDateInput(it) } ?: epochMillis(),
        )
        DatePickerDialog(
            onDismissRequest = { pickingFrom = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { fromText = dateInputOf(it) }
                    pickingFrom = false
                }) { Text(stringResource(Res.string.action_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { pickingFrom = false }) { Text(stringResource(Res.string.action_cancel)) }
            },
        ) { DatePicker(state = pickerState) }
    }

    if (pickingTo) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = toText?.let { parseDateInput(it) } ?: epochMillis(),
        )
        DatePickerDialog(
            onDismissRequest = { pickingTo = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { toText = dateInputOf(it) }
                    pickingTo = false
                }) { Text(stringResource(Res.string.action_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { pickingTo = false }) { Text(stringResource(Res.string.action_cancel)) }
            },
        ) { DatePicker(state = pickerState) }
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
        // Not `primary`: in a warm palette that coral sits one step from the
        // `error` used by negative amounts, so "Hoy" and "−500,00" read as the
        // same kind of mark. Dates are structure; color belongs to the money.
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(top = top, bottom = 4.dp, start = 4.dp),
    )
}

@Composable
internal fun dayLabel(group: DayGroup): String = when (group) {
    DayToday -> stringResource(Res.string.day_today)
    DayYesterday -> stringResource(Res.string.day_yesterday)
    is DayDate -> {
        val weekday = stringArrayResource(Res.array.weekdays_full).getOrElse(group.weekday) { "" }
        val month = stringArrayResource(Res.array.months_full).getOrElse(group.month - 1) { "" }
        if (group.year == null) {
            stringResource(Res.string.day_date, weekday, group.day, month)
        } else {
            stringResource(Res.string.day_date_year, weekday, group.day, month, group.year)
        }
    }
}

/** id → colon-joined full path for picker and journal rendering. */
suspend fun accountPaths(accounts: AccountsRepository): Map<String, String> =
    disambiguatedPaths(accounts.tree())

/**
 * id → path, with the currency appended when a namesake shares that path
 * ("Activos:Banco (USD)"). Two accounts may have the same name as long as
 * their currencies differ, and a bare path then names neither of them.
 *
 * Only the ambiguous ones are decorated: a suffix on every row would read as
 * part of the account's name everywhere the path is shown.
 */
fun disambiguatedPaths(tree: List<AccountNode>): Map<String, String> {
    val nodes = tree.flatMap { it.selfAndDescendants }
    val shared = nodes.groupBy { it.path.lowercase() }.filterValues { it.size > 1 }.keys
    return nodes.associate { node ->
        val commodity = node.account.commodity
        val path = node.path.censored()
        node.account.id to if (commodity != null && node.path.lowercase() in shared) {
            "$path ($commodity)"
        } else {
            path
        }
    }
}

/** id → account type, so journal/home rows can color a posting by what it did to an asset account. */
suspend fun accountTypes(accounts: AccountsRepository): Map<String, AccountType> =
    accounts.tree()
        .flatMap { it.selfAndDescendants }
        .associate { it.account.id to it.account.type }

/** id → leaf name, for the one-line [TransactionRow] used by both Home and the journal. */
suspend fun accountNames(accounts: AccountsRepository): Map<String, String> =
    accounts.tree()
        .flatMap { it.selfAndDescendants }
        .associate { it.account.id to it.account.name.censored() }

/** [paths], [types] and [names] from a single [AccountsRepository.tree] call. */
internal class AccountIndex(
    val paths: Map<String, String>,
    val types: Map<String, AccountType>,
    val names: Map<String, String>,
    val icons: Map<String, String?>,
    val colors: Map<String, String?>,
)

internal suspend fun accountIndex(accounts: AccountsRepository): AccountIndex {
    val nodes = accounts.tree().flatMap { it.selfAndDescendants }
    return AccountIndex(
        paths = nodes.associate { it.account.id to it.path.censored() },
        types = nodes.associate { it.account.id to it.account.type },
        names = nodes.associate { it.account.id to it.account.name.censored() },
        icons = nodes.associate { it.account.id to it.account.icon },
        colors = nodes.associate { it.account.id to it.account.color },
    )
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
 * The three fixed hex colors for a movement row's amount, as specified
 * directly (not the theme's `tertiary`/`error`, which happen to differ by
 * theme): income, expense, transfer between the user's own accounts. Used
 * by [MovementRow] and the classic [TransactionRow] — the two places an
 * amount stands for an entire transaction in a list — not by the detail
 * screen's hero figure, which stays on the theme.
 */
internal fun transactionRowColor(direction: Int): Color = when {
    direction > 0 -> Color(0xFF55A345)
    direction < 0 -> Color(0xFFDB1616)
    else -> Color(0xFF363636)
}

// A small "›" chevron reads as "leads to" without the vertical-alignment
// headaches of the wider "→" arrow glyph, which sits at different heights
// across the platform default fonts (Roboto on Android, the desktop font,
// San Francisco on iOS). The chevron is designed to sit on the x-height like
// regular punctuation, so it centers the same everywhere with no hacks.
internal const val ROUTE_ARROW = "›"

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
    /**
     * Per-row date, for callers that don't group into day runs (Home). It
     * rides under the amount so the payee keeps the full width of the line.
     */
    dateLabel: String? = null,
) {
    val flow = flowOf(tx, types)
    val from = flow?.fromId?.let { names[it] }
    val to = flow?.toId?.let { names[it] }
    // A split (more than the two ends shown) advertises what it's hiding.
    val extra = tx.postings.size - 2
    val moreLabel = if (extra > 0) stringResource(Res.string.journal_more_postings, extra) else null
    val hasRoute = (from != null || to != null)
    // Well under onSurfaceVariant: at full strength the route read as a
    // second description instead of as context hanging off the first one.
    val dim = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    val dimSize = MaterialTheme.typography.bodySmall.fontSize
    val label = buildAnnotatedString {
        if (tx.payee.isNotBlank()) {
            append(tx.payee.censored())
            if (hasRoute) append("  ")
        }
        if (hasRoute) {
            withStyle(SpanStyle(color = dim, fontSize = dimSize)) {
                when {
                    from != null && to != null && from != to -> append("$from $ROUTE_ARROW $to")
                    else -> append(from ?: to ?: "")
                }
                moreLabel?.let { if (length > 0) append("  "); append(it) }
            }
        } else if (moreLabel != null) {
            withStyle(SpanStyle(color = dim, fontSize = dimSize)) { append(moreLabel) }
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
            if (flow != null || dateLabel != null) {
                Column(
                    horizontalAlignment = Alignment.End,
                    modifier = Modifier.padding(start = 12.dp),
                ) {
                    if (flow != null) {
                        Text(
                            // Symbol always, sign never: "$" vs "US$" is what
                            // keeps a dollar row from being read as pesos,
                            // and the direction is already in the color.
                            formatMoney(flow.amountMinor, flow.commodity),
                            style = MaterialTheme.typography.titleSmall,
                            color = transactionRowColor(flow.direction),
                            maxLines = 1,
                        )
                    }
                    if (dateLabel != null) {
                        Text(
                            dateLabel,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}
