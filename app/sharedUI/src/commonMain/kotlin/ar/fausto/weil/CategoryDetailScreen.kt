@file:OptIn(ExperimentalMaterial3Api::class)

package ar.fausto.weil

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import weil.app.sharedui.generated.resources.Res
import weil.app.sharedui.generated.resources.action_back
import weil.app.sharedui.generated.resources.category_edit_title
import weil.app.sharedui.generated.resources.detail_no_postings
import weil.app.sharedui.generated.resources.home_recent_title

/**
 * One category: its subcategories as a grid of chips at the top, its
 * movements underneath. Tapping a chip narrows the list to that child, which
 * is the whole reason the screen exists — the list below a parent is the sum
 * of its children, and "which of them was it?" is the question the tree view
 * answers with indentation and this one answers with a tap.
 *
 * Not [AccountDetailScreen]: that one is a *register* (running balance per
 * commodity, postings, subtree toggle), the shape an asset account needs.
 * A category is read as a list of purchases, so it gets the same
 * [MovementRow] as Home and Movimientos.
 */
@Composable
fun CategoryDetailScreen(
    ledgerState: LedgerState,
    categoryId: String,
    onNavigateBack: () -> Unit,
    onOpenTransaction: (id: String) -> Unit,
) {
    val ledger = ledgerState.ledger
    val accounts = ledgerState.accounts
    // Reachable as a deep link (and as the harness' start route), so it can't
    // assume Home already filled the tree — without it there is no name, no
    // chips and no subtree to query.
    if (!ledgerState.loaded) {
        LaunchedEffect(Unit) { ledgerState.refresh() }
    }
    val node = findNode(ledgerState.tree, categoryId)
    val children = node?.children.orEmpty()
    // null = the category itself, chips are an exclusive narrowing of it.
    var selectedChild by remember(categoryId) { mutableStateOf<String?>(null) }
    var items by remember(categoryId) { mutableStateOf(emptyList<Transaction>()) }
    var loaded by remember(categoryId) { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    var hasMore by remember { mutableStateOf(true) }
    var editing by remember { mutableStateOf(false) }
    // The subcategory a long press is editing, separate from [editing]
    // (which is always this category, the one in the top bar): the two
    // dialogs are opened from different rows and must not fight over one
    // flag, or a long press on a child would reopen the parent instead.
    var editingChild by remember { mutableStateOf<Account?>(null) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    val nodes = remember(ledgerState.tree) { ledgerState.tree.flatMap { it.selfAndDescendants } }
    val names = remember(nodes) { nodes.associate { it.account.id to it.account.name.censored() } }
    val types = remember(nodes) { nodes.associate { it.account.id to it.account.type } }
    val icons = remember(nodes) { nodes.associate { it.account.id to it.account.icon } }
    val colors = remember(nodes) { nodes.associate { it.account.id to it.account.color } }

    // A chip means "only this child"; no chip means the whole subtree, which
    // is what the parent's own total already claims.
    fun scopeIds(): List<String> =
        selectedChild?.let { accounts.subtreeIds(ledgerState.tree, it) }
            ?: accounts.subtreeIds(ledgerState.tree, categoryId)

    suspend fun load() {
        val page = ledger.page(accountIds = scopeIds())
        items = page
        hasMore = page.size >= LIST_PAGE_SIZE
        loaded = true
    }

    LaunchedEffect(selectedChild, ledgerState.tree) { load() }
    LaunchedEffect(categoryId) { ledger.changes.collect { load() } }

    LaunchedEffect(listState, hasMore, items.size) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .distinctUntilChanged()
            .collect { last ->
                if (last == null || last < items.size - 5) return@collect
                if (loadingMore || !hasMore || !loaded) return@collect
                val cursor = items.lastOrNull() ?: return@collect
                loadingMore = true
                scope.launch {
                    try {
                        val next = ledger.page(
                            before = LedgerCursor(cursor.date, cursor.id),
                            accountIds = scopeIds(),
                        )
                        hasMore = next.size >= LIST_PAGE_SIZE
                        // load() can have replaced the list while this page
                        // was in flight; appending blind duplicates keys.
                        val seen = items.mapTo(HashSet()) { it.id }
                        items = items + next.filter { seen.add(it.id) }
                    } finally {
                        loadingMore = false
                    }
                }
            }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(node?.account?.name?.censored().orEmpty()) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.Filled.ArrowBack,
                            contentDescription = stringResource(Res.string.action_back),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { editing = true }) {
                        Icon(
                            Icons.Filled.Edit,
                            contentDescription = stringResource(Res.string.category_edit_title),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
        ) {
            // Subcategories are rows, not chips — same slab as everything
            // else — but bare ones: no disc, no color. Inside one category
            // they are all the same kind of thing, so a glyph per row would
            // repeat the parent's icon six times and a color per row would
            // compete with the *selected* row, which is the only state this
            // list has to show.
            // Two per row: these are filters, not destinations, and at full
            // width six of them push the movements — the thing being
            // filtered — off the first screen.
            items(children.chunked(2), key = { it.first().account.id }) { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    pair.forEach { child ->
                        val selected = selectedChild == child.account.id
                        val childTotal = ledgerState.totals[child.account.id].orEmpty()
                        AppListRow(
                            icon = null,
                            paint = accountPaint(null),
                            title = child.account.name.censored(),
                            // A zero rather than no line at all: an empty
                            // subcategory is a real answer ("nothing was
                            // spent here"), and omitting it made that card
                            // shorter than the one beside it, which read as a
                            // rendering glitch instead of as information.
                            subtitle = if (childTotal.isEmpty()) {
                                formatMoney(
                                    0L,
                                    child.account.commodity ?: Money.DEFAULT_COMMODITY,
                                )
                            } else {
                                formatTotals(childTotal)
                            },
                            // Selection is the mint of the balance card, the
                            // app's one "this is active" color (it is what
                            // the bottom bar and the hero are made of).
                            container = if (selected) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.10f)
                            },
                            // Tapping the selected one clears it: the way
                            // back to "all of Comida" shouldn't be the back
                            // button, which leaves the screen.
                            onClick = {
                                selectedChild = if (selected) null else child.account.id
                            },
                            onLongClick = { editingChild = child.account },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    // Odd count: the last one keeps its half width instead of
                    // stretching across and reading as a section header.
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            item(key = "movements-header") {
                Text(
                    stringResource(Res.string.home_recent_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.inverseSurface,
                    modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
                )
            }
            if (items.isEmpty() && loaded) {
                item(key = "empty") {
                    Text(
                        stringResource(Res.string.detail_no_postings),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                    )
                }
            }
            items(items, key = { it.id }) { tx ->
                MovementRow(
                    tx = tx,
                    names = names,
                    types = types,
                    icons = icons,
                    colors = colors,
                    hidden = false,
                    onOpen = { onOpenTransaction(tx.id) },
                    // Every row here belongs to this category by definition.
                    plain = true,
                )
            }
            if (loadingMore) {
                item(key = "loading") { TransactionCardSkeleton() }
            }
        }
    }

    if (editing) {
        node?.let {
            CategoryDialog(
                state = ledgerState,
                account = it.account,
                onDismiss = { editing = false },
            )
        }
    }
    editingChild?.let { child ->
        CategoryDialog(
            state = ledgerState,
            account = child,
            onDismiss = { editingChild = null },
        )
    }
}

