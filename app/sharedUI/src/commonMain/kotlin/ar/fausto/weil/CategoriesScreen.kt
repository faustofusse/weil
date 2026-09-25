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
import weil.app.sharedui.generated.resources.category_icon_inherited
import weil.app.sharedui.generated.resources.category_icon_label
import weil.app.sharedui.generated.resources.category_icon_own
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
    // Top level only. A flattened tree put "Comida" between "Carne" and
    // "Chino" — its own children, indented — which reads as eleven peers
    // instead of one category with three parts. The children live on the
    // category's own screen, as chips.
    val rows = remember(expenses) { expenses }

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
                    items(rows, key = { node -> node.account.id }) { node ->
                        CategoryRow(
                            state = ledgerState,
                            node = node,
                            // The row opens the category (its subcategories
                            // and its movements); the pencil edits it. Two
                            // intents, two targets, no menu in between.
                            onEdit = { editing = node.account },
                            onOpen = { onNavigateToAccount(node.account.id) },
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
    node: AccountNode,
    onEdit: () -> Unit,
    onOpen: () -> Unit,
) {
    val account = node.account
    val look = state.looks[account.id]
    val paint = accountPaint(look?.color, seed = look?.seed ?: account.id)
    val total = state.displayTotals[account.id].orEmpty()
    AppListRow(
        icon = AccountIcons.resolve(look?.icon, account.type),
        paint = paint,
        title = account.name.censored(),
        // The name carries the color too, not just the disc: it is the wider
        // target of the two, and a tinted circle next to default-ink text
        // reads as decoration rather than as the category's identity.
        titleColor = paint.ink,
        // A zero rather than no line at all, same as the subcategory rows:
        // an empty category is a real answer ("nothing was spent here"), and
        // omitting the line made that row shorter than its neighbours, which
        // read as a rendering glitch instead of as information.
        subtitle = if (total.isEmpty()) {
            formatMoney(0L, account.commodity ?: Money.DEFAULT_COMMODITY)
        } else {
            formatTotals(total)
        },
        onClick = onOpen,
        // Same gesture as the subcategory rows one screen in, for the same
        // edit: a long press works everywhere a pencil does, not just where
        // there happens to be room to draw one.
        onLongClick = onEdit,
    ) {
        // Rename/icon/color/parent, without having to enter the category
        // first — tidying up is a pass over the whole list.
        IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
            Icon(
                Icons.Filled.Edit,
                contentDescription = stringResource(Res.string.category_edit_title),
                tint = MaterialTheme.colorScheme.inverseSurface,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * Create/edit a category: name, icon and parent in one dialog, with delete
 * on the edit path. [account] null means "new".
 */
@Composable
internal fun CategoryDialog(
    state: LedgerState,
    account: Account?,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(account?.name ?: "") }
    var icon by remember { mutableStateOf(account?.icon) }
    var color by remember { mutableStateOf(account?.color) }
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
    // What a subcategory wears while its own icon/color is null. Looked up
    // on the *picked* parent, so moving the category previews the new one.
    val parentLook = parentId?.let { state.looks[it] }

    LaunchedEffect(Unit) { if (account == null) nameFocus.requestFocus() }

    fun save() {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        if (account == null) {
            state.addAccount(trimmed, AccountType.Expense, parentId, icon, color = color)
        } else {
            if (trimmed != account.name) state.rename(account.id, trimmed)
            if (icon != account.icon) state.setIcon(account.id, icon)
            if (color != account.color) state.setColor(account.id, color)
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
                // A new category previews the color it would be given, so the
                // dialog shows the same disc the list will.
                val paint = accountPaint(
                    color ?: parentLook?.color,
                    seed = if (color == null && parentLook != null) parentLook.seed else account?.id,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AccountAvatar(
                        icon = AccountIcons.resolve(icon ?: parentLook?.icon, AccountType.Expense),
                        size = 48.dp,
                        container = paint.tint,
                        content = paint.ink,
                        modifier = Modifier.clip(androidx.compose.foundation.shape.CircleShape)
                            .clickable { pickingIcon = true },
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        TextButton(onClick = { pickingIcon = true }) {
                            Text(stringResource(Res.string.category_icon_label))
                        }
                        // A subcategory says whether the glyph is its own or
                        // borrowed, since both look the same in the avatar.
                        if (parentName != null) {
                            Text(
                                if (icon == null) {
                                    stringResource(Res.string.category_icon_inherited, parentName.censored())
                                } else {
                                    stringResource(Res.string.category_icon_own)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 12.dp),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                // Under the avatar it previews: picking a swatch repaints the
                // circle right above it.
                ColorPickerRow(
                    selected = color,
                    onPick = { color = it },
                    inherited = parentLook?.let { accountPaint(it.color, seed = it.seed) },
                )
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
                                state.addAccount(
                                    account.name,
                                    account.type,
                                    account.parentId,
                                    account.icon,
                                    color = account.color,
                                )
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
            inherited = parentLook?.let { AccountIcons.resolve(it.icon, AccountType.Expense) },
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
                // Promoted to a top-level category: whatever it was borrowing
                // becomes its own, or it would turn into the generic cart.
                parentLook?.let { look ->
                    if (icon == null) icon = look.icon
                    if (color == null) color = look.color
                }
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
