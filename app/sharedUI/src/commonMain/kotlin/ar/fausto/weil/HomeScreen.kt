@file:OptIn(ExperimentalMaterial3Api::class)

package ar.fausto.weil

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import org.jetbrains.compose.resources.stringResource
import weil.app.sharedui.generated.resources.Res
import weil.app.sharedui.generated.resources.account_add_title
import weil.app.sharedui.generated.resources.app_title
import weil.app.sharedui.generated.resources.home_accounts_title
import weil.app.sharedui.generated.resources.home_add_first_account
import weil.app.sharedui.generated.resources.home_net_worth
import weil.app.sharedui.generated.resources.home_new_expense
import weil.app.sharedui.generated.resources.home_no_accounts_yet
import weil.app.sharedui.generated.resources.home_no_recent
import weil.app.sharedui.generated.resources.home_recent_title
import weil.app.sharedui.generated.resources.home_see_all
import weil.app.sharedui.generated.resources.more_options
import weil.app.sharedui.generated.resources.open_account_tree
import weil.app.sharedui.generated.resources.open_emails
import weil.app.sharedui.generated.resources.open_journal
import weil.app.sharedui.generated.resources.open_notifications
import weil.app.sharedui.generated.resources.open_profile

private const val RECENT_COUNT = 5

/**
 * Home: net worth, the user's asset accounts ("Cuentas") and the latest
 * movements. The full tree lives in [AccountsTreeScreen] and the secondary
 * destinations sit in the overflow menu. The FAB opens the quick entry on
 * Gasto (the most frequent kind); the kind can be switched there.
 */
@Composable
fun HomeScreen(
    ledgerState: LedgerState,
    onNavigateToProfile: () -> Unit,
    onNavigateToNotifications: () -> Unit,
    onNavigateToEmails: () -> Unit,
    onNavigateToJournal: () -> Unit,
    onNavigateToTree: () -> Unit,
    onNewTransaction: (TxnKind) -> Unit,
    onNavigateToAccount: (id: String) -> Unit,
    onNavigateToEdit: (id: String) -> Unit,
) {
    if (!ledgerState.loaded) {
        LaunchedEffect(Unit) { ledgerState.refresh() }
    }
    var adding by remember { mutableStateOf(false) }
    var recent by remember { mutableStateOf<List<Transaction>?>(null) }

    // Every refresh (pull, sync, or a mutation via `changes`) ends with busy
    // going false; reload the recent slice then.
    LaunchedEffect(ledgerState.busy) {
        if (ledgerState.busy) return@LaunchedEffect
        try {
            recent = ledgerState.ledger.page(limit = RECENT_COUNT)
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.app_title)) },
                actions = {
                    IconButton(onClick = onNavigateToJournal) {
                        Icon(Icons.Filled.MenuBook, contentDescription = stringResource(Res.string.open_journal))
                    }
                    IconButton(onClick = onNavigateToProfile) {
                        Icon(Icons.Filled.AccountCircle, contentDescription = stringResource(Res.string.open_profile))
                    }
                    Box {
                        var menuOpen by remember { mutableStateOf(false) }
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(Res.string.more_options))
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            OverflowItem(Icons.Filled.AccountTree, stringResource(Res.string.open_account_tree)) {
                                menuOpen = false
                                onNavigateToTree()
                            }
                            OverflowItem(Icons.Filled.Notifications, stringResource(Res.string.open_notifications)) {
                                menuOpen = false
                                onNavigateToNotifications()
                            }
                            OverflowItem(Icons.Filled.Email, stringResource(Res.string.open_emails)) {
                                menuOpen = false
                                onNavigateToEmails()
                            }
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { onNewTransaction(TxnKind.Expense) },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(stringResource(Res.string.home_new_expense)) },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        },
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = ledgerState.busy,
            onRefresh = { ledgerState.refresh() },
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            val assets = ledgerState.tree.filter { it.account.type == AccountType.Asset }
            val nodes = remember(ledgerState.tree) { ledgerState.tree.flatMap { it.selfAndDescendants } }
            val paths = remember(nodes) { nodes.associate { it.account.id to it.path } }
            val types = remember(nodes) { nodes.associate { it.account.id to it.account.type } }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                // Bottom room so the FAB never covers the last row.
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
            ) {
                item(key = "net-worth") { NetWorthCard(ledgerState) }

                item(key = "accounts-header") {
                    SectionHeader(
                        title = stringResource(Res.string.home_accounts_title),
                        trailing = {
                            FilledTonalIconButton(
                                onClick = { adding = true },
                                modifier = Modifier.size(32.dp),
                                colors = IconButtonDefaults.filledTonalIconButtonColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                    contentColor = MaterialTheme.colorScheme.onSurface,
                                ),
                            ) {
                                Icon(
                                    Icons.Filled.Add,
                                    contentDescription = stringResource(Res.string.account_add_title),
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        },
                    )
                }
                if (assets.isEmpty() && ledgerState.loaded) {
                    item(key = "accounts-empty") {
                        EmptyHint(
                            title = stringResource(Res.string.home_no_accounts_yet),
                            action = stringResource(Res.string.home_add_first_account),
                            onAction = { adding = true },
                        )
                    }
                } else {
                    val rows = buildList { addSection(assets, ledgerState.expandedIds, 0) }
                        .filterIsInstance<NodeRow>()
                    // One decision for the whole card: no sub-accounts anywhere
                    // means no fold column, so names start at the card edge.
                    val foldable = rows.any { it.node.children.isNotEmpty() }
                    itemsIndexed(rows, key = { _, row -> row.key }) { index, row ->
                        NodeRowView(
                            state = ledgerState,
                            row = row,
                            onToggle = { ledgerState.toggleExpanded(it) },
                            onOpen = onNavigateToAccount,
                            skin = rowSkin(first = index == 0, last = index == rows.lastIndex),
                            toggleSlot = foldable,
                        )
                    }
                }

                item(key = "recent-header") {
                    SectionHeader(
                        title = stringResource(Res.string.home_recent_title),
                        trailing = {
                            TextButton(onClick = onNavigateToJournal) {
                                Text(stringResource(Res.string.home_see_all))
                            }
                        },
                    )
                }
                val recentItems = recent
                if (recentItems != null && recentItems.isEmpty()) {
                    item(key = "recent-empty") {
                        EmptyHint(title = stringResource(Res.string.home_no_recent))
                    }
                }
                var lastGroup: DayGroup? = null
                for (tx in recentItems.orEmpty()) {
                    val group = dayGroup(tx.date)
                    if (group != lastGroup) {
                        lastGroup = group
                        item(key = "recent-day-${group.key}") { DayHeader(group) }
                    }
                    item(key = "recent-${tx.id}") {
                        TransactionCard(tx = tx, paths = paths, types = types, onOpen = { onNavigateToEdit(tx.id) })
                    }
                }
            }
        }
    }

    if (adding) {
        AddAccountDialog(
            state = ledgerState,
            onDismiss = { adding = false },
            fixedType = AccountType.Asset,
        )
    }
}

@Composable
private fun OverflowItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = { Icon(icon, contentDescription = null) },
        onClick = onClick,
    )
}

/**
 * Net worth hero: the dominant commodity reads as the headline figure, the
 * rest ride along as pills so a multi-currency ledger never pushes the card
 * taller than a glance.
 */
@Composable
private fun NetWorthCard(state: LedgerState) {
    val lines = netWorthOf(state).entries.sortedByDescending { abs(it.value) }
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(Res.string.home_net_worth),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (state.busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            // Every commodity at the same size — ARS used to run big while USD
            // rode along as a small pill underneath, which read as "USD is a
            // footnote." A second currency now costs a line, not a demotion.
            if (lines.isEmpty()) {
                Text(
                    if (state.loaded) "${Money.DEFAULT_COMMODITY} ${formatMinorUnits(0)}" else "—",
                    style = MaterialTheme.typography.displaySmall,
                )
            }
            lines.forEach { (commodity, minor) ->
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        commodity,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 5.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        formatMinorUnits(minor),
                        style = MaterialTheme.typography.displaySmall,
                        color = if (minor < 0) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            state.error?.let { ErrorBanner(it, modifier = Modifier.padding(top = 14.dp)) }
        }
    }
}

private fun netWorthOf(state: LedgerState): Map<String, Long> {
    val acc = mutableMapOf<String, Long>()
    for (root in state.tree) {
        if (root.account.type != AccountType.Asset && root.account.type != AccountType.Liability) {
            continue
        }
        for ((c, v) in state.totals[root.account.id].orEmpty()) {
            acc[c] = (acc[c] ?: 0L) + v
        }
    }
    return acc
}

/**
 * Section title with optional trailing content (totals, actions). The grouped
 * cards below carry the separation, so no divider is drawn here.
 */
@Composable
internal fun SectionHeader(
    title: String,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 24.dp, bottom = 8.dp, start = 4.dp)
            .heightIn(min = 32.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        trailing()
    }
}

/** Centered empty state on a tonal card: a muted title and a single call to action. */
@Composable
internal fun EmptyHint(
    title: String,
    action: String? = null,
    onAction: () -> Unit = {},
) {
    Surface(
        shape = RoundedCornerShape(GroupRadius),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (action != null) {
                Spacer(Modifier.height(12.dp))
                FilledTonalButton(onClick = onAction) { Text(action) }
            }
        }
    }
}
