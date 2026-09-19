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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import weil.app.sharedui.generated.resources.Res
import weil.app.sharedui.generated.resources.account_delete_message
import weil.app.sharedui.generated.resources.account_name_label
import weil.app.sharedui.generated.resources.action_add
import weil.app.sharedui.generated.resources.action_cancel
import weil.app.sharedui.generated.resources.action_delete
import weil.app.sharedui.generated.resources.action_save
import weil.app.sharedui.generated.resources.action_undo
import weil.app.sharedui.generated.resources.categories_empty
import weil.app.sharedui.generated.resources.categories_title
import weil.app.sharedui.generated.resources.category_edit_title
import weil.app.sharedui.generated.resources.category_icon_label
import weil.app.sharedui.generated.resources.category_new_title
import weil.app.sharedui.generated.resources.category_parent_label
import weil.app.sharedui.generated.resources.category_parent_none
import weil.app.sharedui.generated.resources.category_pick_parent

/**
 * Expense categories, and only those: this is the screen a user reaches for
 * to tidy up "where the money goes", so it deliberately hides the other four
 * account types (the complete plan lives in [AccountsTreeScreen]). Create,
 * rename, re-parent, delete — plus the icon, which is the whole point of the
 * round avatars on Home.
 */
@Composable
fun CategoriesScreen(
    ledgerState: LedgerState,
    onNavigateToAccount: (id: String) -> Unit,
    bottomBar: @Composable () -> Unit = {},
) {
    if (!ledgerState.loaded) {
        LaunchedEffect(Unit) { ledgerState.refresh() }
    }
    var editing by remember { mutableStateOf<Account?>(null) }
    var creating by remember { mutableStateOf(false) }

    val expenses = remember(ledgerState.tree) {
        ledgerState.tree.filter { it.account.type == AccountType.Expense }
    }
    val rows = remember(expenses) {
        buildList<TreeRow> { addFlat(expenses, 0) }.filterIsInstance<NodeRow>()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = bottomBar,
        topBar = {
            AppTopBar(
                title = stringResource(Res.string.categories_title),
                // "New category" is a header action, not a FAB: the bottom
                // bar's centered button already means "new movement" on every
                // root screen, and two + buttons on one screen that create
                // different things is a trap.
                actions = {
                    IconButton(onClick = { creating = true }) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = stringResource(Res.string.category_new_title),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = ledgerState.pullRefreshing,
            onRefresh = { ledgerState.refresh(userInitiated = true) },
            modifier = Modifier.fillMaxSize().padding(innerPadding),
        ) {
            if (rows.isEmpty() && ledgerState.loaded) {
                Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
                    EmptyHint(
                        title = stringResource(Res.string.categories_empty),
                        action = stringResource(Res.string.category_new_title),
                        onAction = { creating = true },
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                ) {
                    itemsIndexed(rows, key = { _, row -> row.key }) { index, row ->
                        CategoryRow(
                            state = ledgerState,
                            row = row,
                            skin = rowSkin(first = index == 0, last = index == rows.lastIndex),
                            // Tapping the icon edits the icon; tapping the row
                            // edits the category. Two intents, two targets, no
                            // menu in between.
                            onEdit = { editing = row.node.account },
                            onOpen = { onNavigateToAccount(row.node.account.id) },
                        )
                    }
                }
            }
        }
    }

    if (creating) {
        CategoryDialog(
            state = ledgerState,
            account = null,
            onDismiss = { creating = false },
        )
    }
    editing?.let { account ->
        CategoryDialog(
            state = ledgerState,
            account = account,
            onDismiss = { editing = null },
        )
    }
}

@Composable
private fun CategoryRow(
    state: LedgerState,
    row: NodeRow,
    skin: RowSkin,
    onEdit: () -> Unit,
    onOpen: () -> Unit,
) {
    val account = row.node.account
    val indent = (row.depth.coerceAtMost(4) * 16).dp
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(skin.shape)
            .background(skin.container),
    ) {
        if (skin.divider) {
            HorizontalDivider(
                modifier = Modifier.padding(start = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onEdit)
                .heightIn(min = 64.dp)
                .padding(start = 16.dp + indent, end = 8.dp, top = 8.dp, bottom = 8.dp),
        ) {
            AccountAvatar(icon = AccountIcons.resolve(account.icon, account.type))
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    account.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val total = state.totals[account.id].orEmpty()
                if (total.isNotEmpty()) {
                    Text(
                        formatTotals(total),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
            // The register of the category, for "what did I actually spend
            // here" — the one thing this screen doesn't do itself.
            IconButton(onClick = onOpen) {
                Icon(
                    Icons.Filled.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Create/edit a category: name, icon and parent in one dialog, with delete
 * on the edit path. [account] null means "new".
 */
@Composable
private fun CategoryDialog(
    state: LedgerState,
    account: Account?,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(account?.name ?: "") }
    var icon by remember { mutableStateOf(account?.icon) }
    var parentId by remember { mutableStateOf(account?.parentId) }
    var pickingIcon by remember { mutableStateOf(false) }
    var pickingParent by remember { mutableStateOf(false) }
    val nameFocus = remember { FocusRequester() }
    val deleteMessage = account?.let { stringResource(Res.string.account_delete_message, it.name) }
    val undoLabel = stringResource(Res.string.action_undo)
    val noneLabel = stringResource(Res.string.category_parent_none)
    val parentName = remember(parentId, state.tree) {
        parentId?.let { findNode(state.tree, it)?.account?.name }
    }

    LaunchedEffect(Unit) { if (account == null) nameFocus.requestFocus() }

    fun save() {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        if (account == null) {
            state.addAccount(trimmed, AccountType.Expense, parentId, icon)
        } else {
            if (trimmed != account.name) state.rename(account.id, trimmed)
            if (icon != account.icon) state.setIcon(account.id, icon)
            if (parentId != account.parentId) state.reparent(account.id, parentId)
        }
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (account == null) Res.string.category_new_title else Res.string.category_edit_title,
                ),
            )
        },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AccountAvatar(
                        icon = AccountIcons.resolve(icon, AccountType.Expense),
                        size = 48.dp,
                        modifier = Modifier.clip(androidx.compose.foundation.shape.CircleShape)
                            .clickable { pickingIcon = true },
                    )
                    Spacer(Modifier.width(12.dp))
                    TextButton(onClick = { pickingIcon = true }) {
                        Text(stringResource(Res.string.category_icon_label))
                    }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(Res.string.account_name_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { save() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(nameFocus),
                )
                Spacer(Modifier.height(12.dp))
                PickerField(
                    label = stringResource(Res.string.category_parent_label),
                    value = parentName ?: noneLabel,
                    placeholder = noneLabel,
                    onClick = { pickingParent = true },
                )
                if (account != null && deleteMessage != null) {
                    Spacer(Modifier.height(8.dp))
                    TextButton(
                        onClick = {
                            state.deleteAccount(account.id)
                            Feedback.undoable(deleteMessage, undoLabel) {
                                state.addAccount(account.name, account.type, account.parentId, account.icon)
                            }
                            onDismiss()
                        },
                    ) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            stringResource(Res.string.action_delete),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { save() }, enabled = name.isNotBlank()) {
                Text(
                    stringResource(if (account == null) Res.string.action_add else Res.string.action_save),
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) }
        },
    )

    if (pickingIcon) {
        IconPickerDialog(
            selected = icon,
            onDismiss = { pickingIcon = false },
            onPick = {
                icon = it
                pickingIcon = false
            },
        )
    }
    if (pickingParent) {
        AccountPickerSheet(
            tree = state.tree.filter { it.account.type == AccountType.Expense },
            title = stringResource(Res.string.category_pick_parent),
            // A category can't live under itself or under one of its own
            // children; the repository would refuse, but the option shouldn't
            // be offered in the first place.
            exclude = account?.let { acc ->
                buildSet {
                    add(acc.id)
                    findNode(state.tree, acc.id)?.selfAndDescendants?.forEach { add(it.account.id) }
                }
            } ?: emptySet(),
            rootLabel = noneLabel,
            onPickRoot = {
                parentId = null
                pickingParent = false
            },
            onDismiss = { pickingParent = false },
        ) { picked ->
            parentId = picked.account.id
            pickingParent = false
        }
    }
}
