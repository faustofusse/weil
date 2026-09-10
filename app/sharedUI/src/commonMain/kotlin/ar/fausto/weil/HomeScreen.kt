@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package ar.fausto.weil

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

@Composable
fun HomeScreen(
    ledgerState: LedgerState,
    onNavigateToProfile: () -> Unit,
    onNavigateToNotifications: () -> Unit,
    onNavigateToEmails: () -> Unit,
    onNavigateToJournal: () -> Unit,
    onNavigateToNew: () -> Unit,
    onNavigateToAccount: (id: String) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Finance") },
                actions = {
                    IconButton(onClick = onNavigateToJournal) {
                        Icon(Icons.Filled.MenuBook, contentDescription = "View journal")
                    }
                    IconButton(onClick = onNavigateToNotifications) {
                        Icon(Icons.Filled.Notifications, contentDescription = "View notifications")
                    }
                    IconButton(onClick = onNavigateToEmails) {
                        Icon(Icons.Filled.Email, contentDescription = "View emails")
                    }
                    IconButton(onClick = onNavigateToProfile) {
                        Icon(Icons.Filled.AccountCircle, contentDescription = "View profile")
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
                Icon(Icons.Filled.Add, contentDescription = "New transaction")
            }
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
                NetWorthHeader(ledgerState)
                Spacer(Modifier.height(12.dp))
                AccountsSection(
                    ledgerState,
                    onNavigateToAccount = onNavigateToAccount,
                )
            }
        }
    }
}

@Composable
private fun NetWorthHeader(state: LedgerState) {
    val netWorth = netWorthOf(state)
    Column {
        Text(
            "Net worth",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            formatTotals(netWorth),
            style = MaterialTheme.typography.headlineSmall,
        )
        state.error?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (state.busy) {
            Row(
                modifier = Modifier.padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Syncing…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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

@Composable
private fun AccountsSection(
    state: LedgerState,
    onNavigateToAccount: (id: String) -> Unit,
) {
    if (!state.loaded) {
        LaunchedEffect(Unit) { state.refresh() }
    }
    var adding by remember { mutableStateOf(false) }

    if (state.tree.isEmpty() && !state.busy && state.error == null) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "No accounts yet",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = { adding = true }) {
                Text("Add your first account")
            }
            Text(
                "Long-press any account later to rename, move or delete it",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else {
        val rows = buildList {
            for (type in AccountType.entries) {
                val roots = state.tree.filter { it.account.type == type }
                if (roots.isEmpty()) continue
                add(TypeHeader(type))
                addSection(roots, state.expandedIds, 0)
            }
        }
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            items(rows, key = { it.key }) { row ->
                when (row) {
                    is TypeHeader -> {
                        val perType = typeSum(state, row.type)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp, bottom = 2.dp),
                        ) {
                            Text(
                                row.label,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                formatTotals(perType),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                    }
                    is NodeRow -> NodeRowView(
                        state = state,
                        row = row,
                        onToggle = { state.toggleExpanded(it) },
                        onOpen = { id -> onNavigateToAccount(id) },
                    )
                }
            }
        }
    }

    if (adding) {
        AddAccountDialog(state = state, onDismiss = { adding = false })
    }
}

private fun typeSum(state: LedgerState, type: AccountType): Map<String, Long> {
    val acc = mutableMapOf<String, Long>()
    state.tree.filter { it.account.type == type }.forEach { root ->
        for ((c, v) in state.totals[root.account.id].orEmpty()) {
            acc[c] = (acc[c] ?: 0L) + v
        }
    }
    return acc
}

private fun MutableList<TreeRow>.addSection(
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

sealed interface TreeRow {
    val key: String
}

data class TypeHeader(val type: AccountType) : TreeRow {
    override val key: String = "type-${type.db}"
    val label: String = type.db.replaceFirstChar { it.uppercase() }
}

data class NodeRow(val node: AccountNode, val depth: Int) : TreeRow {
    override val key: String get() = "node-${node.account.id}"
}

@Composable
private fun NodeRowView(
    state: LedgerState,
    row: NodeRow,
    onToggle: (id: String) -> Unit,
    onOpen: (id: String) -> Unit,
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
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(indent.dp))
        Box(modifier = Modifier.size(36.dp), contentAlignment = Alignment.Center) {
            if (node.children.isNotEmpty()) {
                IconButton(onClick = { onToggle(node.account.id) }) {
                    Icon(
                        Icons.Filled.ExpandMore,
                        contentDescription = if (expanded) "Collapse" else "Expand",
                        modifier = Modifier.graphicsLayer(rotationZ = rotation),
                    )
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
                style = MaterialTheme.typography.bodySmall,
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

/** Long-press actions: rename, move under another same-type account, delete. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccountActionsSheet(
    state: LedgerState,
    account: Account,
    onDismiss: () -> Unit,
) {
    var renaming by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf(account.name) }
    val scope = rememberCoroutineScope()
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(bottom = 16.dp)) {
            Text(
                account.name,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            ListItem(
                headlineContent = { Text("Rename") },
                modifier = Modifier.clickable {
                    onDismiss()
                    renaming = true
                },
            )
            ListItem(
                headlineContent = { Text("Move under…") },
                modifier = Modifier.clickable {
                    onDismiss()
                    moving = true
                },
            )
            ListItem(
                headlineContent = {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                },
                modifier = Modifier.clickable {
                    onDismiss()
                    val node = findNode(state.tree, account.id)
                    scope.launch {
                        try {
                            state.deleteAccount(account.id)
                            Feedback.undoable(
                                "Account “${account.name}” deleted",
                            ) {
                                state.addAccount(
                                    account.name,
                                    account.type,
                                    account.parentId,
                                )
                            }
                        } catch (e: Throwable) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            Feedback.undoable(
                                e.message ?: e.toString(),
                                actionLabel = "OK",
                            ) {}
                        }
                    }
                },
            )
        }
    }
    if (renaming) {
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Rename") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        state.rename(account.id, name.trim())
                        renaming = false
                    },
                    enabled = name.isNotBlank() && !state.busy,
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { renaming = false }) { Text("Cancel") }
            },
        )
    }
    if (moving) {
        val node = findNode(state.tree, account.id)
        AccountPickerSheet(
            tree = state.tree.filter { it.account.type == account.type },
            title = "Move under",
            exclude = buildSet {
                add(account.id)
                node?.selfAndDescendants?.forEach { add(it.account.id) }
            },
            onDismiss = { moving = false },
        ) { picked ->
            state.reparent(account.id, picked.account.id)
            moving = false
        }
    }
}

@Composable
private fun TypeDropdown(
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
            value = current.db.replaceFirstChar { it.uppercase() },
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text("Type") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded && enabled) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
            AccountType.entries.forEach { candidate ->
                DropdownMenuItem(
                    text = { Text(candidate.db.replaceFirstChar { it.uppercase() }) },
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

@Composable
private fun AddAccountDialog(state: LedgerState, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(AccountType.Asset) }
    var parent by remember { mutableStateOf<AccountNode?>(null) }
    var pickingParent by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add account") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { pickingParent = true }) {
                    Text(
                        if (parent == null) "No parent (tree root)" else "Under: ${parent!!.path}",
                    )
                }
                Spacer(Modifier.height(8.dp))
                TypeDropdown(
                    initial = if (parent != null) parent!!.account.type else type,
                    enabled = parent == null,
                    onPick = { type = it },
                )
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
            ) { Text("Add") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )

    if (pickingParent) {
        AccountPickerSheet(
            tree = state.tree,
            title = "Choose parent",
            exclude = emptySet(),
            onDismiss = { pickingParent = false },
        ) { picked ->
            parent = picked
            pickingParent = false
        }
    }
}

private fun findNode(tree: List<AccountNode>, id: String?): AccountNode? {
    if (id == null) return null
    for (root in tree) {
        val found = root.selfAndDescendants.firstOrNull { it.account.id == id }
        if (found != null) return found
    }
    return null
}
