@file:OptIn(ExperimentalMaterial3Api::class)

package ar.fausto.weil

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
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
    var payee by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var drafts by remember {
        mutableStateOf(listOf(DraftPosting(null, ""), DraftPosting(prefillAccountId, "")))
    }
    var pickingFor by remember { mutableStateOf<Int?>(null) }
    var pickingDate by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var paths by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var accountTree by remember { mutableStateOf<List<AccountNode>>(emptyList()) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        paths = accountPaths(accounts)
        accountTree = accounts.tree()
    }

    LaunchedEffect(editId) {
        if (editId != null) {
            try {
                val stored = ledger.get(editId) ?: return@LaunchedEffect
                dateText = dateInputOf(stored.date)
                payee = stored.payee
                note = stored.note.orEmpty()
                drafts = stored.postings.map {
                    DraftPosting(it.accountId, formatMinorUnits(it.amountMinor), it.commodity)
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
                title = { Text(if (editId == null) "New transaction" else "Edit transaction") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
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
                                        Feedback.undoable("Transaction deleted") {
                                            ledger.add(
                                                stored?.date ?: epochMillis(),
                                                stored?.payee.orEmpty().ifBlank { "(deleted)" },
                                                stored?.note,
                                                draftsBackup,
                                            )
                                        }
                                    } catch (e: Throwable) {
                                        if (e is kotlinx.coroutines.CancellationException) throw e
                                        error = e.message ?: e.toString()
                                        busy = false
                                    }
                                }
                            },
                        ) { Text("Delete") }
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
                                        "$commodity ✓"
                                    } else {
                                        "$commodity off by ${formatMinorUnits(residual)}"
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
                            val date = parseDateInput(dateText)
                            if (date == null) {
                                error = "Invalid date: $dateText"
                                return@Button
                            }
                            busy = true
                            error = null
                            scope.launch {
                                try {
                                    if (editId == null) {
                                        ledger.add(date, payee, note.ifBlank { null }, drafts)
                                    } else {
                                        ledger.update(editId, date, payee, note.ifBlank { null }, drafts)
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
                        Text(if (editId == null) "Record" else "Save")
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
                label = { Text("Payee / description") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = dateText,
                onValueChange = { dateText = it },
                label = { Text("Date (YYYY-MM-DD)") },
                singleLine = true,
                trailingIcon = {
                    TextButton(onClick = { pickingDate = true }) { Text("Pick") }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("Note (optional)") },
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
                Text("+ add split")
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
                ) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { pickingDate = false }) { Text("Cancel") }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }

    pickingFor?.let { index ->
        AccountPickerSheet(
            tree = accountTree,
            title = "Choose account",
            exclude = emptySet(),
            onDismiss = { pickingFor = null },
        ) { picked ->
            drafts = drafts.copyAt(index) { copy(accountId = picked.account.id) }
            pickingFor = null
        }
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
            OutlinedButton(
                onClick = onAccount,
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    paths[draft.accountId] ?: "Choose account ${index + 1}",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (draft.accountId != null) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            if (canRemove) {
                IconButton(onClick = onRemove) {
                    Icon(Icons.Filled.Close, contentDescription = "Remove posting")
                }
            }
        }
        // Row B: amount + commodity chips, numeric keyboard for the amount.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 4.dp),
        ) {
            OutlinedTextField(
                value = draft.amountText,
                onValueChange = onAmount,
                label = { Text(if (draft.amountText.isBlank()) "Amount (auto)" else "Amount") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f),
            )
            QUICK_COMMODITIES.forEach { chip ->
                FilterChip(
                    selected = draft.commodity.uppercase() == chip,
                    onClick = { onCommodity(chip) },
                    label = { Text(chip) },
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
        }
    }
}
