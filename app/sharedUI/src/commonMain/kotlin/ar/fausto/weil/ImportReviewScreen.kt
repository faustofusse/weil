@file:OptIn(ExperimentalMaterial3Api::class)

package ar.fausto.weil

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import weil.app.sharedui.generated.resources.Res
import weil.app.sharedui.generated.resources.action_back
import weil.app.sharedui.generated.resources.action_undo
import weil.app.sharedui.generated.resources.import_account_default_label
import weil.app.sharedui.generated.resources.import_account_label
import weil.app.sharedui.generated.resources.import_account_pick
import weil.app.sharedui.generated.resources.import_amount_label
import weil.app.sharedui.generated.resources.import_analyzing
import weil.app.sharedui.generated.resources.import_analyzing_hint
import weil.app.sharedui.generated.resources.import_category_label
import weil.app.sharedui.generated.resources.import_category_pick
import weil.app.sharedui.generated.resources.import_create
import weil.app.sharedui.generated.resources.import_created
import weil.app.sharedui.generated.resources.import_created_one
import weil.app.sharedui.generated.resources.import_empty
import weil.app.sharedui.generated.resources.import_failed
import weil.app.sharedui.generated.resources.import_found
import weil.app.sharedui.generated.resources.import_found_one
import weil.app.sharedui.generated.resources.import_payee_label
import weil.app.sharedui.generated.resources.import_retry
import weil.app.sharedui.generated.resources.import_source_pdf
import weil.app.sharedui.generated.resources.import_source_image
import weil.app.sharedui.generated.resources.import_title

/**
 * Editable copy of an [ImportCandidate]: the user reviews, tweaks and either
 * keeps or drops each row before anything reaches the ledger.
 */
private class CandidateDraft(candidate: ImportCandidate) {
    val date = candidate.date
    val direction = candidate.direction
    val commodity = candidate.commodity
    var include by mutableStateOf(true)
    var expanded by mutableStateOf(false)
    var payee by mutableStateOf(candidate.payee)
    var amountText by mutableStateOf(formatMinorUnits(candidate.amountMinor))
    var categoryId by mutableStateOf(candidate.categoryAccountId)

    /**
     * Own account for this row when the document named one ("Forma de Pago:
     * Dinero disponible en Mercado Pago"); null falls back to the screen-wide
     * pick in the bottom bar.
     */
    var accountId by mutableStateOf(candidate.accountId)
    val note = candidate.note

    val amount: Money? get() = Money.parse(amountText, commodity)
    val valid: Boolean get() = payee.isNotBlank() && (amount?.minorUnits ?: 0L) != 0L && categoryId != null

    fun assetOr(fallback: String?): String? = accountId ?: fallback
}

/**
 * Review step of the AI import: the picked/shared document is sent to the
 * worker, the returned candidates are edited here and created in one batch
 * (all postings share the document's id as provenance).
 */
@Composable
fun ImportReviewScreen(
    document: PickedDocument,
    imports: DocumentAnalyzer,
    ledger: TransactionsRepository,
    accounts: AccountsRepository,
    onDone: () -> Unit,
    onNavigateBack: () -> Unit,
) {
    var analysis by remember { mutableStateOf<ImportAnalysis?>(null) }
    var drafts by remember { mutableStateOf<SnapshotStateList<CandidateDraft>?>(null) }
    var tree by remember { mutableStateOf<List<AccountNode>>(emptyList()) }
    var paths by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var assetId by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var attempt by remember { mutableStateOf(0) }
    var picking by remember { mutableStateOf<PickerTarget?>(null) }
    val scope = rememberCoroutineScope()
    val createdOne = stringResource(Res.string.import_created_one)
    val createdMany = stringResource(Res.string.import_created)
    val undoLabel = stringResource(Res.string.action_undo)

    LaunchedEffect(document, attempt) {
        error = null
        analysis = null
        drafts = null
        try {
            tree = accounts.tree()
            paths = tree.flatMap { it.selfAndDescendants }.associate { it.account.id to it.path }
            val assets = tree.filter { it.account.type == AccountType.Asset }
            val result = imports.analyze(document)
            analysis = result
            // The default in the bottom bar only covers rows the document
            // didn't attribute to one of the user's own accounts, so seed it
            // from whatever the analysis detected most — not an arbitrary
            // first account — and fall back the way the quick-entry screen does.
            if (assetId == null) {
                assetId = result.candidates.mapNotNull { it.accountId }
                    .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
                    ?: assets.singleOrNull()?.account?.id
                    ?: assets.firstOrNull()?.account?.id
            }
            drafts = result.candidates.map { candidate ->
                CandidateDraft(candidate).also { draft ->
                    if (draft.categoryId == null) {
                        // Fall back to the seeded External accounts, exactly
                        // like the quick-entry screen does.
                        draft.categoryId = when (draft.direction) {
                            ImportDirection.Expense -> EXTERNAL_EXPENSE_ID
                            ImportDirection.Income -> EXTERNAL_INCOME_ID
                        }
                    }
                    // A single candidate has nothing to scan through — open it
                    // straight away instead of making the user tap to see it.
                    if (result.candidates.size == 1) draft.expanded = true
                }
            }.toMutableStateList()
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            error = e.message ?: e.toString()
        }
    }

    val rows = drafts
    val included = rows?.count { it.include && it.valid && it.assetOr(assetId) != null } ?: 0

    fun create() {
        val current = rows ?: return
        val fallbackAsset = assetId
        val docId = analysis?.docId
        busy = true
        error = null
        scope.launch {
            try {
                val entries = current.filter { it.include && it.valid }.mapNotNull { draft ->
                    val amount = draft.amount!!.minorUnits
                    val category = draft.categoryId!!
                    val asset = draft.assetOr(fallbackAsset) ?: return@mapNotNull null
                    // Expense: asset −X / category +X. Income: category −X / asset +X.
                    val postings = when (draft.direction) {
                        ImportDirection.Expense -> listOf(
                            DraftPosting(asset, formatMinorUnits(-amount), draft.commodity),
                            DraftPosting(category, formatMinorUnits(amount), draft.commodity),
                        )
                        ImportDirection.Income -> listOf(
                            DraftPosting(category, formatMinorUnits(-amount), draft.commodity),
                            DraftPosting(asset, formatMinorUnits(amount), draft.commodity),
                        )
                    }
                    NewTransaction(
                        date = draft.date,
                        payee = draft.payee.trim(),
                        note = draft.note,
                        drafts = postings,
                        sourceDocumentId = docId,
                    )
                }
                if (entries.isEmpty()) return@launch
                val ids = ledger.addAll(entries)
                ledger.syncNow()
                val message = if (ids.size == 1) {
                    createdOne
                } else {
                    createdMany.replace("%1\$d", ids.size.toString())
                }
                // Fires from Feedback's own scope, so it survives this pop.
                Feedback.undoable(message, undoLabel) {
                    ids.forEach { ledger.delete(it) }
                    ledger.syncNow()
                }
                onDone()
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                error = e.message ?: e.toString()
            } finally {
                busy = false
            }
        }
    }

    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.import_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(Res.string.action_back))
                    }
                },
                actions = {
                    Text(
                        stringResource(
                            if (document.isPdf) Res.string.import_source_pdf else Res.string.import_source_image,
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 16.dp),
                    )
                },
            )
        },
        bottomBar = {
            if (rows != null && rows.isNotEmpty()) {
                Surface(tonalElevation = 3.dp) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .windowInsetsPadding(WindowInsets.navigationBars)
                            .padding(16.dp),
                    ) {
                        // Fallback for rows the document didn't attribute to
                        // one of the user's own accounts.
                        AccountField(
                            label = stringResource(Res.string.import_account_default_label),
                            value = assetId?.let { paths[it] },
                            placeholder = stringResource(Res.string.import_account_pick),
                            onClick = { picking = PickerTarget.Asset },
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = { create() },
                            enabled = !busy && included > 0 && assetId != null,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            if (busy) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                )
                            } else {
                                Text(stringResource(Res.string.import_create, included))
                            }
                        }
                    }
                }
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                error != null && rows == null -> ImportError(error!!) { attempt++ }
                rows == null -> AnalyzingState()
                rows.isEmpty() -> CenteredMessage(stringResource(Res.string.import_empty))
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        Text(
                            if (rows.size == 1) {
                                stringResource(Res.string.import_found_one)
                            } else {
                                stringResource(Res.string.import_found, rows.size)
                            },
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    items(rows) { draft ->
                        CandidateCard(
                            draft = draft,
                            categoryPath = draft.categoryId?.let { paths[it] },
                            accountPath = draft.accountId?.let { paths[it] },
                            fallbackAccountPath = assetId?.let { paths[it] },
                            onPickCategory = { picking = PickerTarget.Category(draft) },
                            onPickAccount = { picking = PickerTarget.RowAsset(draft) },
                            onToggleExpanded = { draft.expanded = !draft.expanded },
                        )
                    }
                    error?.let { message ->
                        item {
                            Text(
                                message,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
    }

    when (val target = picking) {
        null -> Unit
        PickerTarget.Asset -> AccountPickerSheet(
            tree = tree.filter { it.account.type == AccountType.Asset },
            title = stringResource(Res.string.import_account_pick),
            onDismiss = { picking = null },
            onPick = {
                assetId = it.account.id
                picking = null
            },
        )
        is PickerTarget.RowAsset -> AccountPickerSheet(
            tree = tree.filter {
                it.account.type == AccountType.Asset || it.account.type == AccountType.Liability
            },
            title = stringResource(Res.string.import_account_pick),
            onDismiss = { picking = null },
            onPick = {
                target.draft.accountId = it.account.id
                picking = null
            },
        )
        is PickerTarget.Category -> {
            val type = when (target.draft.direction) {
                ImportDirection.Expense -> AccountType.Expense
                ImportDirection.Income -> AccountType.Income
            }
            AccountPickerSheet(
                tree = tree.filter { it.account.type == type },
                title = stringResource(Res.string.import_category_pick),
                onDismiss = { picking = null },
                onPick = {
                    target.draft.categoryId = it.account.id
                    picking = null
                },
            )
        }
    }
}

private sealed interface PickerTarget {
    /** The screen-wide fallback account in the bottom bar. */
    data object Asset : PickerTarget
    data class RowAsset(val draft: CandidateDraft) : PickerTarget
    data class Category(val draft: CandidateDraft) : PickerTarget
}

@Composable
private fun CandidateCard(
    draft: CandidateDraft,
    categoryPath: String?,
    accountPath: String?,
    fallbackAccountPath: String?,
    onPickCategory: () -> Unit,
    onPickAccount: () -> Unit,
    onToggleExpanded: () -> Unit,
) {
    val dim = if (draft.include) 1f else 0.4f
    val signed = when (draft.direction) {
        ImportDirection.Expense -> "−"
        ImportDirection.Income -> "+"
    } + draft.amountText
    val amountColor = when {
        !draft.include -> MaterialTheme.colorScheme.onSurfaceVariant
        draft.direction == ImportDirection.Income -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurface
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggleExpanded)
                    .padding(vertical = 4.dp),
            ) {
                Checkbox(checked = draft.include, onCheckedChange = { draft.include = it })
                Column(Modifier.weight(1f)) {
                    Text(
                        draft.payee.ifBlank { "—" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = dim),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        listOfNotNull(dayLabel(dayGroup(draft.date)), accountPath, categoryPath)
                            .joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = dim),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        signed,
                        style = MaterialTheme.typography.bodyMedium,
                        color = amountColor.copy(alpha = dim),
                        maxLines = 1,
                    )
                    // Only a commodity the document stated explicitly shows
                    // up here; everything else is the default (ARS).
                    if (draft.commodity != Money.DEFAULT_COMMODITY) {
                        Text(
                            draft.commodity,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
                Icon(
                    if (draft.expanded) Icons.Filled.ExpandMore else Icons.Filled.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp).size(20.dp),
                )
            }
            if (draft.expanded) {
                Column(Modifier.padding(start = 8.dp, end = 8.dp, bottom = 12.dp, top = 4.dp)) {
                    OutlinedTextField(
                        value = draft.payee,
                        onValueChange = { draft.payee = it },
                        label = { Text(stringResource(Res.string.import_payee_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = draft.amountText,
                        onValueChange = { draft.amountText = it },
                        label = { Text(stringResource(Res.string.import_amount_label)) },
                        singleLine = true,
                        isError = draft.amount == null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    AccountField(
                        label = stringResource(Res.string.import_category_label),
                        value = categoryPath,
                        placeholder = stringResource(Res.string.import_category_pick),
                        onClick = onPickCategory,
                    )
                    Spacer(Modifier.height(8.dp))
                    AccountField(
                        label = stringResource(Res.string.import_account_label),
                        value = accountPath ?: fallbackAccountPath,
                        placeholder = stringResource(Res.string.import_account_pick),
                        onClick = onPickAccount,
                    )
                }
            }
        }
    }
}

/** Read-only field that opens an [AccountPickerSheet] when tapped. */
@Composable
private fun AccountField(
    label: String?,
    value: String?,
    placeholder: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            if (label != null) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                value ?: placeholder,
                style = MaterialTheme.typography.bodyMedium,
                color = if (value == null) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun AnalyzingState() {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(20.dp))
        Text(
            stringResource(Res.string.import_analyzing),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(Res.string.import_analyzing_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ImportError(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            stringResource(Res.string.import_failed),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = onRetry) { Text(stringResource(Res.string.import_retry)) }
    }
}

@Composable
private fun CenteredMessage(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(32.dp),
        )
    }
}
