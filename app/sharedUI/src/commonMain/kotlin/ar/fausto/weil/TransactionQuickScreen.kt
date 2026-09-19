@file:OptIn(ExperimentalMaterial3Api::class)

package ar.fausto.weil

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import weil.app.sharedui.generated.resources.Res
import weil.app.sharedui.generated.resources.action_back
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

private enum class QuickField { From, To }

/**
 * Simplified transaction entry behind the Home FAB. One amount becomes two
 * postings with kind-driven signs:
 *  - Gasto:    asset −X, expense +X  (money leaves the asset)
 *  - Ingreso:  income −X, asset +X   (money enters the asset)
 *  - Traspaso: asset −X, asset +X
 * The kind starts at [kind] and can be switched on screen (picks reset to the
 * new kind's defaults; amount and description are kept). Commodity is ARS and
 * the date is now; backdating or other commodities go through the full
 * editor. Expense categories can be created inline.
 */
@Composable
fun TransactionQuickScreen(
    ledger: TransactionsRepository,
    accounts: AccountsRepository,
    settings: SettingsRepository,
    kind: TxnKind,
    onSaved: () -> Unit,
    onNavigateBack: () -> Unit,
) {
    var currentKind by remember { mutableStateOf(kind) }
    val kindLabel = kindTitle(currentKind)
    val categoryLabel = stringResource(Res.string.quick_category_label)
    val accountLabel = stringResource(Res.string.quick_from_label)
    val fromLabel = when (currentKind) {
        TxnKind.Expense -> accountLabel
        TxnKind.Income -> stringResource(Res.string.quick_source_label)
        TxnKind.Transfer -> stringResource(Res.string.quick_transfer_from_label)
    }
    val toLabel = when (currentKind) {
        TxnKind.Expense -> categoryLabel
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
    var picking by remember { mutableStateOf<QuickField?>(null) }
    var creatingCategory by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val amountFocus = remember { FocusRequester() }

    // The asset leg is what holds money, so it decides the currency: a
    // charge on a dollar account is in dollars without the user saying so.
    // An account that declares nothing keeps the old default.
    val assetId = when (currentKind) {
        TxnKind.Expense, TxnKind.Transfer -> fromId
        TxnKind.Income -> toId
    }
    val commodity = remember(tree, assetId) {
        tree.flatMap { it.selfAndDescendants }
            .firstOrNull { it.account.id == assetId }
            ?.account?.commodity
            ?: Money.DEFAULT_COMMODITY
    }
    val money = Money.parse(amountText.trim(), commodity)
    val amountValid = money != null && money.minorUnits > 0
    val canRecord = !busy && amountValid && fromId != null && toId != null

    suspend fun reloadTree() {
        defaults = settings.defaultAccounts()
        tree = accounts.tree()
        paths = disambiguatedPaths(tree)
    }

    LaunchedEffect(Unit) {
        amountFocus.requestFocus()
        reloadTree()
    }

    // Defaults for the active kind once the tree arrives: the account the
    // user marked as default for each type (long-press → "Usar como
    // predeterminada"), falling back to the seeded "Otros"/first account —
    // the point of this screen is amount → record, and "which of my accounts
    // paid" is the pick the user most often wouldn't change anyway. Wrong
    // guesses are one tap to fix. Re-runs keep any existing pick.
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
                    // Both legs are assets, so the default can only fill one;
                    // the other is any other asset account.
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
        // Carry the asset side across kinds so a switch doesn't lose the
        // account the user already chose.
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
                    DraftPosting(from, formatMinorUnits(-amount.minorUnits), amount.commodity),
                    DraftPosting(to, formatMinorUnits(amount.minorUnits), amount.commodity),
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

    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            TopAppBar(
                title = { Text(kindLabel) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(Res.string.action_back))
                    }
                },
            )
        },
        bottomBar = {
            // Sticky above the keyboard, like the full editor's record bar.
            Surface(tonalElevation = 3.dp) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    error?.let {
                        Text(
                            it,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                    }
                    Button(
                        onClick = { record() },
                        enabled = canRecord,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp),
                    ) {
                        if (busy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Text(stringResource(Res.string.quick_record))
                        }
                    }
                }
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                TxnKind.entries.forEachIndexed { index, entry ->
                    SegmentedButton(
                        selected = entry == currentKind,
                        onClick = { switchKind(entry) },
                        shape = SegmentedButtonDefaults.itemShape(index, TxnKind.entries.size),
                        label = { Text(kindTitle(entry)) },
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                value = amountText,
                onValueChange = { amountText = sanitizeAmountInput(it, allowNegative = false) },
                label = { Text(stringResource(Res.string.quick_amount_label)) },
                prefix = { Text("$commodity ") },
                visualTransformation = AmountVisualTransformation,
                singleLine = true,
                textStyle = MaterialTheme.typography.headlineMedium.copy(textAlign = TextAlign.End),
                isError = amountText.isNotBlank() && !amountValid,
                supportingText = if (amountText.isNotBlank() && !amountValid) {
                    { Text(stringResource(Res.string.quick_error_amount)) }
                } else {
                    null
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Decimal,
                    imeAction = ImeAction.Next,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(amountFocus),
            )
            PickerField(
                label = fromLabel,
                value = fromId?.let { paths[it] },
                placeholder = chooseLabel,
                onClick = { picking = QuickField.From },
            )
            PickerField(
                label = toLabel,
                value = toId?.let { paths[it] },
                placeholder = chooseLabel,
                onClick = { picking = QuickField.To },
            )
            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text(stringResource(Res.string.quick_description_label)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { record() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    picking?.let { field ->
        val pickerType = when (currentKind) {
            TxnKind.Expense -> if (field == QuickField.From) AccountType.Asset else AccountType.Expense
            TxnKind.Income -> if (field == QuickField.From) AccountType.Income else AccountType.Asset
            TxnKind.Transfer -> AccountType.Asset
        }
        val exclude = when {
            currentKind != TxnKind.Transfer -> emptySet()
            field == QuickField.From -> listOfNotNull(toId).toSet()
            else -> listOfNotNull(fromId).toSet()
        }
        val isCategory = pickerType == AccountType.Expense
        // A transfer here carries one amount, so both legs must be the same
        // currency: offering a dollar account opposite a peso one would only
        // produce a transaction this screen cannot express (the full editor
        // does FX, with two amounts).
        val transferCommodity = if (currentKind == TxnKind.Transfer) {
            val other = if (field == QuickField.From) toId else fromId
            tree.flatMap { it.selfAndDescendants }
                .firstOrNull { it.account.id == other }
                ?.account?.commodity
        } else {
            null
        }
        AccountPickerSheet(
            tree = tree.filter { it.account.type == pickerType },
            title = if (field == QuickField.From) fromLabel else toLabel,
            exclude = exclude,
            commodityFilter = transferCommodity,
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
            if (field == QuickField.From) fromId = picked.account.id else toId = picked.account.id
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

@Composable
private fun kindTitle(kind: TxnKind): String = stringResource(
    when (kind) {
        TxnKind.Expense -> Res.string.quick_expense_title
        TxnKind.Income -> Res.string.quick_income_title
        TxnKind.Transfer -> Res.string.quick_transfer_title
    },
)

