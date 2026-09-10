package ar.fausto.weil

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Create/edit a balanced transaction. Ledger-style: one posting may leave its
 * amount blank ("auto"), absorbing the per-commodity residual; Save is
 * enabled only when every commodity balances to zero.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionEditScreen(
    ledger: TransactionsRepository,
    accounts: AccountsRepository,
    editId: String?,
    onSaved: () -> Unit,
    onNavigateBack: () -> Unit,
) {
    var dateText by remember { mutableStateOf(todayInput()) }
    var payee by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var drafts by remember {
        mutableStateOf(listOf(DraftPosting(null, ""), DraftPosting(null, "")))
    }
    var pickingFor by remember { mutableStateOf<Int?>(null) }
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
                val stored = ledger.page(limit = Int.MAX_VALUE)
                    .firstOrNull { it.id == editId } ?: return@LaunchedEffect
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
                                        ledger.delete(editId)
                                        onSaved()
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
                value = dateText,
                onValueChange = { dateText = it },
                label = { Text("Date (YYYY-MM-DD)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = payee,
                onValueChange = { payee = it },
                label = { Text("Payee / description") },
                singleLine = true,
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

            val residuals = residualsOf(drafts)
            if (residuals.isNotEmpty()) {
                Column {
                    residuals.forEach { (commodity, residual) ->
                        Text(
                            if (residual == 0L) {
                                "$commodity ✓"
                            } else {
                                "$commodity off by ${formatMinorUnits(residual)}"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (residual == 0L) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                        )
                    }
                }
            }

            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }

            Button(
                onClick = {
                    val date = parseDateInput(dateText)
                    if (date == null) {
                        error = "invalid date: $dateText"
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
                        } finally {
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

    pickingFor?.let { index ->
        AccountPickerDialog(
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
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onAccount, modifier = Modifier.weight(1.6f)) {
            Text(
                paths[draft.accountId] ?: "Choose account ${index + 1}",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        OutlinedTextField(
            value = draft.amountText,
            onValueChange = onAmount,
            label = { Text(if (draft.amountText.isBlank()) "auto" else "Amount") },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            value = draft.commodity,
            onValueChange = onCommodity,
            label = { Text("Ccy") },
            singleLine = true,
            modifier = Modifier.width(72.dp),
        )
        if (canRemove) {
            IconButton(onClick = onRemove) {
                Icon(Icons.Filled.Close, contentDescription = "Remove posting")
            }
        } else {
            Spacer(Modifier.width(48.dp))
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        QUICK_COMMODITIES.forEach { chip ->
            TextButton(onClick = { onCommodity(chip) }) {
                Text(
                    chip,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (draft.commodity.uppercase() == chip) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}
