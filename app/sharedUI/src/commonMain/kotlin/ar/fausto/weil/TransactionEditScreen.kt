@file:OptIn(ExperimentalMaterial3Api::class)

package ar.fausto.weil

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import weil.app.sharedui.generated.resources.Res
import weil.app.sharedui.generated.resources.account_add_title
import weil.app.sharedui.generated.resources.account_commodity_mismatch
import weil.app.sharedui.generated.resources.action_cancel
import weil.app.sharedui.generated.resources.action_delete
import weil.app.sharedui.generated.resources.action_ok
import weil.app.sharedui.generated.resources.action_save
import weil.app.sharedui.generated.resources.action_undo
import weil.app.sharedui.generated.resources.editor_add_posting
import weil.app.sharedui.generated.resources.editor_amount_auto_short
import weil.app.sharedui.generated.resources.editor_balanced
import weil.app.sharedui.generated.resources.editor_choose_account_title
import weil.app.sharedui.generated.resources.editor_date_short
import weil.app.sharedui.generated.resources.editor_deleted_payee_fallback
import weil.app.sharedui.generated.resources.editor_description
import weil.app.sharedui.generated.resources.editor_detail_optional
import weil.app.sharedui.generated.resources.editor_edit_title
import weil.app.sharedui.generated.resources.editor_invalid_date
import weil.app.sharedui.generated.resources.editor_invalid_time
import weil.app.sharedui.generated.resources.editor_new_title
import weil.app.sharedui.generated.resources.editor_off_by
import weil.app.sharedui.generated.resources.editor_postings_title
import weil.app.sharedui.generated.resources.editor_record
import weil.app.sharedui.generated.resources.editor_remove_posting
import weil.app.sharedui.generated.resources.editor_time_none
import weil.app.sharedui.generated.resources.editor_time_short
import weil.app.sharedui.generated.resources.editor_transaction_deleted

/**
 * Create/edit a balanced transaction. Ledger-style: one posting may leave its
 * amount blank ("auto"), absorbing the per-commodity residual; Record is
 * enabled only when every commodity balances to zero.
 *
 * Dressed like the create panel it is the "Ver más" of — same mint page, same
 * translucent slabs, same money row — because it *is* the same act with the
 * lid off: a date, a note, and as many postings as the movement really had.
 * The bookkeeping (residuals, auto-amount, commodity per posting) is what the
 * design can't show and is exactly what this screen exists for.
 */
@Composable
fun TransactionEditScreen(
    ledger: TransactionsRepository,
    accounts: AccountsRepository,
    editId: String?,
    prefillAccountId: String? = null,
    onSaved: () -> Unit,
    onNavigateBack: () -> Unit,
) {
    var dateText by remember { mutableStateOf(todayInput()) }
    var timeText by remember { mutableStateOf(nowTimeInput()) }
    var payee by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var drafts by remember {
        mutableStateOf(listOf(DraftPosting(null, ""), DraftPosting(prefillAccountId, "")))
    }
    var pickingFor by remember { mutableStateOf<Int?>(null) }
    var creatingAccountFor by remember { mutableStateOf<Int?>(null) }
    var pickingDate by remember { mutableStateOf(false) }
    var pickingTime by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var paths by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var accountTree by remember { mutableStateOf<List<AccountNode>>(emptyList()) }
    val scope = rememberCoroutineScope()
    val undoLabel = stringResource(Res.string.action_undo)
    val deletedMessage = stringResource(Res.string.editor_transaction_deleted)
    val deletedPayee = stringResource(Res.string.editor_deleted_payee_fallback)
    val invalidDateMessage = stringResource(Res.string.editor_invalid_date, dateText)
    val invalidTimeMessage = stringResource(Res.string.editor_invalid_time, timeText)

    suspend fun reloadTree() {
        paths = accountPaths(accounts)
        accountTree = accounts.tree()
    }

    LaunchedEffect(Unit) { reloadTree() }

    LaunchedEffect(editId) {
        if (editId != null) {
            try {
                val stored = ledger.get(editId) ?: return@LaunchedEffect
                dateText = dateInputOf(stored.date)
                // A row imported from a statement knows its day only; leaving
                // the field empty says so, and typing a time is what promotes
                // it to a real moment.
                timeText = if (stored.timeKnown) timeInputOf(stored.date) else ""
                payee = stored.payee
                note = stored.note.orEmpty()
                drafts = stored.postings.map {
                    DraftPosting(it.accountId, rawAmountText(it.amountMinor), it.commodity)
                }
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                error = e.message ?: e.toString()
            }
        }
    }

    fun deleteTransaction(id: String) {
        busy = true
        scope.launch {
            try {
                val stored = ledger.get(id)
                val draftsBackup = stored?.postings?.map {
                    DraftPosting(it.accountId, formatMinorUnits(it.amountMinor), it.commodity)
                }.orEmpty()
                ledger.delete(id)
                onSaved()
                Feedback.undoable(deletedMessage, undoLabel) {
                    ledger.add(
                        stored?.date ?: epochMillis(),
                        stored?.payee.orEmpty().ifBlank { deletedPayee },
                        stored?.note,
                        draftsBackup,
                        timeKnown = stored?.timeKnown ?: true,
                    )
                }
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                error = e.message ?: e.toString()
                busy = false
            }
        }
    }

    fun record() {
        // An empty time is a deliberate state, not an error: the transaction
        // is dated to the day and stored at local midnight with
        // timeKnown = false.
        val timeKnown = timeText.isNotBlank()
        val date = if (timeKnown) parseDateTimeInput(dateText, timeText) else parseDateInput(dateText)
        if (date == null) {
            error = if (parseDateInput(dateText) == null) invalidDateMessage else invalidTimeMessage
            return
        }
        busy = true
        error = null
        scope.launch {
            try {
                if (editId == null) {
                    ledger.add(date, payee, note.ifBlank { null }, drafts, timeKnown = timeKnown)
                } else {
                    ledger.update(editId, date, payee, note.ifBlank { null }, drafts, timeKnown = timeKnown)
                }
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
        // The whole page is the mint the create panel is made of: this screen
        // is that panel with the lid off, not a different place.
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.inverseSurface,
        topBar = {
            AppTopBar(
                title = stringResource(
                    if (editId == null) Res.string.editor_new_title else Res.string.editor_edit_title,
                ),
                onNavigateBack = onNavigateBack,
                actions = {
                    if (editId != null) {
                        IconButton(onClick = { deleteTransaction(editId) }) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = stringResource(Res.string.action_delete),
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            // Sticky footer: the record button stays visible over the
            // keyboard, so validation feedback never scrolls away.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            ) {
                val residuals = residualsOf(drafts)
                // Only the commodities that are *out* get a line: "ARS ✓" on
                // every keystroke of a balanced entry is noise, and the
                // enabled button already says the same thing.
                val unbalanced = residuals.filterValues { it != 0L }
                if (unbalanced.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        unbalanced.forEach { (commodity, residual) ->
                            Text(
                                stringResource(
                                    Res.string.editor_off_by,
                                    commodity,
                                    formatMinorUnits(residual),
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                } else if (residuals.size > 1) {
                    // Several currencies, all balanced: worth saying, because
                    // a multi-commodity entry is the one case where "it adds
                    // up" isn't obvious by looking.
                    val balancedLine = residuals.keys.map {
                        stringResource(Res.string.editor_balanced, it)
                    }.joinToString("  ")
                    Text(
                        balancedLine,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.inverseSurface,
                    )
                    Spacer(Modifier.height(6.dp))
                }
                error?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(6.dp))
                }
                Button(
                    onClick = { record() },
                    enabled = !busy && payee.isNotBlank() && isValidTransaction(drafts),
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.inverseSurface,
                        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                    ),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                ) {
                    Text(
                        stringResource(
                            if (editId == null) Res.string.editor_record else Res.string.action_save,
                        ),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SlabTextField(
                value = payee,
                onValueChange = { payee = it },
                placeholder = stringResource(Res.string.editor_description),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                // Date and time are picked, not typed: the text form
                // ("AAAA-MM-DD") was a parser's idea of a date field. The
                // state stays textual so an empty time can keep meaning
                // "day only".
                SlabValue(
                    label = stringResource(Res.string.editor_date_short),
                    value = dateText,
                    onClick = { pickingDate = true },
                    modifier = Modifier.weight(1f),
                )
                SlabValue(
                    label = stringResource(Res.string.editor_time_short),
                    value = timeText.ifBlank { stringResource(Res.string.editor_time_none) },
                    dim = timeText.isBlank(),
                    onClick = { pickingTime = true },
                    modifier = Modifier.weight(1f),
                )
            }
            SlabTextField(
                value = note,
                onValueChange = { note = it },
                placeholder = stringResource(Res.string.editor_detail_optional),
                singleLine = false,
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            ) {
                Text(
                    stringResource(Res.string.editor_postings_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.inverseSurface,
                    modifier = Modifier.weight(1f),
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fadeOnPress { drafts = drafts + DraftPosting(null, "") }
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                ) {
                    Text(
                        stringResource(Res.string.editor_add_posting),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.inverseSurface,
                    )
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            drafts.forEachIndexed { index, draft ->
                PostingSlab(
                    draft = draft,
                    path = paths[draft.accountId],
                    accountCommodity = accountTree.flatMap { it.selfAndDescendants }
                        .firstOrNull { it.account.id == draft.accountId }
                        ?.account?.commodity,
                    onAccount = { pickingFor = index },
                    onAmount = { amount -> drafts = drafts.copyAt(index) { copy(amountText = amount) } },
                    onCommodity = { ccy -> drafts = drafts.copyAt(index) { copy(commodity = ccy) } },
                    onRemove = { if (drafts.size > 2) drafts = drafts.minusAt(index) },
                    canRemove = drafts.size > 2,
                )
            }
        }
    }

    if (pickingDate) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = parseDateInput(dateText) ?: epochMillis(),
        )
        DatePickerDialog(
            onDismissRequest = { pickingDate = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        pickerState.selectedDateMillis?.let { dateText = dateInputOf(it) }
                        pickingDate = false
                    },
                ) { Text(stringResource(Res.string.action_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { pickingDate = false }) { Text(stringResource(Res.string.action_cancel)) }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }

    if (pickingTime) {
        // Empty field (a day-only row): the picker opens at now, the same
        // default a brand-new transaction gets.
        val (initialHour, initialMinute) = parseTimeInput(timeText)
            ?: parseTimeInput(nowTimeInput())
            ?: (0 to 0)
        val pickerState = rememberTimePickerState(
            initialHour = initialHour,
            initialMinute = initialMinute,
            is24Hour = true,
        )
        DatePickerDialog(
            onDismissRequest = { pickingTime = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        timeText = pickerState.hour.toString().padStart(2, '0') + ":" +
                            pickerState.minute.toString().padStart(2, '0')
                        pickingTime = false
                    },
                ) { Text(stringResource(Res.string.action_ok)) }
            },
            dismissButton = {
                // Clearing the time is a real choice ("this row only knows its
                // day"), so cancel doubles as it: the picker has nowhere else
                // to put an "unset".
                TextButton(
                    onClick = {
                        timeText = ""
                        pickingTime = false
                    },
                ) { Text(stringResource(Res.string.editor_time_none)) }
            },
        ) {
            TimePicker(state = pickerState)
        }
    }

    pickingFor?.let { index ->
        AccountPickerSheet(
            tree = accountTree,
            title = stringResource(Res.string.editor_choose_account_title),
            exclude = emptySet(),
            createLabel = stringResource(Res.string.account_add_title),
            onCreate = {
                pickingFor = null
                creatingAccountFor = index
            },
            onDismiss = { pickingFor = null },
        ) { picked ->
            drafts = drafts.copyAt(index) {
                // An account that declares a currency sets the row's; one
                // that doesn't leaves whatever was there, so picking an
                // account never silently rewrites an amount's meaning.
                copy(
                    accountId = picked.account.id,
                    commodity = picked.account.commodity ?: commodity,
                )
            }
            pickingFor = null
        }
    }

    creatingAccountFor?.let { index ->
        CreateAccountDialog(
            title = stringResource(Res.string.account_add_title),
            // The editor's picker isn't scoped to one type — any posting can
            // reference any account — so the dialog shows its own type picker
            // instead of assuming one.
            type = null,
            accounts = accounts,
            onDismiss = { creatingAccountFor = null },
            onError = { error = it },
            onCreated = { id ->
                drafts = drafts.copyAt(index) { copy(accountId = id) }
                reloadTree()
            },
        )
    }
}

private inline fun List<DraftPosting>.copyAt(
    index: Int,
    block: DraftPosting.() -> DraftPosting,
): List<DraftPosting> = toMutableList().also { it[index] = it[index].block() }

private fun List<DraftPosting>.minusAt(index: Int): List<DraftPosting> =
    toMutableList().also { it.removeAt(index) }

/** A free-text field with no chrome of its own: the slab is the field. */
@Composable
private fun SlabTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    singleLine: Boolean = true,
) {
    SheetRow {
        TextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = {
                Text(
                    placeholder,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.55f),
                )
            },
            textStyle = MaterialTheme.typography.bodyLarge,
            singleLine = singleLine,
            colors = slabFieldColors(),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        )
    }
}

/** A slab that shows a picked value and opens its picker when tapped. */
@Composable
private fun SlabValue(
    label: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    dim: Boolean = false,
) {
    androidx.compose.material3.Surface(
        color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.22f),
        contentColor = MaterialTheme.colorScheme.inverseSurface,
        shape = RoundedCornerShape(18.dp),
        modifier = modifier,
    ) {
        Column(
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .fadeOnPress(onClick)
                .heightIn(min = 64.dp)
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.7f),
            )
            Text(
                value,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = if (dim) 0.55f else 1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * One posting: account on the left, signed amount on the right — the same row
 * the create panel uses, because it is the same fact.
 *
 * What the panel doesn't have and this does: the sign (a movement's two legs
 * differ only by it), the commodity, and removal. Blank means "auto": this
 * leg absorbs whatever the others leave over.
 */
@Composable
private fun PostingSlab(
    draft: DraftPosting,
    path: String?,
    /**
     * The currency the row's account declares, if any. Only ever a warning:
     * an FX transfer is legitimately one transaction touching two
     * currencies, and postings recorded before the account declared
     * anything must stay editable.
     */
    accountCommodity: String?,
    onAccount: () -> Unit,
    onAmount: (String) -> Unit,
    onCommodity: (String) -> Unit,
    onRemove: () -> Unit,
    canRemove: Boolean,
) {
    val mismatch = accountCommodity != null && accountCommodity != draft.commodity.uppercase()
    Column {
        SheetRow {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fadeOnPress(onAccount)
                    .padding(start = 12.dp, end = 4.dp)
                    .weight(1f),
            ) {
                Icon(
                    Icons.Filled.ChevronRight,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp).rotate(90f),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    path ?: stringResource(Res.string.editor_choose_account_title),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.inverseSurface.copy(
                        alpha = if (path == null) 0.55f else 1f,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextField(
                value = draft.amountText,
                onValueChange = { onAmount(sanitizeAmountInput(it)) },
                placeholder = {
                    Text(
                        // Blank is the "auto" leg, so the placeholder says so
                        // rather than showing a zero that isn't there.
                        stringResource(Res.string.editor_amount_auto_short),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.45f),
                        textAlign = TextAlign.End,
                        modifier = Modifier.fillMaxWidth(),
                    )
                },
                visualTransformation = AmountWithSymbol,
                singleLine = true,
                textStyle = MaterialTheme.typography.titleMedium.copy(textAlign = TextAlign.End),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                colors = slabFieldColors(),
                modifier = Modifier.weight(1f),
            )
            // The iOS decimal pad has no minus key, so signing a posting
            // needs an explicit affordance.
            TextButton(
                onClick = { onAmount(toggleAmountSign(draft.amountText)) },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp),
            ) {
                Text(
                    "±",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.inverseSurface,
                )
            }
            if (canRemove) {
                IconButton(onClick = onRemove) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(Res.string.editor_remove_posting),
                        modifier = Modifier.size(18.dp),
                    )
                }
            } else {
                Spacer(Modifier.width(8.dp))
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 16.dp, top = 2.dp),
        ) {
            // Currency as a quiet row of codes, not a dropdown: there are
            // three of them in practice, and a menu to pick from three is a
            // menu too many.
            QUICK_COMMODITIES.forEach { chip ->
                val selected = chip.equals(draft.commodity, ignoreCase = true)
                Text(
                    chip,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    color = MaterialTheme.colorScheme.inverseSurface.copy(
                        alpha = if (selected) 1f else 0.5f,
                    ),
                    modifier = Modifier
                        .fadeOnPress { onCommodity(chip) }
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                )
            }
            if (mismatch) {
                Text(
                    stringResource(Res.string.account_commodity_mismatch, accountCommodity),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
        }
    }
}

/** Transparent field colors: the slab behind it is the only container. */
@Composable
private fun slabFieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = Color.Transparent,
    unfocusedContainerColor = Color.Transparent,
    focusedIndicatorColor = Color.Transparent,
    unfocusedIndicatorColor = Color.Transparent,
    focusedTextColor = MaterialTheme.colorScheme.inverseSurface,
    unfocusedTextColor = MaterialTheme.colorScheme.inverseSurface,
    cursorColor = MaterialTheme.colorScheme.inverseSurface,
)
