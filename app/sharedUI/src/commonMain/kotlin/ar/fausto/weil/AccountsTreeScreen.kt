@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package ar.fausto.weil

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import weil.app.sharedui.generated.resources.Res
import weil.app.sharedui.generated.resources.account_add_title
import weil.app.sharedui.generated.resources.account_choose_parent
import weil.app.sharedui.generated.resources.account_collapse
import weil.app.sharedui.generated.resources.account_delete_message
import weil.app.sharedui.generated.resources.account_expand
import weil.app.sharedui.generated.resources.account_move_under
import weil.app.sharedui.generated.resources.account_move_under_title
import weil.app.sharedui.generated.resources.account_name_label
import weil.app.sharedui.generated.resources.account_parent_none
import weil.app.sharedui.generated.resources.account_parent_under
import weil.app.sharedui.generated.resources.account_rename
import weil.app.sharedui.generated.resources.account_type_asset
import weil.app.sharedui.generated.resources.account_type_equity
import weil.app.sharedui.generated.resources.account_type_expense
import weil.app.sharedui.generated.resources.account_type_income
import weil.app.sharedui.generated.resources.account_type_label
import weil.app.sharedui.generated.resources.account_type_liability
import weil.app.sharedui.generated.resources.action_add
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
) {
    var adding by remember { mutableStateOf(false) }
    Scaffold(
        floatingActionButton = {
            FloatingActionButton(
                onClick = { adding = true },
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
            isRefreshing = ledgerState.busy,
            onRefresh = { ledgerState.refresh() },
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
                    onAdd = { adding = true },
                )
            }
        }
    }

    if (adding) {
        AddAccountDialog(state = ledgerState, onDismiss = { adding = false })
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
            items(rows, key = { it.key }) { row ->
                when (row) {
                    is TypeHeader -> SectionHeader(
                        title = accountTypeLabel(row.type),
                        trailing = {
                            Text(
                                formatTotals(typeSum(state, row.type)),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                    )
                    is NodeRow -> NodeRowView(
                        state = state,
                        row = row,
                        onToggle = { state.toggleExpanded(it) },
                        onOpen = { id -> onNavigateToAccount(id) },
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
    flat: Boolean = false,
) {
    val node = row.node
    var actions by remember { mutableStateOf(false) }
    val expanded = node.account.id in state.expandedIds
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 0f else -90f,
        animationSpec = tween(180),
        label = "chevron",
    )
    // Deep trees don't run off-screen: depth padding is capped and any deeper
    // level keeps the max indent.
    val indent = row.depth.coerceAtMost(6) * 14
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { onOpen(node.account.id) },
                onLongClick = { actions = true },
            )
            .heightIn(min = 48.dp)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (flat) {
            if (row.depth > 0) {
                Spacer(Modifier.width(((row.depth - 1).coerceAtMost(6) * 18).dp))
                // Box-drawing corner: the nesting indicator lives in the row's
                // baseline so it points at the child's name.
                Text(
                    "└",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(6.dp))
            }
        } else {
            Spacer(Modifier.width(indent.dp))
            Box(modifier = Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                if (node.children.isNotEmpty()) {
                    IconButton(onClick = { onToggle(node.account.id) }) {
                        Icon(
                            Icons.Filled.ExpandMore,
                            contentDescription = stringResource(
                                if (expanded) Res.string.account_collapse else Res.string.account_expand,
                            ),
                            modifier = Modifier.graphicsLayer(rotationZ = rotation),
                        )
                    }
                }
            }
        }
        Text(
            node.account.name,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(start = 8.dp)) {
            Text(
                formatTotals(state.totals[node.account.id].orEmpty()),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    if (actions) {
        AccountActionsSheet(
            state = state,
            account = node.account,
            onDismiss = { actions = false },
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
    var moving by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf(account.name) }
    val deleteMessage = stringResource(Res.string.account_delete_message, account.name)
    val undoLabel = stringResource(Res.string.action_undo)
    if (sheetOpen) {
        ModalBottomSheet(onDismissRequest = onDismiss) {
            Column(modifier = Modifier.padding(bottom = 16.dp)) {
                Text(
                    account.name,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                ListItem(
                    headlineContent = { Text(stringResource(Res.string.account_rename)) },
                    modifier = Modifier.clickable {
                        sheetOpen = false
                        renaming = true
                    },
                )
                ListItem(
                    headlineContent = { Text(stringResource(Res.string.account_move_under)) },
                    modifier = Modifier.clickable {
                        sheetOpen = false
                        moving = true
                    },
                )
                ListItem(
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
                            state.addAccount(account.name, account.type, account.parentId)
                        }
                        onDismiss()
                        onDeleted()
                    },
                )
            }
        }
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
            onDismiss = onDismiss,
        ) { picked ->
            state.reparent(account.id, picked.account.id)
            onDismiss()
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

/**
 * Account creation dialog. When [fixedType] is set (Home creates asset
 * accounts only) the type dropdown is hidden and the parent picker is
 * restricted to that type's subtree.
 */
@Composable
internal fun AddAccountDialog(
    state: LedgerState,
    onDismiss: () -> Unit,
    fixedType: AccountType? = null,
) {
    var name by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(fixedType ?: AccountType.Asset) }
    var parent by remember { mutableStateOf<AccountNode?>(null) }
    var pickingParent by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.account_add_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(Res.string.account_name_label)) },
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { pickingParent = true }) {
                    Text(
                        if (parent == null) {
                            stringResource(Res.string.account_parent_none)
                        } else {
                            stringResource(Res.string.account_parent_under, parent!!.path)
                        },
                    )
                }
                if (fixedType == null) {
                    Spacer(Modifier.height(8.dp))
                    TypeDropdown(
                        initial = if (parent != null) parent!!.account.type else type,
                        enabled = parent == null,
                        onPick = { type = it },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    state.addAccount(
                        name.trim(),
                        parent?.account?.type ?: type,
                        parent?.account?.id,
                    )
                    onDismiss()
                },
                enabled = name.isNotBlank() && !state.busy,
            ) { Text(stringResource(Res.string.action_add)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) }
        },
    )

    if (pickingParent) {
        val allowedType = parent?.account?.type ?: fixedType
        AccountPickerSheet(
            tree = if (allowedType != null) {
                state.tree.filter { it.account.type == allowedType }
            } else {
                state.tree
            },
            title = stringResource(Res.string.account_choose_parent),
            exclude = emptySet(),
            onDismiss = { pickingParent = false },
        ) { picked ->
            parent = picked
            pickingParent = false
        }
    }
}
