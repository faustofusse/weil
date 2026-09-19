@file:OptIn(ExperimentalMaterial3Api::class, androidx.compose.ui.ExperimentalComposeUiApi::class)

package ar.fausto.weil

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ar.fausto.weil.AccountType
import ar.fausto.weil.AccountsRepository
import ar.fausto.weil.DraftPosting
import ar.fausto.weil.Money
import ar.fausto.weil.SettingsRepository
import ar.fausto.weil.TransactionsRepository
import ar.fausto.weil.epochMillis
import ar.fausto.weil.resolveDefault
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import weil.app.sharedui.generated.resources.Res
import weil.app.sharedui.generated.resources.action_close
import weil.app.sharedui.generated.resources.picker_create
import weil.app.sharedui.generated.resources.quick_amount_label
import weil.app.sharedui.generated.resources.quick_category_label
import weil.app.sharedui.generated.resources.quick_choose
import weil.app.sharedui.generated.resources.quick_description_label
import weil.app.sharedui.generated.resources.quick_error_amount
import weil.app.sharedui.generated.resources.quick_expense_title
import weil.app.sharedui.generated.resources.quick_from_label
import weil.app.sharedui.generated.resources.quick_income_title
import weil.app.sharedui.generated.resources.quick_record
import weil.app.sharedui.generated.resources.quick_source_label
import weil.app.sharedui.generated.resources.quick_to_label
import weil.app.sharedui.generated.resources.quick_transfer_from_label
import weil.app.sharedui.generated.resources.quick_transfer_title
import weil.app.sharedui.generated.resources.sheet_new_title

/**
 * Entering a movement, presented as a panel that rises out of the bottom bar
 * and stops under the header.
 *
 * It is *not* a nav route: a route would cross-fade or slide sideways like
 * every other destination, and the whole point is that the create button
 * grows into the surface it sits on — same mint, same rounded top edge, the
 * header of the screen behind still visible so this reads as a layer over the
 * app rather than a place you travelled to. Back closes it without touching
 * the back stack, so a tab is never left behind by dismissing a form.
 *
 * The older [TransactionQuickScreen] (a full destination with its own app
 * bar) is untouched and still routable; this is the one the bar opens.
 */
@Composable
fun TransactionSheet(
    visible: Boolean,
    ledger: TransactionsRepository,
    accounts: AccountsRepository,
    settings: SettingsRepository,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    // Back closes the panel instead of leaving the tab behind it: it is not
    // on the back stack, so nothing else would have answered.
    BackHandler(enabled = visible, onBack = onDismiss)
    Box(modifier = Modifier.fillMaxSize()) {
        // Dim only what stays visible (the header strip): the panel itself
        // covers everything else, so a full-screen scrim would just darken
        // the app for the length of the animation.
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(SheetEnterMs)),
            exit = fadeOut(tween(SheetExitMs)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f))
                    // Tapping the visible header closes it; no ripple, this
                    // is a dismiss area, not a control.
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    ),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                // What the panel leaves uncovered: the status bar and one
                // header's worth of the screen behind.
                .statusBarsPadding()
                .padding(top = TopBarHeight)
                // The keyboard shortens the panel instead of pushing it: the
                // panel *is* the window here, and anything below the keyboard
                // is unreachable anyway. Inside the form this was the bug —
                // the content shrank but the mint stayed full height, leaving
                // a keyboard-sized empty band under the record button.
                .imePadding(),
        ) {
            AnimatedVisibility(
                visible = visible,
                modifier = Modifier.align(Alignment.BottomCenter),
                // Rises from below the screen's edge, which is where the bar
                // (and its create button) is.
                enter = slideInVertically(
                    animationSpec = tween(SheetEnterMs, easing = FastOutSlowInEasing),
                    initialOffsetY = { it },
                ),
                exit = slideOutVertically(
                    animationSpec = tween(SheetExitMs, easing = FastOutSlowInEasing),
                    targetOffsetY = { it },
                ),
            ) {
                Surface(
                    // Mint, like the bar it grew out of, and the same corner
                    // radius the bar rounds its top with.
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    shape = RoundedCornerShape(topStart = BarCorner, topEnd = BarCorner),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    TransactionSheetForm(
                        ledger = ledger,
                        accounts = accounts,
                        settings = settings,
                        onDismiss = onDismiss,
                        onSaved = onSaved,
                    )
                }
            }
        }
    }
}

/** Which of the two legs a picker is being opened for. */
private enum class SheetSide { From, To }

private const val SheetEnterMs = 320
private const val SheetExitMs = 220

@Composable
private fun TransactionSheetForm(
    ledger: TransactionsRepository,
    accounts: AccountsRepository,
    settings: SettingsRepository,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    var currentKind by remember { mutableStateOf(TxnKind.Expense) }
    val kindLabel = sheetKindTitle(currentKind)
    val accountLabel = stringResource(Res.string.quick_from_label)
    val fromLabel = when (currentKind) {
        TxnKind.Expense -> accountLabel
        TxnKind.Income -> stringResource(Res.string.quick_source_label)
        TxnKind.Transfer -> stringResource(Res.string.quick_transfer_from_label)
    }
    val toLabel = when (currentKind) {
        TxnKind.Expense -> stringResource(Res.string.quick_category_label)
        TxnKind.Income -> accountLabel
        TxnKind.Transfer -> stringResource(Res.string.quick_to_label)
    }
    val chooseLabel = stringResource(Res.string.quick_choose)
    val newCategoryLabel = stringResource(Res.string.picker_create)

    var amountText by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var fromId by remember { mutableStateOf<String?>(null) }
    var toId by remember { mutableStateOf<String?>(null) }
    var tree by remember { mutableStateOf<List<AccountNode>>(emptyList()) }
    var defaults by remember { mutableStateOf<Map<AccountType, String>>(emptyMap()) }
    var paths by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var picking by remember { mutableStateOf<SheetSide?>(null) }
    var creatingCategory by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val amountFocus = remember { FocusRequester() }

    val money = Money.parse(amountText.trim(), Money.DEFAULT_COMMODITY)
    val amountValid = money != null && money.minorUnits > 0
    val canRecord = !busy && amountValid && fromId != null && toId != null

    suspend fun reloadTree() {
        defaults = settings.defaultAccounts()
        tree = accounts.tree()
        paths = tree.flatMap { it.selfAndDescendants }
            .associate { it.account.id to it.path.censored() }
    }

    LaunchedEffect(Unit) {
        amountFocus.requestFocus()
        reloadTree()
    }

    // Same defaulting as the quick screen: the account marked default for
    // each type, else the seeded fallback. The pick the user would most
    // often not change anyway, and a wrong guess is one tap to fix.
    LaunchedEffect(tree, defaults, currentKind) {
        if (tree.isEmpty()) return@LaunchedEffect
        fun default(type: AccountType): String? = resolveDefault(tree, type, defaults[type])
        when (currentKind) {
            TxnKind.Expense -> {
                if (fromId == null) fromId = default(AccountType.Asset)
                if (toId == null) toId = default(AccountType.Expense)
            }
            TxnKind.Income -> {
                if (fromId == null) fromId = default(AccountType.Income)
                if (toId == null) toId = default(AccountType.Asset)
            }
            TxnKind.Transfer -> {
                if (fromId == null && toId == null) {
                    val from = default(AccountType.Asset)
                    fromId = from
                    toId = tree.filter { it.account.type == AccountType.Asset }
                        .firstOrNull { it.account.id != from }?.account?.id
                }
            }
        }
    }

    fun switchKind(next: TxnKind) {
        if (next == currentKind) return
        // The asset side survives the switch: the account is the part the
        // user already chose, only its role changes.
        val asset = when (currentKind) {
            TxnKind.Expense, TxnKind.Transfer -> fromId
            TxnKind.Income -> toId
        }
        fromId = null
        toId = null
        when (next) {
            TxnKind.Expense, TxnKind.Transfer -> fromId = asset
            TxnKind.Income -> toId = asset
        }
        error = null
        currentKind = next
    }

    fun record() {
        val amount = money ?: return
        val from = fromId ?: return
        val to = toId ?: return
        if (!canRecord) return
        busy = true
        error = null
        scope.launch {
            try {
                val drafts = listOf(
                    DraftPosting(from, formatMinorUnits(-amount.minorUnits)),
                    DraftPosting(to, formatMinorUnits(amount.minorUnits)),
                )
                ledger.add(epochMillis(), description.trim().ifBlank { kindLabel }, null, drafts)
                onSaved()
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                error = e.message ?: e.toString()
                busy = false
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
            .padding(top = 12.dp, bottom = 20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                stringResource(Res.string.sheet_new_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Light,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDismiss) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(Res.string.action_close))
            }
        }
        Spacer(Modifier.padding(top = 4.dp))
        // Only the fields scroll; the title stays put and the record button
        // stays reachable, which is what a weighted scroll region buys that
        // a scrolling column with a weighted spacer in it does not (that was
        // the other half of the keyboard bug).
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            KindSwitcher(current = currentKind, onSelect = { switchKind(it) })

            // The amount is the reason the screen opened: no box around it, it's
            // just the biggest thing on the panel, focused and waiting.
            TextField(
                value = amountText,
                onValueChange = { amountText = sanitizeAmountInput(it, allowNegative = false) },
                // "0,00", not the word "Monto": next to the symbol it shows the
                // shape of what goes here, and the label would be a second
                // large word fighting the title for the eye.
                placeholder = {
                    Text(
                        formatMinorUnits(0),
                        style = MaterialTheme.typography.displaySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.4f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                },
                prefix = { Text("$ ", style = MaterialTheme.typography.displaySmall) },
                visualTransformation = AmountVisualTransformation,
                singleLine = true,
                textStyle = MaterialTheme.typography.displaySmall.copy(textAlign = TextAlign.Center),
                isError = amountText.isNotBlank() && !amountValid,
                supportingText = if (amountText.isNotBlank() && !amountValid) {
                    {
                        Text(
                            stringResource(Res.string.quick_error_amount),
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center,
                        )
                    }
                } else {
                    null
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Decimal,
                    imeAction = ImeAction.Next,
                ),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    errorContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    errorIndicatorColor = Color.Transparent,
                    focusedTextColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    unfocusedTextColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    cursorColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
                    .focusRequester(amountFocus),
            )

            SheetField(
                label = fromLabel,
                value = fromId?.let { paths[it] },
                placeholder = chooseLabel,
                onClick = { picking = SheetSide.From },
            )
            Spacer(Modifier.padding(top = 10.dp))
            SheetField(
                label = toLabel,
                value = toId?.let { paths[it] },
                placeholder = chooseLabel,
                onClick = { picking = SheetSide.To },
            )
            Spacer(Modifier.padding(top = 10.dp))
            TextField(
                value = description,
                onValueChange = { description = it },
                label = { Text(stringResource(Res.string.quick_description_label)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { record() }),
                shape = RoundedCornerShape(16.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.background,
                    unfocusedContainerColor = MaterialTheme.colorScheme.background,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        error?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
        Button(
            onClick = { record() },
            enabled = canRecord,
            shape = RoundedCornerShape(28.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.inverseSurface,
                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .heightIn(min = 56.dp),
        ) {
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Text(stringResource(Res.string.quick_record))
            }
        }
    }

    picking?.let { field ->
        val pickerType = when (currentKind) {
            TxnKind.Expense -> if (field == SheetSide.From) AccountType.Asset else AccountType.Expense
            TxnKind.Income -> if (field == SheetSide.From) AccountType.Income else AccountType.Asset
            TxnKind.Transfer -> AccountType.Asset
        }
        val exclude = when {
            currentKind != TxnKind.Transfer -> emptySet()
            field == SheetSide.From -> listOfNotNull(toId).toSet()
            else -> listOfNotNull(fromId).toSet()
        }
        val isCategory = pickerType == AccountType.Expense
        AccountPickerSheet(
            tree = tree.filter { it.account.type == pickerType },
            title = if (field == SheetSide.From) fromLabel else toLabel,
            exclude = exclude,
            createLabel = if (isCategory) newCategoryLabel else null,
            onCreate = if (isCategory) {
                {
                    picking = null
                    creatingCategory = true
                }
            } else {
                null
            },
            onDismiss = { picking = null },
        ) { picked ->
            if (field == SheetSide.From) fromId = picked.account.id else toId = picked.account.id
            picking = null
        }
    }

    if (creatingCategory) {
        CreateAccountDialog(
            title = newCategoryLabel,
            type = AccountType.Expense,
            accounts = accounts,
            onDismiss = { creatingCategory = false },
            onError = { error = it },
            onCreated = { id ->
                toId = id
                reloadTree()
            },
        )
    }
}

/** Gasto / Ingreso / Traspaso as three pills on the mint. */
@Composable
private fun KindSwitcher(current: TxnKind, onSelect: (TxnKind) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        TxnKind.entries.forEach { kind ->
            val selected = kind == current
            Surface(
                color = if (selected) {
                    MaterialTheme.colorScheme.inverseSurface
                } else {
                    MaterialTheme.colorScheme.background
                },
                contentColor = if (selected) {
                    MaterialTheme.colorScheme.inverseOnSurface
                } else {
                    MaterialTheme.colorScheme.inverseSurface
                },
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.weight(1f),
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .clickable { onSelect(kind) }
                        .heightIn(min = 40.dp)
                        .fillMaxWidth(),
                ) {
                    Text(sheetKindTitle(kind), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

/** A tappable row that opens a picker: label above, chosen path below. */
@Composable
private fun SheetField(
    label: String,
    value: String?,
    placeholder: String,
    onClick: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.inverseSurface,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.secondary,
                )
                Text(
                    value ?: placeholder,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (value == null) {
                        MaterialTheme.colorScheme.secondary
                    } else {
                        MaterialTheme.colorScheme.inverseSurface
                    },
                )
            }
            Icon(Icons.Filled.ChevronRight, contentDescription = null)
        }
    }
}

@Composable
private fun sheetKindTitle(kind: TxnKind): String = stringResource(
    when (kind) {
        TxnKind.Expense -> Res.string.quick_expense_title
        TxnKind.Income -> Res.string.quick_income_title
        TxnKind.Transfer -> Res.string.quick_transfer_title
    },
)
