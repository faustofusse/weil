@file:OptIn(ExperimentalMaterial3Api::class)

package ar.fausto.weil

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    ledgerState: LedgerState,
    onNavigateToProfile: () -> Unit,
    onNavigateToNotifications: () -> Unit,
    onNavigateToEmails: () -> Unit,
    onNavigateToJournal: () -> Unit,
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
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
        ) {
            NetWorthHeader(ledgerState)
            Spacer(Modifier.height(12.dp))
            AccountsSection(ledgerState, onNavigateToAccount = onNavigateToAccount)
        }
    }
}

@Composable
private fun NetWorthHeader(state: LedgerState) {
    val netWorth = mwcamenteHash(state)
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
            Row(modifier = Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
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

private fun mwcamenteHash(state: LedgerState): Map<String, Long> {
    val acc = mutableMapOf<String, Long>()
    for (root in state.tree) {
        if (root.account.type != AccountType.Asset && root.account.type != AccountType.Liability) continue
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
    var expandedIds by remember { mutableStateOf(setOf<String>()) }
    var adding by remember { mutableStateOf(false) }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "Accounts",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = { adding = true }) {
            Icon(Icons.Filled.Add, contentDescription = "Add account")
        }
    }

    if (state.tree.isEmpty() && !state.busy && state.error == null) {
        Text(
            "No accounts yet — add one below",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp),
        )
    } else {
        val rows = buildList {
            for (type in AccountType.entries) {
                val roots = state.tree.filter { it.account.type == type }
                if (roots.isEmpty()) continue
                add(TypeHeader(type))
                addSection(roots, expandedIds, 0)
            }
        }
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            items(rows, key = { it.key }) { row ->
                when (row) {
                    is TypeHeader -> Text(
                        row.label,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
                    )
                    is NodeRow -> NodeRowView(
                        state = state,
                        row = row,
                        expandedIds = expandedIds,
                        onToggle = { id ->
                            expandedIds = if (id in expandedIds) expandedIds - id else expandedIds + id
                        },
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
    expandedIds: Set<String>,
    onToggle: (id: String) -> Unit,
    onOpen: (id: String) -> Unit,
) {
    var editing by remember { mutableStateOf(false) }
    val node = row.node
    val hasChildren = node.children.isNotEmpty()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width((row.depth * 16).dp))
        if (hasChildren) {
            IconButton(onClick = { onToggle(node.account.id) }) {
                Icon(
                    if (node.account.id in expandedIds) Icons.Filled.Remove else Icons.Filled.Add,
                    contentDescription = if (node.account.id in expandedIds) "Collapse" else "Expand",
                )
            }
        } else {
            Spacer(Modifier.width(48.dp))
        }
        Text(
            node.account.name,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .clickable { onOpen(node.account.id) }
                .padding(vertical = 6.dp),
        )
        Text(
            formatTotals(state.totals[node.account.id].orEmpty()),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 8.dp),
        )
        TextButton(onClick = { editing = true }) {
            Text("Edit")
        }
    }
    if (editing) {
        EditAccountDialog(state = state, account = node.account, onDismiss = { editing = false })
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
        AccountPickerDialog(
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

@Composable
private fun EditAccountDialog(state: LedgerState, account: Account, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(account.name) }
    var pickingParent by remember { mutableStateOf(false) }
    var excluded by remember(account.id) { mutableStateOf(setOf(account.id)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit account") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                TextButton(
                    onClick = {
                        val node = findNode(state.tree, account.id)
                        excluded = buildSet {
                            add(account.id)
                            node?.selfAndDescendants?.forEach { add(it.account.id) }
                        }
                        pickingParent = true
                    },
                ) {
                    Text(
                        if (account.parentId == null) {
                            "Move to a parent"
                        } else {
                            "Move under: ${findNode(state.tree, account.parentId!!)?.path ?: account.parentId}"
                        },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    state.rename(account.id, name.trim())
                    onDismiss()
                },
                enabled = name.isNotBlank() && !state.busy,
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )

    if (pickingParent) {
        AccountPickerDialog(
            tree = state.tree.filter { it.account.type == account.type },
            title = "Move under",
            exclude = excluded,
            onDismiss = { pickingParent = false },
        ) { picked ->
            state.reparent(account.id, picked.account.id)
            pickingParent = false
            onDismiss()
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
