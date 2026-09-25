package ar.fausto.weil

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlin.random.Random
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.saveable.rememberSaveable
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
import weil.app.sharedui.generated.resources.positions_only
import weil.app.sharedui.generated.resources.positions_show_all

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
    onOpenTransaction: (id: String) -> Unit,
    onNavigateToNew: () -> Unit,
    initialCommodity: String? = null,
) {
    val ledger = ledgerState.ledger
    val accounts = ledgerState.accounts
    val node = findNode(ledgerState.tree, accountId)
    // Leaf names + types, same derivation Home uses, so this register's rows
    // draw with the identical MovementRow the journal and Home show.
    val nodes = remember(ledgerState.tree) { ledgerState.tree.flatMap { it.selfAndDescendants } }
    val names = remember(nodes) { nodes.associate { it.account.id to it.account.name.censored() } }
    val types = remember(nodes) { nodes.associate { it.account.id to it.account.type } }
    val looks = ledgerState.looks
    // Opening a transaction takes this entry out of composition, and plain
    // `remember` state goes with it: the register came back empty, and the
    // (saveable) scroll position was clamped to the top before the reload
    // landed. The rows are parked in [RegisterCache] under a token that is
    // itself saveable, so it lives exactly as long as this back-stack entry —
    // a fresh visit to the same account gets a new token and starts at the top.
    val cacheKey = rememberSaveable { Random.nextLong().toString() }
    val cached = remember(cacheKey) { RegisterCache[cacheKey] }
    var includeSubtree by rememberSaveable { mutableStateOf(false) }
    // The positions view narrows the register to one instrument.
    var commodityFilter by rememberSaveable { mutableStateOf(initialCommodity) }
    var holdings by remember { mutableStateOf(cached?.holdings ?: emptyMap()) }
    val positions = remember(holdings, ledgerState.valuation) {
        ledgerState.valuation.positions(holdings)
    }
    var entries by remember { mutableStateOf(cached?.entries ?: emptyList()) }
    var transactions by remember { mutableStateOf(cached?.transactions ?: emptyMap()) }
    var loaded by remember { mutableStateOf(cached != null) }
    var loadingMore by remember { mutableStateOf(false) }
    var hasMore by remember { mutableStateOf(cached?.hasMore ?: true) }
    DisposableEffect(cacheKey) {
        onDispose {
            if (loaded) RegisterCache[cacheKey] = RegisterSnapshot(entries, transactions, hasMore, holdings)
        }
    }
    var error by remember { mutableStateOf<String?>(null) }
    var actions by remember { mutableStateOf(false) }
    val selection = remember { TransactionSelection() }
    var confirmDelete by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    SelectionBackHandler(selection)
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    fun ids(): List<String> =
        if (includeSubtree) accounts.subtreeIds(ledgerState.tree, accountId) else listOf(accountId)

    suspend fun loadAll() {
        error = null
        try {
            // As deep as the user has already scrolled: a plain first page
            // would cut the list short and clamp the position upwards.
            val limit = maxOf(LIST_PAGE_SIZE, entries.size)
            val page = ledger.register(subtreeIds = ids(), limit = limit, commodity = commodityFilter)
            // Positions are one account's: a subtree mixes brokers' carteras
            // and the booked cost is per account.
            holdings = if (includeSubtree) emptyMap() else ledger.holdings(accountId)
            entries = page
            transactions = ledger.getAll(page.map { it.posting.transactionId }.distinct())
            hasMore = page.size >= limit
            selection.retain(page.mapTo(HashSet()) { it.posting.transactionId })
            loaded = true
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            error = e.message ?: e.toString()
        }
    }

    // Toggling the subtree is a different list; start it from one page.
    // Same for narrowing to one instrument.
    var shownSubtree by remember { mutableStateOf(includeSubtree) }
    var shownFilter by remember { mutableStateOf(commodityFilter) }
    LaunchedEffect(includeSubtree, commodityFilter) {
        if (shownSubtree != includeSubtree || shownFilter != commodityFilter) {
            entries = emptyList()
            shownSubtree = includeSubtree
            shownFilter = commodityFilter
            selection.clear()
        }
        loadAll()
    }

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
                    commodity = commodityFilter,
                )
                hasMore = nextPage.size >= LIST_PAGE_SIZE
                // A concurrent loadAll() (ledger.changes) can have replaced
                // the list while this page was in flight; appending blind
                // would duplicate rows and crash the LazyColumn's keys.
                val seen = entries.mapTo(HashSet()) { it.posting.id }
                val added = nextPage.filter { seen.add(it.posting.id) }
                entries = entries + added
                transactions = transactions + ledger.getAll(added.map { it.posting.transactionId }.distinct())
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
            val selecting = selection.isSelecting
            TopAppBar(
                title = {
                    Text(
                        if (selecting) {
                            selectionTitle(selection)
                        } else {
                            node?.account?.name?.censored() ?: stringResource(Res.string.detail_account_fallback)
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { if (selecting) selection.clear() else onNavigateBack() }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(Res.string.action_back))
                    }
                },
                actions = {
                    if (selecting) {
                        RenameSelectionAction(selection.size) { renaming = true }
                        DeleteSelectionAction(selection.size) { confirmDelete = true }
                    } else if (node != null) {
                        IconButton(onClick = { actions = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(Res.string.more_options))
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            if (!selection.isSelecting) FloatingActionButton(
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
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp, bottom = 16.dp),
                ) {
                    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
                        node?.let { n ->
                            if (n.path != n.account.name) {
                                Text(
                                    n.path.censored().displayPath(),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(Modifier.height(6.dp))
                            }
                        }
                        // BalanceText right-aligns for its usual row use; here
                        // it's the sole element so pull it back to the card's
                        // leading edge to match the net-worth hero card.
                        // The account's own total, not a movement in a list:
                        // a minus sign says "negative" as plainly as red does,
                        // without recruiting a color that elsewhere means
                        // "money left my pocket". The biggest currency is the
                        // headline and the rest sit under it, smaller — the
                        // Home hero's rule: at display size, a second currency
                        // (a broker holding pesos and dollars) doubled the
                        // header's height.
                        val lines = ledgerState.displayTotals[accountId].orEmpty()
                            .entries.sortedByDescending { kotlin.math.abs(it.value) }
                        Box(modifier = Modifier.fillMaxWidth()) {
                            BalanceText(
                                totals = lines.firstOrNull()?.let { mapOf(it.key to it.value) }.orEmpty(),
                                style = MaterialTheme.typography.displaySmall,
                                signalNegative = false,
                                modifier = Modifier.align(Alignment.CenterStart),
                            )
                        }
                        lines.drop(1).forEach { (commodity, minor) ->
                            Text(
                                formatMoney(minor, commodity, signed = true),
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                        if (node?.children?.isNotEmpty() == true) {
                            FilterChip(
                                selected = includeSubtree,
                                onClick = { includeSubtree = !includeSubtree },
                                label = { Text(stringResource(Res.string.detail_include_subaccounts)) },
                                modifier = Modifier.padding(top = 12.dp),
                            )
                        }
                        (error ?: ledgerState.error)?.let {
                            ErrorBanner(it, modifier = Modifier.padding(top = 12.dp))
                        }
                    }
                }
            }
            if (!includeSubtree && positions.isNotEmpty()) {
                item(key = "positions") {
                    PositionsCard(
                        lines = positions,
                        selected = commodityFilter,
                        onSelect = { commodityFilter = it },
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
            }
            commodityFilter?.let { filter ->
                item(key = "filter") {
                    val symbol = ledgerState.valuation.commodities[filter]?.symbol ?: filter
                    FilterChip(
                        selected = true,
                        onClick = { commodityFilter = null },
                        label = { Text(stringResource(Res.string.positions_only, symbol)) },
                        trailingIcon = {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = stringResource(Res.string.positions_show_all),
                                modifier = Modifier.size(16.dp),
                            )
                        },
                        modifier = Modifier.padding(top = 8.dp),
                    )
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
                    val tx = transactions[entry.posting.transactionId] ?: Transaction(
                        id = entry.posting.transactionId,
                        date = entry.date,
                        payee = entry.payee,
                        note = null,
                        createdAt = entry.date,
                        postings = listOf(entry.posting),
                        timeKnown = entry.timeKnown,
                    )
                    MovementRow(
                        tx = tx,
                        names = names,
                        types = types,
                        looks = looks,
                        hidden = false,
                        selected = entry.posting.transactionId in selection,
                        onLongClick = { selection.toggle(entry.posting.transactionId) },
                        onOpen = {
                            val id = entry.posting.transactionId
                            if (selection.isSelecting) selection.toggle(id) else onOpenTransaction(id)
                        },
                        // The running balance after this row, in the same dim
                        // bodySmall caption slot other lists leave empty —
                        // here it's the number this screen exists to show.
                        // A holdings account's balance is a quantity
                        // ("13 MELI"), not money with two decimals.
                        caption = formatAmount(
                            entry.balanceAfter.minorUnits,
                            entry.balanceAfter.commodity,
                            ledgerState.valuation,
                            signed = true,
                        ),
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

    if (renaming) {
        RenameSelectedDialog(
            count = selection.size,
            current = { ledger.payees(selection.selected) },
            rename = { payee ->
                ledger.renamePayees(selection.selected, payee).also { selection.clear() }
            },
            restore = { ledger.restorePayees(it) },
            onDismiss = { renaming = false },
        )
    }

    if (confirmDelete) {
        DeleteSelectedDialog(
            count = selection.size,
            delete = {
                ledger.deleteWithBackup(selection.selected).also { selection.clear() }
            },
            restore = { ledger.restoreBackups(it) },
            onDismiss = { confirmDelete = false },
        )
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

private class RegisterSnapshot(
    val entries: List<RegisterEntry>,
    val transactions: Map<String, Transaction>,
    val hasMore: Boolean,
    val holdings: Map<String, HeldPosition>,
)

/**
 * Registers of account screens that left composition while still on the back
 * stack, keyed by their entry's saveable token. Bounded, since a popped entry
 * never comes back for its row: the oldest are dropped past a handful.
 */
private object RegisterCache {
    private const val MAX = 8
    private val map = LinkedHashMap<String, RegisterSnapshot>()
    operator fun get(key: String): RegisterSnapshot? = map[key]
    operator fun set(key: String, value: RegisterSnapshot) {
        map.remove(key)
        map[key] = value
        while (map.size > MAX) map.remove(map.keys.first())
    }
}
