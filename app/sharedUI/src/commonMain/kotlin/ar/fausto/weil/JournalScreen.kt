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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource
import weil.app.sharedui.generated.resources.Res
import weil.app.sharedui.generated.resources.action_back
import weil.app.sharedui.generated.resources.action_cancel
import weil.app.sharedui.generated.resources.action_close
import weil.app.sharedui.generated.resources.action_search
import weil.app.sharedui.generated.resources.action_delete
import weil.app.sharedui.generated.resources.action_ok
import weil.app.sharedui.generated.resources.action_undo
import weil.app.sharedui.generated.resources.action_undo
import weil.app.sharedui.generated.resources.day_date
import weil.app.sharedui.generated.resources.day_date_year
import weil.app.sharedui.generated.resources.day_today
import weil.app.sharedui.generated.resources.day_yesterday
import weil.app.sharedui.generated.resources.journal_delete_failed
import weil.app.sharedui.generated.resources.journal_scroll_top
import weil.app.sharedui.generated.resources.journal_delete_selected
import weil.app.sharedui.generated.resources.journal_delete_selected_body
import weil.app.sharedui.generated.resources.journal_delete_selected_title
import weil.app.sharedui.generated.resources.journal_dev_delete_range
import weil.app.sharedui.generated.resources.journal_dev_delete_range_body
import weil.app.sharedui.generated.resources.journal_dev_delete_range_confirm
import weil.app.sharedui.generated.resources.journal_dev_delete_range_done
import weil.app.sharedui.generated.resources.journal_dev_delete_range_from
import weil.app.sharedui.generated.resources.journal_dev_delete_range_invalid
import weil.app.sharedui.generated.resources.journal_dev_delete_range_title
import weil.app.sharedui.generated.resources.journal_dev_delete_range_to
import weil.app.sharedui.generated.resources.journal_date_range_filter
import weil.app.sharedui.generated.resources.journal_date_range_filter_active
import weil.app.sharedui.generated.resources.journal_date_range_filter_apply
import weil.app.sharedui.generated.resources.journal_date_range_filter_body
import weil.app.sharedui.generated.resources.journal_date_range_filter_clear
import weil.app.sharedui.generated.resources.journal_date_range_filter_from
import weil.app.sharedui.generated.resources.journal_date_range_filter_invalid
import weil.app.sharedui.generated.resources.journal_date_range_filter_title
import weil.app.sharedui.generated.resources.journal_date_range_filter_to
import weil.app.sharedui.generated.resources.journal_empty
import weil.app.sharedui.generated.resources.journal_filter_all
import weil.app.sharedui.generated.resources.journal_more_postings
import weil.app.sharedui.generated.resources.journal_search_empty
import weil.app.sharedui.generated.resources.journal_search_hint
import weil.app.sharedui.generated.resources.journal_selected_count
import weil.app.sharedui.generated.resources.journal_title
import weil.app.sharedui.generated.resources.journal_title
import weil.app.sharedui.generated.resources.journal_filter_expense
import weil.app.sharedui.generated.resources.journal_filter_income
import weil.app.sharedui.generated.resources.journal_filter_transfer
import weil.app.sharedui.generated.resources.months_full
import weil.app.sharedui.generated.resources.nav_movements
import weil.app.sharedui.generated.resources.more_options
import weil.app.sharedui.generated.resources.weekdays_full
import weil.app.sharedui.generated.resources.new_transaction
import weil.app.sharedui.generated.resources.new_transaction_hint

// Enough rows to cover any screen height while loading — the list is
// lazy, so declaring more than fit on screen costs nothing.
private const val JOURNAL_SKELETON_COUNT = 16

/** Rows scrolled past before the scroll-to-top button appears. */
private const val SCROLL_TOP_THRESHOLD = 8

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
    val listState = rememberLazyListState(state.scrollIndex, state.scrollOffset)
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .collect { (index, offset) ->
                state.scrollIndex = index
                state.scrollOffset = offset
            }
    }
    var showDeleteRangeDialog by remember { mutableStateOf(false) }
    var showDateRangeFilterDialog by remember { mutableStateOf(false) }
    var showDeleteSelectedDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val selecting = state.isSelecting
    // A selection run always wins the bar's mode: long-pressing a row while
    // a search is open ends up ticking a search result, and the delete
    // action needs the same priority the count/back title already has.
    val searching = state.searching && !selecting
    val searchFocus = remember { FocusRequester() }

    // Consecutive same-day runs, computed once per page rather than inside
    // the LazyListScope builder (which isn't @Composable, so a plain
    // grouping there would redo the work — and worse, lose its identity —
    // on every recomposition). Items arrive newest-first from the server, so
    // a day only ever starts one run: no need to merge non-adjacent slices.
    // The kind filter narrows what's grouped, not what's fetched: paging
    // still walks the whole journal, and "Gasto" is a lens over pages already
    // on screen rather than a different query. The search field is the
    // opposite — it is part of the query ([JournalState.updateQuery]), so
    // there is nothing left to match here.
    val filteredItems = remember(state.items, state.filter, state.types) {
        if (state.filter == JournalFilter.All) {
            state.items
        } else {
            state.items.filter { matchesFilter(it, state.filter, state.types) }
        }
    }
    val dayRuns = remember(filteredItems) {
        buildList {
            var current: DayGroup? = null
            var bucket = mutableListOf<Transaction>()
            for (tx in filteredItems) {
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

    // Infinite scroll, against the *filtered* count: the list on screen is
    // shorter than the fetched one, so scrolling to its end has to be what
    // triggers the next page, not the size of data the filter is hiding.
    LaunchedEffect(listState, state.hasMore, filteredItems.size) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .distinctUntilChanged()
            .collect { last ->
                if (last != null && last >= filteredItems.size - 5) state.loadMore()
            }
    }

    Scaffold(
        topBar = {
            // As a tab root it wears the shared header (same height and title
            // style as Inicio/Categorías/Mi perfil); pushed from elsewhere it
            // keeps the stock bar with its back arrow.
            // Refresh is pull-to-refresh now, same gesture as everywhere
            // else in the app; a second button doing the same thing was a
            // second control for one action.
            // In a selection run the bar swaps the overflow for a count and
            // the delete action — the same role the title plays on a pushed
            // screen, and one control fewer between the user and the action
            // they came here for. Deselecting every row ends the run.
            val actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit =
                if (selecting) {
                    {
                        IconButton(onClick = { showDeleteSelectedDialog = true }) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = stringResource(Res.string.journal_delete_selected, state.selected.size),
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                } else if (searching) {
                    // Closing the field is the leading (back) icon, same as any
                    // other mode swap here; the trailing slot only ever clears
                    // typed text, so there are two ways out and one of them
                    // keeps the field open for another try.
                    {
                        if (state.query.isNotEmpty()) {
                            IconButton(onClick = { state.updateQuery("") }) {
                                Icon(Icons.Filled.Close, contentDescription = stringResource(Res.string.action_close))
                            }
                        }
                    }
                } else {
                    {
                        IconButton(onClick = { state.startSearch() }) {
                            Icon(Icons.Filled.Search, contentDescription = stringResource(Res.string.action_search))
                        }
                        Box {
                            var menuOpen by remember { mutableStateOf(false) }
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(Res.string.more_options))
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(Res.string.journal_date_range_filter)) },
                                    leadingIcon = { Icon(Icons.Filled.DateRange, contentDescription = null) },
                                    onClick = {
                                        menuOpen = false
                                        showDateRangeFilterDialog = true
                                    },
                                )
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
                }
            // While selecting, the bar wears the stock pushed-screen shape:
            // a back arrow that ends the run and a title that says how many
            // rows are ticked, whichever chrome this screen arrived with.
            val titleText = if (selecting) {
                stringResource(Res.string.journal_selected_count, state.selected.size)
            } else if (bottomBar != null) {
                stringResource(Res.string.nav_movements)
            } else {
                stringResource(Res.string.journal_title)
            }
            val searchHint = stringResource(Res.string.journal_search_hint)
            val searchField: @Composable () -> Unit = {
                // Grabs focus (and opens the keyboard) only for the tap that
                // opened the field — [JournalState.pendingFocus] is one-shot,
                // so returning here from a transaction after the user already
                // dismissed the keyboard doesn't pop it back up.
                LaunchedEffect(state.pendingFocus) {
                    if (state.pendingFocus) {
                        searchFocus.requestFocus()
                        state.focusConsumed()
                    }
                }
                // Dismissing the keyboard (back gesture, swipe-down, the
                // system's own close button) drops focus the same way tapping
                // outside the field would; an empty box at that point isn't a
                // search in progress, so the field closes itself instead of
                // sitting there unfocused with nothing typed. `hadFocus`
                // guards the field's very first, not-yet-focused frame from
                // reading as a dismissal before [searchFocus] even requests it.
                var hadFocus by remember { mutableStateOf(false) }
                androidx.compose.material3.TextField(
                    value = state.query,
                    onValueChange = { state.updateQuery(it) },
                    modifier = Modifier.fillMaxWidth()
                        .focusRequester(searchFocus)
                        .onFocusChanged { focus ->
                            if (focus.isFocused) {
                                hadFocus = true
                            } else if (hadFocus && state.query.isEmpty()) {
                                state.closeSearch()
                            }
                        },
                    placeholder = { Text(searchHint) },
                    singleLine = true,
                    colors = androidx.compose.material3.TextFieldDefaults.colors(
                        focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                        unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                        focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                        unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    ),
                )
            }
            if (bottomBar != null) {
                AppTopBar(
                    title = titleText,
                    onNavigateBack = when {
                        selecting -> ({ state.clearSelection() })
                        searching -> ({ state.closeSearch() })
                        else -> null
                    },
                    titleContent = if (searching) searchField else null,
                    actions = actions,
                )
            } else {
                TopAppBar(
                    title = { if (searching) searchField() else Text(titleText) },
                    navigationIcon = {
                        IconButton(
                            onClick = {
                                when {
                                    selecting -> state.clearSelection()
                                    searching -> state.closeSearch()
                                    else -> onNavigateBack()
                                }
                            },
                        ) {
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
            // second one in the corner would be the same action twice. Hidden
            // mid-selection: registering a movement is the last thing the
            // gesture is reaching for, and the FAB overlaps the bottom rows.
            val showCreate = bottomBar == null && !selecting
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // Only once the top is out of reach of a flick: a few rows
                // down, the button would cover a row to save one swipe.
                val scrolledAway by remember {
                    derivedStateOf { listState.firstVisibleItemIndex > SCROLL_TOP_THRESHOLD }
                }
                AnimatedVisibility(
                    visible = scrolledAway,
                    enter = fadeIn() + scaleIn(),
                    exit = fadeOut() + scaleOut(),
                ) {
                    SmallFloatingActionButton(
                        onClick = { scope.launch { listState.animateScrollToItem(0) } },
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        // Alone in the corner (tab root) it drops into
                        // the Scaffold's 16 dp FAB margin, closer to the
                        // bar; above the create FAB it keeps a gap.
                        modifier = if (showCreate) {
                            Modifier.padding(bottom = 12.dp)
                        } else {
                            Modifier.offset(y = 10.dp)
                        },
                    ) {
                        Icon(
                            Icons.Filled.ArrowUpward,
                            contentDescription = stringResource(Res.string.journal_scroll_top),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                if (showCreate) {
                    FloatingActionButton(
                        onClick = onNavigateToNew,
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = stringResource(Res.string.new_transaction))
                    }
                }
            }
        },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            MovementFilterBar(
                current = state.filter,
                onSelect = { state.pickFilter(it) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            if (state.hasDateRange) {
                val from = state.dateFrom?.let { dateInputOf(it) } ?: ""
                val to = state.dateTo?.let { dateInputOf(it) } ?: ""
                androidx.compose.material3.AssistChip(
                    onClick = { state.clearDateRange() },
                    label = { Text(stringResource(Res.string.journal_date_range_filter_active, from, to)) },
                    trailingIcon = { Icon(Icons.Filled.Close, contentDescription = stringResource(Res.string.journal_date_range_filter_clear)) },
                    modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp),
                )
            }
            PullToRefreshBox(
                isRefreshing = state.pullRefreshing,
                onRefresh = { state.refresh(userInitiated = true) },
                modifier = Modifier.fillMaxSize().weight(1f),
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
                if (filteredItems.isEmpty() && !state.isInitialLoading && state.error == null) {
                    item(key = "empty") {
                        val activeQuery = state.query.trim()
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            if (activeQuery.isNotEmpty()) {
                                // A search with no hits reads differently from
                                // an empty journal: the hint below ("add your
                                // first transaction") would be actively wrong
                                // advice when the account isn't empty at all.
                                Text(
                                    stringResource(Res.string.journal_search_empty, activeQuery),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
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
                        val selected = tx.id in state.selected
                        MovementRow(
                            tx = tx,
                            names = state.names,
                            types = state.types,
                            icons = state.icons,
                            colors = state.colors,
                            hidden = false,
                            selected = selected,
                            // The long-press that starts a run also ticks its
                            // row; afterwards every tap toggles, so a run can
                            // be built without lifting the finger.
                            onLongClick = { state.toggleSelected(tx.id) },
                            onOpen = {
                                if (state.isSelecting) state.toggleSelected(tx.id) else onOpenTransaction(tx.id)
                            },
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
    }

    if (showDeleteRangeDialog) {
        DeleteRangeDialog(state = state, onDismiss = { showDeleteRangeDialog = false })
    }

    if (showDateRangeFilterDialog) {
        DateRangeFilterDialog(state = state, onDismiss = { showDateRangeFilterDialog = false })
    }

    if (showDeleteSelectedDialog) {
        DeleteSelectedDialog(
            state = state,
            scope = scope,
            onDismiss = { showDeleteSelectedDialog = false },
        )
    }
}

/**
 * Confirm-and-delete for the multi-select run: one transaction for the whole
 * batch ([JournalState.deleteSelected]), then the Snackbar carries every
 * removed row back ([JournalState.restore]) — the same undoable-delete shape
 * the editor and the detail screen use, extended to N rows.
 */
@Composable
private fun DeleteSelectedDialog(
    state: JournalState,
    scope: CoroutineScope,
    onDismiss: () -> Unit,
) {
    var deleting by remember { mutableStateOf(false) }
    val count = state.selected.size
    val undoLabel = stringResource(Res.string.action_undo)
    val deletedMessage = stringResource(Res.string.journal_selected_count, count)
    val failedMessage = stringResource(Res.string.journal_delete_failed)

    AlertDialog(
        onDismissRequest = { if (!deleting) onDismiss() },
        title = { Text(stringResource(Res.string.journal_delete_selected_title, count)) },
        text = {
            Text(
                stringResource(Res.string.journal_delete_selected_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    deleting = true
                    scope.launch {
                        try {
                            val backups = state.deleteSelected()
                            onDismiss()
                            if (backups.isNotEmpty()) {
                                Feedback.undoable(deletedMessage, undoLabel) {
                                    state.restore(backups)
                                }
                            }
                        } catch (e: Throwable) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            Feedback.show(failedMessage)
                            deleting = false
                        }
                    }
                },
                enabled = !deleting && count > 0,
            ) { Text(stringResource(Res.string.action_delete)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !deleting) {
                Text(stringResource(Res.string.action_cancel))
            }
        },
    )
}

private fun matchesFilter(
    tx: Transaction,
    filter: JournalFilter,
    types: Map<String, AccountType>,
): Boolean {
    if (filter == JournalFilter.All) return true
    // The same signal the row's own color comes from: +1 money came in, -1
    // went out, 0 an internal move. "Gasto"/"Ingreso"/"Traspaso" is that
    // reading turned into a filter instead of a tint.
    val direction = flowOf(tx, types)?.direction ?: return false
    return when (filter) {
        JournalFilter.Expense -> direction < 0
        JournalFilter.Income -> direction > 0
        JournalFilter.Transfer -> direction == 0
        JournalFilter.All -> true
    }
}

/**
 * Todos / Gasto / Ingreso / Traspaso, the same segmented track the new-
 * movement panel switches kind with (same track color, same lit-segment
 * pill, same direction glyphs) — one control for choosing among states of a
 * thing reads the same whether that thing is being entered or being found.
 */
@Composable
private fun MovementFilterBar(
    current: JournalFilter,
    onSelect: (JournalFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    SegmentedSwitch(
        options = JournalFilter.entries,
        selected = current,
        label = { filterTitle(it) },
        onSelect = onSelect,
        modifier = modifier,
    )
}

// Plural, unlike the create panel's "Gasto": there this names the one
// movement being entered, here it names the set of rows on screen.
@Composable
private fun filterTitle(filter: JournalFilter): String = when (filter) {
    JournalFilter.All -> stringResource(Res.string.journal_filter_all)
    JournalFilter.Expense -> stringResource(Res.string.journal_filter_expense)
    JournalFilter.Income -> stringResource(Res.string.journal_filter_income)
    JournalFilter.Transfer -> stringResource(Res.string.journal_filter_transfer)
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
 * From the overflow menu: narrow the journal (and its search, when both are
 * active at once) to an inclusive from/to range. [JournalState.setDateRange]
 * folds it into the same SQL query as the free-text search, so it composes
 * with whatever the user already typed rather than filtering rows already on
 * screen.
 */
@Composable
private fun DateRangeFilterDialog(state: JournalState, onDismiss: () -> Unit) {
    var fromText by remember { mutableStateOf(state.dateFrom?.let { dateInputOf(it) }) }
    var toText by remember { mutableStateOf(state.dateTo?.let { dateInputOf(it) }) }
    var pickingFrom by remember { mutableStateOf(false) }
    var pickingTo by remember { mutableStateOf(false) }

    val invalidMessage = stringResource(Res.string.journal_date_range_filter_invalid)

    fun apply() {
        val from = fromText?.let { parseDateInput(it) }
        // Inclusive of the whole "to" day, same as the delete-range dialog.
        val toStart = toText?.let { parseDateInput(it) }
        val to = toStart?.plus(DAY_MILLIS - 1)
        if (from == null || to == null || from > to) {
            Feedback.show(invalidMessage)
            return
        }
        state.setDateRange(from, to)
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.journal_date_range_filter_title)) },
        text = {
            Column {
                Text(
                    stringResource(Res.string.journal_date_range_filter_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { pickingFrom = true }) {
                        Text(fromText ?: stringResource(Res.string.journal_date_range_filter_from))
                    }
                    TextButton(onClick = { pickingTo = true }) {
                        Text(toText ?: stringResource(Res.string.journal_date_range_filter_to))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { apply() },
                enabled = fromText != null && toText != null,
            ) { Text(stringResource(Res.string.journal_date_range_filter_apply)) }
        },
        dismissButton = {
            Row {
                if (state.hasDateRange) {
                    TextButton(onClick = {
                        state.clearDateRange()
                        onDismiss()
                    }) { Text(stringResource(Res.string.journal_date_range_filter_clear)) }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) }
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
        val path = node.path.censored().displayPath()
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
        paths = nodes.associate { it.account.id to it.path.censored().displayPath() },
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
    exchangeFlowOf(tx, types)?.let { return it }
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

/**
 * A transaction that states a cost (a trade, dollars bought through MEP) read
 * as money: the asset leg *without* a cost is the money that moved, the leg
 * with the cost is what it bought. Without this, [flowOf]'s "biggest
 * commodity" pick chose the instrument — a fund's units in minor units dwarf
 * any amount — and a rescue of IOLPORA printed "FCI:IOLPORA 34.192.245,14".
 *
 * Neutral on purpose: buying or selling moves value between the user's own
 * accounts, it doesn't make them richer or poorer (the gain, if any, is a
 * separate posting the detail screen shows).
 */
private fun exchangeFlowOf(tx: Transaction, types: Map<String, AccountType>): TxnFlow? {
    val priced = tx.postings.filter { it.costMinor != null }
    if (priced.isEmpty()) return null
    fun own(p: Posting) = types[p.accountId] == AccountType.Asset || types[p.accountId] == AccountType.Liability
    val money = tx.postings.filter { it.costMinor == null && own(it) }
        .maxByOrNull { abs(it.amountMinor) }
        ?: return null
    val bought = priced.maxByOrNull { abs(it.costMinor ?: 0L) }!!
    val out = money.amountMinor < 0
    return TxnFlow(
        fromId = if (out) money.accountId else bought.accountId,
        toId = if (out) bought.accountId else money.accountId,
        amountMinor = abs(money.amountMinor),
        commodity = money.commodity,
        direction = 0,
    )
}

/**
 * The color of an amount that stands for a whole movement: income, expense,
 * or a transfer between the user's own accounts. One definition for every
 * screen — list rows, detail hero, import review — so the same debit can't
 * come out in a different red one screen over. The pair itself lives in the
 * theme ([MoneyColors]), not in Material's `tertiary`/`error`, which other
 * components pull for unrelated reasons and which differ per theme.
 */
@Composable
internal fun flowColor(direction: Int): Color = when {
    direction > 0 -> MoneyColor.positive
    direction < 0 -> MoneyColor.negative
    else -> MoneyColor.neutral
}

// A small "›" chevron reads as "leads to" without the vertical-alignment
// headaches of the wider "→" arrow glyph, which sits at different heights
// across the platform default fonts (Roboto on Android, the desktop font,
// San Francisco on iOS). The chevron is designed to sit on the x-height like
// regular punctuation, so it centers the same everywhere with no hacks.
internal const val ROUTE_ARROW = "›"

/**
 * Account path for display: "Comida:Kiosko" -> "Comida › Kiosko". Storage,
 * search and matching keep the colon (it's the stable, server-facing form);
 * only the rendered text gets the chevron, so this belongs where a path is
 * about to hit a [Text], never upstream of it.
 */
fun String.displayPath(): String = replace(":", " $ROUTE_ARROW ")

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
                            color = flowColor(flow.direction),
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
