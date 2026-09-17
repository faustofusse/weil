@file:OptIn(ExperimentalMaterial3Api::class)

package ar.fausto.weil

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import weil.app.sharedui.generated.resources.Res
import weil.app.sharedui.generated.resources.account_add_title
import weil.app.sharedui.generated.resources.action_back
import weil.app.sharedui.generated.resources.action_cancel
import weil.app.sharedui.generated.resources.action_delete
import weil.app.sharedui.generated.resources.action_ok
import weil.app.sharedui.generated.resources.action_save
import weil.app.sharedui.generated.resources.action_undo
import weil.app.sharedui.generated.resources.editor_add_split
import weil.app.sharedui.generated.resources.editor_amount
import weil.app.sharedui.generated.resources.editor_amount_auto
import weil.app.sharedui.generated.resources.editor_balanced
import weil.app.sharedui.generated.resources.editor_choose_account
import weil.app.sharedui.generated.resources.editor_choose_account_title
import weil.app.sharedui.generated.resources.editor_commodity_label
import weil.app.sharedui.generated.resources.editor_date_label
import weil.app.sharedui.generated.resources.editor_date_pick
import weil.app.sharedui.generated.resources.editor_deleted_payee_fallback
import weil.app.sharedui.generated.resources.editor_edit_title
import weil.app.sharedui.generated.resources.editor_invalid_date
import weil.app.sharedui.generated.resources.editor_invalid_time
import weil.app.sharedui.generated.resources.editor_new_title
import weil.app.sharedui.generated.resources.editor_note_label
import weil.app.sharedui.generated.resources.editor_off_by
import weil.app.sharedui.generated.resources.editor_payee_label
import weil.app.sharedui.generated.resources.editor_record
import weil.app.sharedui.generated.resources.editor_time_label
import weil.app.sharedui.generated.resources.editor_time_none
import weil.app.sharedui.generated.resources.editor_time_pick
import weil.app.sharedui.generated.resources.editor_remove_posting
import weil.app.sharedui.generated.resources.editor_transaction_deleted

/**
 * Create/edit a balanced transaction. Ledger-style: one posting may leave its
 * amount blank ("auto"), absorbing the per-commodity residual; Record is
 * enabled only when every commodity balances to zero.
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

    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (editId == null) Res.string.editor_new_title else Res.string.editor_edit_title,
                        ),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(Res.string.action_back))
                    }
                },
                actions = {
                    if (editId != null) {
                        TextButton(
                            onClick = {
                                busy = true
                                scope.launch {
                                    try {
                                        val stored = ledger.get(editId)
                                        val draftsBackup = stored?.postings?.map {
                                            DraftPosting(
                                                it.accountId,
                                                formatMinorUnits(it.amountMinor),
                                                it.commodity,
                                            )
                                        }.orEmpty()
                                        ledger.delete(editId)
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
                            },
                        ) { Text(stringResource(Res.string.action_delete)) }
                    }
                },
            )
        },
        bottomBar = {
            // Sticky footer: the record button stays visible over the
            // keyboard, so validation feedback never scrolls away.
            Surface(tonalElevation = 3.dp) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        // Keep clear of the gesture/system nav bar when the
                        // keyboard is closed.
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    val residuals = residualsOf(drafts)
                    if (residuals.isNotEmpty()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            residuals.forEach { (commodity, residual) ->
                                Text(
                                    if (residual == 0L) {
                                        stringResource(Res.string.editor_balanced, commodity)
                                    } else {
                                        stringResource(
                                            Res.string.editor_off_by,
                                            commodity,
                                            formatMinorUnits(residual),
                                        )
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (residual == 0L) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.error
                                    },
                                )
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                    error?.let {
                        Text(
                            it,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                    Button(
                        onClick = {
                            // An empty time is a deliberate state, not an
                            // error: the transaction is dated to the day and
                            // stored at local midnight with timeKnown = false.
                            val timeKnown = timeText.isNotBlank()
                            val date = if (timeKnown) {
                                parseDateTimeInput(dateText, timeText)
                            } else {
                                parseDateInput(dateText)
                            }
                            if (date == null) {
                                error = if (parseDateInput(dateText) == null) invalidDateMessage else invalidTimeMessage
                                return@Button
                            }
                            busy = true
                            error = null
                            scope.launch {
                                try {
                                    if (editId == null) {
                                        ledger.add(
                                            date,
                                            payee,
                                            note.ifBlank { null },
                                            drafts,
                                            timeKnown = timeKnown,
                                        )
                                    } else {
                                        ledger.update(
                                            editId,
                                            date,
                                            payee,
                                            note.ifBlank { null },
                                            drafts,
                                            timeKnown = timeKnown,
                                        )
                                    }
                                    onSaved()
                                } catch (e: Throwable) {
                                    if (e is kotlinx.coroutines.CancellationException) throw e
                                    error = e.message ?: e.toString()
                                    busy = false
                                }
                            }
                        },
                        enabled = !busy && payee.isNotBlank() && isValidTransaction(drafts),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            stringResource(
                                if (editId == null) Res.string.editor_record else Res.string.action_save,
                            ),
                        )
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
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = payee,
                onValueChange = { payee = it },
                label = { Text(stringResource(Res.string.editor_payee_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedTextField(
                    value = dateText,
                    onValueChange = { dateText = it },
                    label = { Text(stringResource(Res.string.editor_date_label)) },
                    singleLine = true,
                    trailingIcon = {
                        TextButton(onClick = { pickingDate = true }) {
                            Text(stringResource(Res.string.editor_date_pick))
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = timeText,
                    onValueChange = { timeText = it },
                    label = { Text(stringResource(Res.string.editor_time_label)) },
                    placeholder = { Text(stringResource(Res.string.editor_time_none)) },
                    singleLine = true,
                    trailingIcon = {
                        TextButton(onClick = { pickingTime = true }) {
                            Text(stringResource(Res.string.editor_time_pick))
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
            }
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text(stringResource(Res.string.editor_note_label)) },
                modifier = Modifier.fillMaxWidth(),
            )

            drafts.forEachIndexed { index, draft ->
                PostingRow(
                    index = index,
                    draft = draft,
                    paths = paths,
                    onAccount = { pickingFor = index },
                    onAmount = { amount -> drafts = drafts.copyAt(index) { copy(amountText = amount) } },
                    onCommodity = { ccy -> drafts = drafts.copyAt(index) { copy(commodity = ccy) } },
                    onRemove = {
                        if (drafts.size > 2) drafts = drafts.minusAt(index)
                    },
                    canRemove = drafts.size > 2,
                )
            }

            TextButton(onClick = { drafts = drafts + DraftPosting(null, "") }) {
                Text(stringResource(Res.string.editor_add_split))
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
                TextButton(onClick = { pickingTime = false }) { Text(stringResource(Res.string.action_cancel)) }
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
            drafts = drafts.copyAt(index) { copy(accountId = picked.account.id) }
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

@Composable
private fun PostingRow(
    index: Int,
    draft: DraftPosting,
    paths: Map<String, String>,
    onAccount: () -> Unit,
    onAmount: (String) -> Unit,
    onCommodity: (String) -> Unit,
    onRemove: () -> Unit,
    canRemove: Boolean,
) {
    Column {
        // Row A: the account selector takes the whole line — colon paths
        // finally fit.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.weight(1f)) {
                OutlinedTextField(
                    value = paths[draft.accountId] ?: "",
                    onValueChange = {},
                    readOnly = true,
                    label = {
                        Text(stringResource(Res.string.editor_choose_account, index + 1))
                    },
                    placeholder = {
                        Text(
                            paths[draft.accountId]
                                ?: stringResource(Res.string.editor_choose_account_title),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onAccount() },
                )
            }
            if (canRemove) {
                IconButton(onClick = onRemove) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = stringResource(Res.string.editor_remove_posting),
                    )
                }
            }
        }
        // Row B: amount + commodity dropdown, numeric keyboard for the amount.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 4.dp),
        ) {
            OutlinedTextField(
                value = draft.amountText,
                onValueChange = { onAmount(sanitizeAmountInput(it)) },
                label = {
                    Text(
                        stringResource(
                            if (draft.amountText.isBlank()) {
                                Res.string.editor_amount_auto
                            } else {
                                Res.string.editor_amount
                            },
                        ),
                    )
                },
                singleLine = true,
                visualTransformation = AmountVisualTransformation,
                // The iOS decimal pad has no minus key, so signing a posting
                // needs an explicit affordance.
                trailingIcon = {
                    TextButton(onClick = { onAmount(toggleAmountSign(draft.amountText)) }) {
                        Text(
                            "±",
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f),
            )
            var commodityExpanded by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(
                expanded = commodityExpanded,
                onExpandedChange = { commodityExpanded = it },
                modifier = Modifier
                    .padding(start = 6.dp)
                    .width(110.dp),
            ) {
                OutlinedTextField(
                    value = draft.commodity.uppercase(),
                    onValueChange = {},
                    readOnly = true,
                    singleLine = true,
                    label = { Text(stringResource(Res.string.editor_commodity_label)) },
                    trailingIcon = {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = commodityExpanded)
                    },
                    modifier = Modifier.menuAnchor().fillMaxWidth(),
                )
                ExposedDropdownMenu(
                    expanded = commodityExpanded,
                    onDismissRequest = { commodityExpanded = false },
                ) {
                    QUICK_COMMODITIES.forEach { chip ->
                        DropdownMenuItem(
                            text = { Text(chip) },
                            onClick = {
                                onCommodity(chip)
                                commodityExpanded = false
                            },
                        )
                    }
                }
            }
        }
    }
}
