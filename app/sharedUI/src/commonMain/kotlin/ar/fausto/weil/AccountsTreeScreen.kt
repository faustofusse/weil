@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package ar.fausto.weil

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import weil.app.sharedui.generated.resources.Res
import weil.app.sharedui.generated.resources.account_actions
import weil.app.sharedui.generated.resources.account_add_title
import weil.app.sharedui.generated.resources.account_choose_parent
import weil.app.sharedui.generated.resources.account_collapse
import weil.app.sharedui.generated.resources.account_commodity_any
import weil.app.sharedui.generated.resources.account_commodity_hint
import weil.app.sharedui.generated.resources.account_commodity_label
import weil.app.sharedui.generated.resources.account_default_badge
import weil.app.sharedui.generated.resources.account_default_hint
import weil.app.sharedui.generated.resources.account_delete_message
import weil.app.sharedui.generated.resources.account_expand
import weil.app.sharedui.generated.resources.account_in_net_worth_toggle
import weil.app.sharedui.generated.resources.account_includes_subaccounts
import weil.app.sharedui.generated.resources.account_move_to_root
import weil.app.sharedui.generated.resources.account_move_under
import weil.app.sharedui.generated.resources.account_move_under_title
import weil.app.sharedui.generated.resources.account_name_label
import weil.app.sharedui.generated.resources.account_parent_none
import weil.app.sharedui.generated.resources.account_parent_under
import weil.app.sharedui.generated.resources.account_rename
import weil.app.sharedui.generated.resources.account_set_default
import weil.app.sharedui.generated.resources.account_subaccounts_many
import weil.app.sharedui.generated.resources.account_subaccounts_one
import weil.app.sharedui.generated.resources.account_type_asset
import weil.app.sharedui.generated.resources.account_type_equity
import weil.app.sharedui.generated.resources.account_type_expense
import weil.app.sharedui.generated.resources.account_type_income
import weil.app.sharedui.generated.resources.account_type_label
import weil.app.sharedui.generated.resources.account_type_liability
import weil.app.sharedui.generated.resources.account_unset_default
import weil.app.sharedui.generated.resources.category_icon_label
import weil.app.sharedui.generated.resources.action_back
import weil.app.sharedui.generated.resources.action_cancel
import weil.app.sharedui.generated.resources.action_delete
import weil.app.sharedui.generated.resources.action_save
import weil.app.sharedui.generated.resources.action_undo
import weil.app.sharedui.generated.resources.home_add_first_account
import weil.app.sharedui.generated.resources.home_no_accounts_yet
import weil.app.sharedui.generated.resources.tree_title

/** Localized label for an [AccountType] (list headers, type dropdown). */
@Composable
internal fun accountTypeLabel(type: AccountType): String = stringResource(
    when (type) {
        AccountType.Asset -> Res.string.account_type_asset
        AccountType.Liability -> Res.string.account_type_liability
        AccountType.Income -> Res.string.account_type_income
        AccountType.Expense -> Res.string.account_type_expense
        AccountType.Equity -> Res.string.account_type_equity
    },
)

/**
 * The full account tree of every type, reachable from the Home top bar.
 * Home keeps only asset accounts; this screen is the complete ledger plan.
 */
@Composable
fun AccountsTreeScreen(
    ledgerState: LedgerState,
    onNavigateBack: () -> Unit,
    onNavigateToAccount: (id: String) -> Unit,
    onNavigateToAdd: () -> Unit,
) {
    Scaffold(
        floatingActionButton = {
            FloatingActionButton(
                onClick = onNavigateToAdd,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(Res.string.account_add_title))
            }
        },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.tree_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(Res.string.action_back))
                    }
                },
            )
        },
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = ledgerState.pullRefreshing,
            onRefresh = { ledgerState.refresh(userInitiated = true) },
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
            ) {
                AccountsTreeSection(
                    state = ledgerState,
                    onNavigateToAccount = onNavigateToAccount,
                    onAdd = onNavigateToAdd,
                )
            }
        }
    }
}

@Composable
private fun AccountsTreeSection(
    state: LedgerState,
    onNavigateToAccount: (id: String) -> Unit,
    onAdd: () -> Unit,
) {
    if (!state.loaded) {
        LaunchedEffect(Unit) { state.refresh() }
    }

    if (state.tree.isEmpty() && state.loaded && state.error == null) {
        EmptyHint(
            title = stringResource(Res.string.home_no_accounts_yet),
            action = stringResource(Res.string.home_add_first_account),
            onAction = onAdd,
        )
    } else {
        val rows = buildList {
            for (type in AccountType.entries) {
                val roots = state.tree.filter { it.account.type == type }
                if (roots.isEmpty()) continue
                add(TypeHeader(type))
                addFlat(roots, 0)
            }
        }
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(bottom = 88.dp),
        ) {
            itemsIndexed(rows, key = { _, row -> row.key }) { index, row ->
                when (row) {
                    is TypeHeader -> SectionHeader(
                        title = accountTypeLabel(row.type),
                        trailing = {
                            // Unweighted: the title's own weight already
                            // yields the space this total needs, which is
                            // what keeps it flush against the row's trailing
                            // edge.
                            TypeTotals(typeSum(state, row.type))
                        },
                    )
                    is NodeRow -> NodeRowView(
                        state = state,
                        row = row,
                        onToggle = { state.toggleExpanded(it) },
                        onOpen = { id -> onNavigateToAccount(id) },
                        // The tree is always fully expanded: indentation alone
                        // carries the hierarchy, no fold column needed.
                        toggleSlot = false,
                        // Each type's run of rows reads as one card: only its
                        // outer edges get rounded.
                        skin = rowSkin(
                            first = rows.getOrNull(index - 1) !is NodeRow,
                            last = rows.getOrNull(index + 1) !is NodeRow,
                        ),
                        flat = true,
                    )
                }
            }
        }
    }
}

internal fun typeSum(state: LedgerState, type: AccountType): Map<String, Long> {
    val acc = mutableMapOf<String, Long>()
    state.tree.filter { it.account.type == type }.forEach { root ->
        for ((c, v) in state.totals[root.account.id].orEmpty()) {
            acc[c] = (acc[c] ?: 0L) + v
        }
    }
    return acc
}

internal fun MutableList<TreeRow>.addSection(
    roots: List<AccountNode>,
    expandedIds: Set<String>,
    depth: Int,
) {
    for (node in roots) {
        add(NodeRow(node, depth))
        if (node.account.id in expandedIds) {
            addSection(node.children, expandedIds, depth + 1)
        }
    }
}

/** Fully expanded rows for the tree screen: every child always visible. */
internal fun MutableList<TreeRow>.addFlat(
    roots: List<AccountNode>,
    depth: Int,
) {
    for (node in roots) {
        add(NodeRow(node, depth))
        addFlat(node.children, depth + 1)
    }
}

internal sealed interface TreeRow {
    val key: String
}

internal data class TypeHeader(val type: AccountType) : TreeRow {
    override val key: String = "type-${type.db}"
}

internal data class NodeRow(val node: AccountNode, val depth: Int) : TreeRow {
    override val key: String get() = "node-${node.account.id}"
}

@Composable
internal fun NodeRowView(
    state: LedgerState,
    row: NodeRow,
    onToggle: (id: String) -> Unit,
    onOpen: (id: String) -> Unit,
    skin: RowSkin? = null,
    flat: Boolean = false,
    /**
     * Reserve the fold/unfold column. The caller turns it on for the whole
     * card when at least one row is foldable, so names stay aligned; a flat
     * list of accounts keeps its left edge clean instead of growing a column
     * of meaningless bullets.
     */
    toggleSlot: Boolean = false,
    /**
     * Show the trailing ⋮ button. It only ever duplicates the row's own
     * long-press, so Home (a handful of accounts, read at a glance) turns it
     * off: the column of dots was the busiest thing in the card and its
     * 32.dp pushed the balances out of line with the movement amounts
     * underneath. The full tree keeps it, where the gesture is less obvious.
     */
    actions: Boolean = true,
) {
    val node = row.node
    var actionsOpen by remember { mutableStateOf(false) }
    val expanded = node.account.id in state.expandedIds
    val childCount = node.children.size
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 0f else -90f,
        animationSpec = tween(180),
        label = "chevron",
    )
    // Deep trees don't run off-screen: depth padding is capped and any deeper
    // level keeps the max indent.
    val indent = (row.depth.coerceAtMost(5) * 16).dp
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (skin == null) {
                    Modifier
                } else {
                    Modifier.clip(skin.shape).background(skin.container)
                },
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
                .combinedClickable(
                    onClick = { onOpen(node.account.id) },
                    onLongClick = { actionsOpen = true },
                )
                .heightIn(min = 56.dp)
                // The trailing IconButton below is shrunk to 32.dp and
                // already sits inside this whole-row clickable, so the
                // platform's 48.dp touch-target padding around it (see the
                // CompositionLocalProvider below) would otherwise double up
                // with this inset — 8.dp here + that button's own 7.dp of
                // internal centering lines back up with the 16.dp leading
                // inset instead of dwarfing it.
                // Without the trailing button the row owns its full inset,
                // so the balance ends on the same edge as a TransactionRow's
                // amount (both 16.dp from the card edge).
                .padding(
                    start = 16.dp + indent,
                    end = if (actions) 8.dp else 16.dp,
                    top = 8.dp,
                    bottom = 8.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (toggleSlot) {
                Box(modifier = Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                    if (childCount > 0 && !flat) {
                        // Disable the 48.dp minimum touch target: this icon
                        // sits inside a row that's already fully clickable
                        // (combinedClickable above), so shrinking its own hit
                        // box to the intended 32.dp doesn't cost accessible
                        // reach, and it stops Material from silently
                        // re-inflating it (which otherwise pushes the account
                        // name 8.dp further right than the mirrored trailing
                        // icon).
                        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                            IconButton(
                                onClick = { onToggle(node.account.id) },
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(
                                    Icons.Filled.ExpandMore,
                                    contentDescription = stringResource(
                                        if (expanded) Res.string.account_collapse else Res.string.account_expand,
                                    ),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .size(20.dp)
                                        .graphicsLayer(rotationZ = rotation),
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.width(6.dp))
            } else if (row.depth > 0) {
                // Nesting cue for the always-expanded tree: a short rule the
                // child name hangs off of.
                Box(
                    modifier = Modifier
                        .size(width = 10.dp, height = 1.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant),
                )
                Spacer(Modifier.width(10.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        node.account.name.censored(),
                        style = if (row.depth == 0) {
                            MaterialTheme.typography.titleSmall
                        } else {
                            MaterialTheme.typography.bodyLarge
                        },
                        color = if (row.depth == 0) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    // Two accounts may share a name when their currencies
                    // differ, so this is not decoration: without it the tree
                    // shows two identical rows and the long-press sheet acts
                    // on whichever one the user guessed.
                    CommodityBadge(node.account.commodity)
                    // The account new transactions of this type preselect.
                    if (state.defaultAccounts[node.account.type] == node.account.id) {
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            Icons.Filled.Star,
                            contentDescription = stringResource(Res.string.account_default_badge),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
                // Folded parents advertise what they're hiding; expanded ones
                // don't need the noise.
                if (childCount > 0 && !expanded && !flat) {
                    Text(
                        subaccountsLabel(childCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(start = 12.dp)) {
                BalanceText(
                    totals = state.totals[node.account.id].orEmpty(),
                    signalNegative = node.account.type == AccountType.Asset ||
                        node.account.type == AccountType.Expense,
                )
                // The number shown is a subtree rollup, not just this
                // account's own postings — "comida ARS 2.500" would otherwise
                // read the same whether or not "verduras" is folded inside it.
                if (childCount > 0 && state.leafTotals[node.account.id].orEmpty() != state.totals[node.account.id].orEmpty()) {
                    Text(
                        stringResource(Res.string.account_includes_subaccounts),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
            // Same rationale as the chevron above: the row itself is already
            // the primary tap target (single click opens the account, long
            // click opens this same sheet), so this is just a visual
            // affordance and can honor its explicit 32.dp size instead of
            // Material's 48.dp minimum, which was the actual cause of the
            // lopsided right-hand gap.
            if (actions) {
                CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                    IconButton(
                        onClick = { actionsOpen = true },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            Icons.Filled.MoreVert,
                            contentDescription = stringResource(Res.string.account_actions),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
    }
    if (actionsOpen) {
        AccountActionsSheet(
            state = state,
            account = node.account,
            onDismiss = { actionsOpen = false },
        )
    }
}

/**
 * Long-press actions: rename, move under another same-type account, delete.
 * The sheet hides itself before showing the rename dialog / move picker, and
 * [onDismiss] fires only once the whole flow is finished — the caller removes
 * this composable on dismiss, which would otherwise take the dialog with it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AccountActionsSheet(
    state: LedgerState,
    account: Account,
    onDismiss: () -> Unit,
    onDeleted: () -> Unit = {},
) {
    var sheetOpen by remember { mutableStateOf(true) }
    var renaming by remember { mutableStateOf(false) }
    var pickingIcon by remember { mutableStateOf(false) }
    var pickingCommodity by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf(account.name) }
    val deleteMessage = stringResource(Res.string.account_delete_message, account.name.censored())
    val undoLabel = stringResource(Res.string.action_undo)
    if (sheetOpen) {
        ModalBottomSheet(onDismissRequest = onDismiss) {
            val itemColors = ListItemDefaults.colors(containerColor = Color.Transparent)
            Column(modifier = Modifier.padding(bottom = 24.dp)) {
                Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)) {
                    Text(account.name.censored(), style = MaterialTheme.typography.titleLarge)
                    Text(
                        accountTypeLabel(account.type),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(8.dp))
                ListItem(
                    colors = itemColors,
                    leadingContent = { Icon(Icons.Filled.Edit, contentDescription = null) },
                    headlineContent = { Text(stringResource(Res.string.account_rename)) },
                    modifier = Modifier.clickable {
                        sheetOpen = false
                        renaming = true
                    },
                )
                ListItem(
                    colors = itemColors,
                    // The icon itself is the leading content: the row shows
                    // what it edits, which no label can do as well.
                    leadingContent = {
                        AccountAvatar(
                            icon = AccountIcons.resolve(account.icon, account.type),
                            size = 28.dp,
                        )
                    },
                    headlineContent = { Text(stringResource(Res.string.category_icon_label)) },
                    modifier = Modifier.clickable {
                        sheetOpen = false
                        pickingIcon = true
                    },
                )
                ListItem(
                    colors = itemColors,
                    leadingContent = { Icon(Icons.Filled.AccountTree, contentDescription = null) },
                    headlineContent = { Text(stringResource(Res.string.account_move_under)) },
                    modifier = Modifier.clickable {
                        sheetOpen = false
                        moving = true
                    },
                )
                run {
                    val isDefault = state.defaultAccounts[account.type] == account.id
                    ListItem(
                        colors = itemColors,
                        leadingContent = {
                            Icon(
                                if (isDefault) Icons.Filled.Star else Icons.Filled.StarOutline,
                                contentDescription = null,
                                tint = if (isDefault) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    LocalContentColor.current
                                },
                            )
                        },
                        headlineContent = {
                            Text(
                                stringResource(
                                    if (isDefault) {
                                        Res.string.account_unset_default
                                    } else {
                                        Res.string.account_set_default
                                    },
                                ),
                            )
                        },
                        supportingContent = {
                            Text(stringResource(Res.string.account_default_hint, accountTypeLabel(account.type)))
                        },
                        modifier = Modifier.clickable {
                            state.toggleDefaultAccount(account)
                            onDismiss()
                        },
                    )
                }
                if (account.type == AccountType.Asset || account.type == AccountType.Liability) {
                    // Categories never hold a currency: an expense is
                    // whatever the account it was paid from holds.
                    ListItem(
                        colors = itemColors,
                        leadingContent = { Icon(Icons.Filled.Tune, contentDescription = null) },
                        headlineContent = { Text(stringResource(Res.string.account_commodity_label)) },
                        supportingContent = {
                            Text(account.commodity ?: stringResource(Res.string.account_commodity_any))
                        },
                        modifier = Modifier.clickable {
                            sheetOpen = false
                            pickingCommodity = true
                        },
                    )
                    ListItem(
                        colors = itemColors,
                        leadingContent = { Icon(Icons.Filled.Tune, contentDescription = null) },
                        headlineContent = { Text(stringResource(Res.string.account_in_net_worth_toggle)) },
                        trailingContent = {
                            Switch(
                                checked = account.inNetWorth,
                                onCheckedChange = { checked -> state.setInNetWorth(account.id, checked) },
                            )
                        },
                    )
                }
                ListItem(
                    colors = itemColors,
                    leadingContent = {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                        )
                    },
                    headlineContent = {
                        Text(
                            stringResource(Res.string.action_delete),
                            color = MaterialTheme.colorScheme.error,
                        )
                    },
                    modifier = Modifier.clickable {
                        // Failures surface through state.error like every mutation.
                        state.deleteAccount(account.id)
                        Feedback.undoable(deleteMessage, undoLabel) {
                            // Restores the currency too: without it the undo
                            // recreates a namesake that now collides with its
                            // own sibling.
                            state.addAccount(
                                account.name,
                                account.type,
                                account.parentId,
                                account.icon,
                                account.commodity,
                            )
                        }
                        onDismiss()
                        onDeleted()
                    },
                )
            }
        }
    }
    if (pickingCommodity) {
        CommodityPickerDialog(
            selected = account.commodity,
            onDismiss = onDismiss,
            onPick = {
                state.setCommodity(account.id, it)
                onDismiss()
            },
        )
    }
    if (pickingIcon) {
        IconPickerDialog(
            selected = account.icon,
            onDismiss = onDismiss,
            onPick = {
                state.setIcon(account.id, it)
                onDismiss()
            },
        )
    }
    if (renaming) {
        fun save() {
            if (name.isBlank()) return
            state.rename(account.id, name.trim())
            onDismiss()
        }
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(Res.string.account_rename)) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(Res.string.account_name_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { save() }),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { save() },
                    enabled = name.isNotBlank() && !state.busy,
                ) { Text(stringResource(Res.string.action_save)) }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) }
            },
        )
    }
    if (moving) {
        val node = findNode(state.tree, account.id)
        AccountPickerSheet(
            tree = state.tree.filter { it.account.type == account.type },
            title = stringResource(Res.string.account_move_under_title),
            exclude = buildSet {
                add(account.id)
                node?.selfAndDescendants?.forEach { add(it.account.id) }
            },
            // Only offered when there is somewhere to come back from.
            rootLabel = if (account.parentId != null) {
                stringResource(Res.string.account_move_to_root)
            } else {
                null
            },
            onPickRoot = if (account.parentId != null) {
                {
                    state.reparent(account.id, null)
                    onDismiss()
                }
            } else {
                null
            },
            onDismiss = onDismiss,
        ) { picked ->
            state.reparent(account.id, picked.account.id)
            onDismiss()
        }
    }
}

@Composable
private fun subaccountsLabel(count: Int): String = if (count == 1) {
    stringResource(Res.string.account_subaccounts_one)
} else {
    stringResource(Res.string.account_subaccounts_many, count)
}

/**
 * Type header's rollup, real amounts instead of a "+n" count: one entry per
 * commodity, side by side, same number formatting as every row below it. No
 * chip background — it's unweighted in [SectionHeader]'s row (the title
 * soaks up whatever's left), so it reads as plain trailing text instead of a
 * badge competing with the type name.
 */
@Composable
private fun TypeTotals(totals: Map<String, Long>) {
    if (totals.isEmpty()) return
    val entries = totals.entries.sortedByDescending { kotlin.math.abs(it.value) }
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        entries.forEach { (commodity, minor) ->
            Text(
                // A type total is never colored (a negative income total is
                // normal bookkeeping), so this one keeps its sign.
                formatMoney(minor, commodity, signed = true),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

internal fun findNode(tree: List<AccountNode>, id: String?): AccountNode? {
    if (id == null) return null
    for (root in tree) {
        val found = root.selfAndDescendants.firstOrNull { it.account.id == id }
        if (found != null) return found
    }
    return null
}

@Composable
internal fun TypeDropdown(
    initial: AccountType,
    enabled: Boolean = true,
    onPick: (AccountType) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var current by remember { mutableStateOf(initial) }
    ExposedDropdownMenuBox(
        expanded = expanded && enabled,
        onExpandedChange = { if (enabled) expanded = it },
    ) {
        OutlinedTextField(
            value = accountTypeLabel(current),
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text(stringResource(Res.string.account_type_label)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded && enabled) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
            AccountType.entries.forEach { candidate ->
                DropdownMenuItem(
                    text = { Text(accountTypeLabel(candidate)) },
                    onClick = {
                        current = candidate
                        onPick(candidate)
                        expanded = false
                    },
                )
            }
        }
    }
}

// Account creation now lives in its own screen, AccountAddScreen.kt — see
// AccountAddRoute.

/**
 * The account's declared currency, as a quiet pill next to its name. Drawn
 * only when there is one: an undeclared account is the common case and a
 * badge on every row would be noise.
 *
 * This is what makes same-named accounts legible — "Santander" in pesos and
 * "Santander" in dollares are two rows that differ in nothing else.
 */
@Composable
internal fun CommodityBadge(commodity: String?) {
    if (commodity.isNullOrBlank()) return
    Spacer(Modifier.width(6.dp))
    Box(
        modifier = Modifier
            .background(
                MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(4.dp),
            )
            .padding(horizontal = 4.dp, vertical = 1.dp),
    ) {
        Text(
            commodity,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/**
 * Currency for an asset/liability account, with "cualquiera" (null) as a
 * first-class choice rather than an empty state: not declaring one is a valid
 * answer, and it is what every pre-existing account holds.
 */
@Composable
internal fun CommodityPickerDialog(
    selected: String?,
    onDismiss: () -> Unit,
    onPick: (String?) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.account_commodity_label)) },
        text = {
            Column {
                Text(
                    stringResource(Res.string.account_commodity_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                val options: List<String?> = listOf<String?>(null) + QUICK_COMMODITIES
                options.forEach { option ->
                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        headlineContent = {
                            Text(option ?: stringResource(Res.string.account_commodity_any))
                        },
                        trailingContent = {
                            if (option == selected) {
                                Icon(
                                    Icons.Filled.Star,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        },
                        modifier = Modifier.clickable { onPick(option) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) }
        },
    )
}
