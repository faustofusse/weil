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
import androidx.compose.runtime.mutableStateListOf
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
import weil.app.sharedui.generated.resources.import_chain_progress
import weil.app.sharedui.generated.resources.import_chain_start
import weil.app.sharedui.generated.resources.import_create
import weil.app.sharedui.generated.resources.import_created
import weil.app.sharedui.generated.resources.import_counter_amount_label
import weil.app.sharedui.generated.resources.import_created_one
import weil.app.sharedui.generated.resources.import_destination_label
import weil.app.sharedui.generated.resources.import_destination_pick
import weil.app.sharedui.generated.resources.import_origin_label
import weil.app.sharedui.generated.resources.import_empty
import weil.app.sharedui.generated.resources.import_failed
import weil.app.sharedui.generated.resources.import_found
import weil.app.sharedui.generated.resources.import_found_one
import weil.app.sharedui.generated.resources.import_items
import weil.app.sharedui.generated.resources.import_associate_only
import weil.app.sharedui.generated.resources.import_associated
import weil.app.sharedui.generated.resources.import_associated_one
import weil.app.sharedui.generated.resources.import_create_and_associate
import weil.app.sharedui.generated.resources.import_match_already
import weil.app.sharedui.generated.resources.import_match_associate
import weil.app.sharedui.generated.resources.import_match_create
import weil.app.sharedui.generated.resources.import_match_duplicate
import weil.app.sharedui.generated.resources.import_match_maybe
import weil.app.sharedui.generated.resources.import_match_mirror
import weil.app.sharedui.generated.resources.import_match_will_associate
import weil.app.sharedui.generated.resources.import_reason_account
import weil.app.sharedui.generated.resources.import_reason_account_opposite
import weil.app.sharedui.generated.resources.import_reason_already
import weil.app.sharedui.generated.resources.import_reason_amount
import weil.app.sharedui.generated.resources.import_reason_amount_close
import weil.app.sharedui.generated.resources.import_reason_day
import weil.app.sharedui.generated.resources.import_reason_day_near
import weil.app.sharedui.generated.resources.import_reason_payee
import weil.app.sharedui.generated.resources.import_reason_payee_similar
import weil.app.sharedui.generated.resources.import_section_matched
import weil.app.sharedui.generated.resources.import_section_review
import weil.app.sharedui.generated.resources.import_payee_label
import weil.app.sharedui.generated.resources.picker_create
import weil.app.sharedui.generated.resources.import_retry
import weil.app.sharedui.generated.resources.import_source_csv
import weil.app.sharedui.generated.resources.import_source_pdf
import weil.app.sharedui.generated.resources.import_source_image
import weil.app.sharedui.generated.resources.import_split_add
import weil.app.sharedui.generated.resources.import_split_merge
import weil.app.sharedui.generated.resources.import_split_remove
import weil.app.sharedui.generated.resources.import_title
import weil.app.sharedui.generated.resources.inbox_empty
import weil.app.sharedui.generated.resources.inbox_source
import weil.app.sharedui.generated.resources.inbox_title

/**
 * Editable copy of an [ImportCandidate]: the user reviews, tweaks and either
 * keeps or drops each row before anything reaches the ledger.
 */
/**
 * One split line of a candidate, editable independently: its own amount and
 * category. [commodity] rides along only to parse [amountText] — every split
 * of one candidate shares the same currency.
 */
private class SplitDraft(split: ImportSplit, val commodity: String) {
    var amountText by mutableStateOf(rawAmountText(split.amountMinor))
    var categoryId by mutableStateOf(split.categoryAccountId)
    val amount: Money? get() = Money.parse(amountText, commodity)
    val valid: Boolean get() = (amount?.minorUnits ?: 0L) != 0L && categoryId != null
}

/**
 * What the review screen is reviewing. Both arms end in the same rows: a
 * statement and a push alert describe the same kind of event, and the whole
 * point of the reconciliation work is that the ledger cannot tell which door
 * a movement used.
 */
sealed interface ReviewSource {
    /** An image/PDF the user picked or shared; analyzed by the worker. */
    data class Document(val document: PickedDocument) : ReviewSource

    /** Movements recognized locally in captured notifications and emails. */
    data object Inbox : ReviewSource
}

private class CandidateDraft(
    val candidate: ImportCandidate,
    /** Origin of this row; the document arm shares one for the whole batch. */
    val sourceKind: EventSource = EventSource.Document,
    val sourceRef: String? = null,
    /** Headline of the originating message, shown instead of a page number. */
    val sourceTitle: String? = null,
) {
    /**
     * A document states a day, so its candidates are dated at local midnight
     * here rather than at the noon-UTC placeholder the wire carries — and
     * [timeKnown] then keeps the editor and the registers from printing an
     * hour nobody wrote down.
     */
    val date = candidate.day?.let { parseDateInput(it) } ?: candidate.date
    val timeKnown = candidate.day == null
    val direction = candidate.direction
    val commodity = candidate.commodity
    var include by mutableStateOf(true)
    var expanded by mutableStateOf(false)
    var payee by mutableStateOf(candidate.payee)

    /**
     * Own account for this row when the document named one ("Forma de Pago:
     * Dinero disponible en Mercado Pago"); null falls back to the screen-wide
     * pick in the bottom bar.
     */
    var accountId by mutableStateOf(candidate.accountId)
    val note = candidate.note

    /**
     * Currency exchange: the destination leg is another currency, so it
     * carries its own amount (pesos out, dollars in). Editable because the
     * rate is read off the row's prose and worth a second look.
     */
    val counterCommodity = candidate.counterCommodity
    var counterAmountText by mutableStateOf(
        candidate.counterAmountMinor?.let { rawAmountText(it) } ?: "",
    )
    val counterAmount: Money?
        get() = counterCommodity?.let { Money.parse(counterAmountText, it) }

    /**
     * Almost always one line; more only when the document itself itemized
     * the payment (a receipt's line items). A mutable list so the user can
     * add, remove or merge lines by hand.
     */
    val splits = candidate.splits.ifEmpty { listOf(ImportSplit(0L, null, null)) }
        .map { SplitDraft(it, commodity) }
        .toMutableStateList()

    /**
     * What the matcher found for this row: the existing transaction it might
     * already be (a duplicate of a row imported from another statement, or the
     * other half of a transfer). Null when nothing comparable is in the ledger.
     */
    var suggestion by mutableStateOf<ScoredMatch?>(null)

    /**
     * The three-state decision. Null = create a new transaction; non-null =
     * attach this event to that existing one instead. [include] off = skip.
     */
    var associateTo by mutableStateOf<ScoredMatch?>(null)

    val totalMinor: Long get() = splits.sumOf { it.amount?.minorUnits ?: 0L }
    val valid: Boolean get() =
        payee.isNotBlank() && splits.isNotEmpty() && splits.all { it.valid } && totalMinor != 0L &&
            (counterCommodity == null || (counterAmount?.minorUnits ?: 0L) != 0L)

    fun assetOr(fallback: String?): String? = accountId ?: fallback

    /**
     * Fingerprint of the movement as it will be saved, so a later import of an
     * overlapping statement period recognizes it without scoring. Derived at
     * write time because both the account fallback and the amount are editable.
     */
    fun eventKey(fallbackAsset: String?): String =
        candidate.toEvent(null, assetOr(fallbackAsset), totalMinor).eventKey

    /**
     * Provenance rows for this draft: which message or document it came from,
     * plus the door-independent fingerprint that recognizes the same movement
     * arriving later through a different one.
     */
    fun provenance(fallbackAsset: String?): List<TransactionSource> {
        val ref = sourceRef ?: return emptyList()
        return listOf(TransactionSource(sourceKind, ref, eventKey(fallbackAsset)))
    }
}

/**
 * Review step of the AI import: the picked/shared document is sent to the
 * worker, the returned candidates are edited here and created in one batch
 * (all postings share the document's id as provenance).
 */
@Composable
fun ImportReviewScreen(
    source: ReviewSource,
    imports: DocumentAnalyzer,
    ingest: IngestRepository,
    ledger: TransactionsRepository,
    accounts: AccountsRepository,
    settings: SettingsRepository,
    onDone: () -> Unit,
    onNavigateBack: () -> Unit,
) {
    var analysis by remember { mutableStateOf<ImportAnalysis?>(null) }
    var drafts by remember { mutableStateOf<SnapshotStateList<CandidateDraft>?>(null) }
    var tree by remember { mutableStateOf<List<AccountNode>>(emptyList()) }
    var paths by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    // Leaf names for the collapsed rows: "Verduras", not "Comida:Verduras".
    // The full path stays in the expanded fields, where the extra words are
    // what tells two same-named leaves apart.
    var names by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var assetId by remember { mutableStateOf<String?>(null) }
    var defaults by remember { mutableStateOf<Map<AccountType, String>>(emptyMap()) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var attempt by remember { mutableStateOf(0) }
    var picking by remember { mutableStateOf<PickerTarget?>(null) }
    var creatingCategory by remember { mutableStateOf<PickerTarget.Category?>(null) }
    // Chain mode: one picker sheet walks every row that still needs a
    // category, advancing on each pick instead of closing. [visited] keeps
    // the walk moving even when the user re-picks the same default.
    var chaining by remember { mutableStateOf(false) }
    val visited = remember { mutableStateListOf<Pair<CandidateDraft, Int>>() }
    // CreateAccountDialog calls onCreated *and then* onDismiss; without this
    // the dismiss would re-open the picker on a row the walk already left.
    var categoryCreated by remember { mutableStateOf(false) }
    // Type currently shown by the category picker. Starts at the direction's
    // natural type and follows the sheet's chips, so inline creation makes an
    // account of the type the user is actually looking at.
    var categoryType by remember { mutableStateOf<AccountType?>(null) }
    // Resolved rows collapse into a count: on a statement that is already in
    // the ledger they are almost all of it, and scrolling past thirty cards
    // that need nothing to reach the four that do is the whole problem.
    var matchedExpanded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val createdOne = stringResource(Res.string.import_created_one)
    val createdMany = stringResource(Res.string.import_created)
    val associatedOne = stringResource(Res.string.import_associated_one)
    val associatedMany = stringResource(Res.string.import_associated)
    val undoLabel = stringResource(Res.string.action_undo)
    val matchPolicy = remember { MatchPolicy() }

    suspend fun reloadTree() {
        defaults = settings.defaultAccounts()
        tree = accounts.tree()
        val nodes = tree.flatMap { it.selfAndDescendants }
        paths = nodes.associate { it.account.id to it.path.censored() }
        names = nodes.associate { it.account.id to it.account.name.censored() }
    }

    LaunchedEffect(source, attempt) {
        error = null
        analysis = null
        drafts = null
        try {
            reloadTree()
            // Matching runs against the local replica, so pull first: without
            // this, rows another device wrote minutes ago are invisible and
            // every one of them would be imported a second time.
            try {
                ledger.syncNow()
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
            }
            // The two doors differ only in how the rows are obtained: the
            // worker reads a document, the device reads its own notification
            // and email captures. Everything below is shared.
            val loaded: List<CandidateDraft> = when (source) {
                is ReviewSource.Document -> {
                    val result = imports.analyze(source.document)
                    analysis = result
                    result.candidates.map {
                        CandidateDraft(it, EventSource.Document, result.docId)
                    }
                }
                ReviewSource.Inbox -> ingest.inbox().map {
                    CandidateDraft(it.candidate, it.kind, it.ref, it.title)
                }
            }
            val candidates = loaded.map { it.candidate }
            // The default in the bottom bar only covers rows the document
            // didn't attribute to one of the user's own accounts, so seed it
            // from whatever the analysis detected most — not an arbitrary
            // first account — and fall back the way the quick-entry screen does.
            if (assetId == null) {
                assetId = candidates.mapNotNull { it.accountId }
                    .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
                    ?: resolveDefault(tree, AccountType.Asset, defaults[AccountType.Asset])
            }
            // Blocking step: one window query for the whole document instead
            // of a lookup per candidate.
            val facts = if (candidates.isEmpty()) {
                emptyList()
            } else {
                val dates = candidates.map { it.date }
                ledger.reconcileFacts(
                    dates.min() - matchPolicy.windowMs,
                    dates.max() + matchPolicy.windowMs,
                )
            }
            // Batch-matched, not row-by-row: `matchAll` also makes sure two
            // candidates never claim the same existing transaction.
            val outcomes = matchAll(
                loaded.map { it.candidate.toEvent(it.sourceRef, assetId) },
                facts,
                matchPolicy,
            )
            drafts = loaded.mapIndexed { index, draft ->
                draft.also {
                    // Fall back to the seeded "Otros" account — but only if it
                    // actually exists. Assigning the fixed id blind wrote
                    // postings pointing at a missing account on any ledger
                    // that never got seeded (the seed only runs on an empty
                    // accounts table), and the journal then had nothing to
                    // render but the raw id. Null leaves the row invalid, so
                    // the user picks a category instead of silently creating
                    // a dangling posting.
                    val defaultCategory = defaultCategoryId(tree, defaults, draft.direction)
                    draft.splits.forEach { split ->
                        if (split.categoryId == null) split.categoryId = defaultCategory
                    }
                    // A single candidate has nothing to scan through — open it
                    // straight away instead of making the user tap to see it.
                    if (candidates.size == 1) draft.expanded = true

                    // Confident matches default to associating; anything less
                    // is only a suggestion the row displays. An event already
                    // imported from another document defaults to skipped —
                    // re-including it associates (idempotent) rather than
                    // writing a second copy.
                    when (val outcome = outcomes[index]) {
                        is MatchOutcome.Confident -> {
                            draft.suggestion = outcome.match
                            draft.associateTo = outcome.match
                            if (outcome.match.relation == MatchRelation.AlreadyImported) {
                                draft.include = false
                            }
                        }
                        is MatchOutcome.Ambiguous -> draft.suggestion = outcome.matches.first()
                        MatchOutcome.None -> Unit
                    }
                }
            }.toMutableStateList()
            // Nothing left to decide (a whole statement re-imported) would
            // otherwise render as an empty screen with one collapsed header.
            matchedExpanded = drafts?.none { it.associateTo == null } == true
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            error = e.message ?: e.toString()
        }
    }

    val rows = drafts
    val creating = rows?.count {
        it.include && it.associateTo == null && it.valid && it.assetOr(assetId) != null
    } ?: 0
    val associating = rows?.count { it.include && it.associateTo != null } ?: 0
    val included = creating + associating

    /**
     * Splits worth walking: the ones still on the fallback category (or on
     * none at all) — i.e. the rows the AI couldn't attribute. When every row
     * already has a real category the whole included set is offered instead,
     * so the button still does something useful.
     */
    fun chainQueue(): List<Pair<CandidateDraft, Int>> {
        val current = rows ?: return emptyList()
        // Rows being associated to an existing transaction need no category:
        // nothing new is written for them.
        val all = current.filter { it.include && it.associateTo == null }
            .flatMap { draft -> draft.splits.indices.map { draft to it } }
        val pending = all.filter { (draft, index) ->
            val category = draft.splits[index].categoryId
            category == null || category == defaultCategoryId(tree, defaults, draft.direction)
        }
        return pending.ifEmpty { all }
    }

    /** Move to the next unvisited split, or end the walk. */
    fun advanceChain() {
        val next = chainQueue().firstOrNull { it !in visited }
        if (next == null) {
            chaining = false
            picking = null
        } else {
            visited += next
            next.first.expanded = true
            categoryType = null
            picking = PickerTarget.Category(next.first, next.second)
        }
    }

    fun create() {
        val current = rows ?: return
        val fallbackAsset = assetId
        val docId = analysis?.docId
        busy = true
        error = null
        scope.launch {
            try {
                // Rows the user chose to attach to a transaction that already
                // records the movement: provenance always, plus (for the other
                // half of a transfer) repointing the dangling category leg at
                // this row's own account.
                val associations = current.filter { it.include && it.associateTo != null }
                    .map { draft ->
                        val match = draft.associateTo!!
                        val mirror = match.relation == MatchRelation.Mirror
                        AssociateOp(
                            transactionId = match.fact.transactionId,
                            sources = draft.provenance(fallbackAsset),
                            retargetPostingId = if (mirror) match.retargetPostingId else null,
                            retargetAccountId = if (mirror) draft.assetOr(fallbackAsset) else null,
                        )
                    }
                val entries = current.filter { it.include && it.associateTo == null && it.valid }.mapNotNull { draft ->
                    val asset = draft.assetOr(fallbackAsset) ?: return@mapNotNull null
                    // The asset leg is the payment's full total; each split is
                    // its own category leg. Expense: asset −total, category
                    // +share. Income: category −share, asset +total.
                    val assetLeg = when (draft.direction) {
                        // A transfer leaves the row's account exactly like an
                        // expense does; only the other leg differs (another
                        // account of the user's, not a category).
                        ImportDirection.Expense, ImportDirection.Transfer ->
                            DraftPosting(asset, formatMinorUnits(-draft.totalMinor), draft.commodity)
                        ImportDirection.Income -> DraftPosting(asset, formatMinorUnits(draft.totalMinor), draft.commodity)
                    }
                    // Currency exchange: the far leg is denominated in the
                    // other currency, so the transaction is intentionally
                    // unbalanced per commodity — that is what an exchange is.
                    val counter = draft.counterAmount
                    val splitLegs = if (counter != null && draft.counterCommodity != null) {
                        listOf(
                            DraftPosting(
                                draft.splits.first().categoryId!!,
                                formatMinorUnits(counter.minorUnits),
                                draft.counterCommodity,
                            ),
                        )
                    } else draft.splits.map { split ->
                        val minor = split.amount!!.minorUnits
                        val category = split.categoryId!!
                        when (draft.direction) {
                            ImportDirection.Expense, ImportDirection.Transfer ->
                                DraftPosting(category, formatMinorUnits(minor), draft.commodity)
                            ImportDirection.Income -> DraftPosting(category, formatMinorUnits(-minor), draft.commodity)
                        }
                    }
                    NewTransaction(
                        date = draft.date,
                        payee = draft.payee.trim(),
                        note = draft.note,
                        timeKnown = draft.timeKnown,
                        drafts = listOf(assetLeg) + splitLegs,
                        sourceDocumentId = docId,
                        sources = draft.provenance(fallbackAsset),
                    )
                }
                if (entries.isEmpty() && associations.isEmpty()) return@launch
                // addAll validates inside one SQL transaction, so a single bad
                // row would roll back the other forty with a message naming
                // none of them. Check row by row first and say which one.
                entries.forEach { entry ->
                    try {
                        resolvePostings(entry.drafts)
                    } catch (e: LedgerValidationException) {
                        error = "${entry.payee}: ${e.message}"
                        return@launch
                    }
                }
                val ids = ledger.addAll(entries)
                val undos = ledger.associate(associations)
                ledger.syncNow()
                val created = when {
                    ids.isEmpty() -> null
                    ids.size == 1 -> createdOne
                    else -> createdMany.replace("%1\$d", ids.size.toString())
                }
                val attached = when {
                    undos.isEmpty() -> null
                    undos.size == 1 -> associatedOne
                    else -> associatedMany.replace("%1\$d", undos.size.toString())
                }
                val message = listOfNotNull(created, attached).joinToString(" · ")
                // Fires from Feedback's own scope, so it survives this pop.
                Feedback.undoable(message, undoLabel) {
                    ids.forEach { ledger.delete(it) }
                    ledger.revertAssociations(undos)
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
                title = {
                    Text(
                        stringResource(
                            when (source) {
                                is ReviewSource.Document -> Res.string.import_title
                                ReviewSource.Inbox -> Res.string.inbox_title
                            },
                        ),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(Res.string.action_back))
                    }
                },
                actions = {
                    Text(
                        stringResource(
                            when {
                                source is ReviewSource.Document && source.document.isPdf ->
                                    Res.string.import_source_pdf
                                source is ReviewSource.Document && source.document.isCsv ->
                                    Res.string.import_source_csv
                                source is ReviewSource.Document -> Res.string.import_source_image
                                else -> Res.string.inbox_source
                            },
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
                        // Next to the button that triggered it: the failure
                        // used to render at the bottom of the candidate list,
                        // which on a statement meant scrolling past forty rows
                        // to find out why nothing happened.
                        error?.let { message ->
                            Text(
                                message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(bottom = 8.dp),
                            )
                        }
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
                                Text(
                                    when {
                                        associating == 0 -> stringResource(Res.string.import_create, creating)
                                        creating == 0 -> stringResource(Res.string.import_associate_only, associating)
                                        else -> stringResource(
                                            Res.string.import_create_and_associate,
                                            creating,
                                            associating,
                                        )
                                    },
                                )
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
                rows.isEmpty() -> CenteredMessage(
                    stringResource(
                        when (source) {
                            is ReviewSource.Document -> Res.string.import_empty
                            ReviewSource.Inbox -> Res.string.inbox_empty
                        },
                    ),
                )
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (rows.size == 1) {
                                    stringResource(Res.string.import_found_one)
                                } else {
                                    stringResource(Res.string.import_found, rows.size)
                                },
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f),
                            )
                            // Deliberately quiet: a power-user shortcut, not a
                            // second call to action next to "Crear".
                            TextButton(
                                onClick = {
                                    visited.clear()
                                    chaining = true
                                    advanceChain()
                                },
                            ) {
                                Text(
                                    stringResource(Res.string.import_chain_start),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    // Two groups, document order inside each: rows that still
                    // need a decision (new ones, and the maybes the matcher
                    // would not resolve on its own) come first; the ones it
                    // already resolved sit behind one collapsed header. The
                    // split is derived from live state, so answering a row
                    // moves it out of the way immediately.
                    val toReview = rows.filter { it.associateTo == null }
                    val matched = rows.filter { it.associateTo != null }

                    @Composable
                    fun card(draft: CandidateDraft) = CandidateCard(
                        draft = draft,
                        paths = paths,
                        names = names,
                        fallbackAccountPath = assetId?.let { paths[it] },
                        fallbackAccountName = assetId?.let { names[it] },
                        onPickCategory = { index ->
                            categoryType = null
                            picking = PickerTarget.Category(draft, index)
                        },
                        onPickAccount = { picking = PickerTarget.RowAsset(draft) },
                        onToggleExpanded = { draft.expanded = !draft.expanded },
                        onToggleAssociate = {
                            draft.associateTo =
                                if (draft.associateTo == null) draft.suggestion else null
                        },
                    )

                    if (toReview.isNotEmpty() && matched.isNotEmpty()) {
                        item {
                            GroupHeader(stringResource(Res.string.import_section_review, toReview.size))
                        }
                    }
                    items(toReview) { draft -> card(draft) }
                    if (matched.isNotEmpty()) {
                        item {
                            GroupHeader(
                                text = stringResource(Res.string.import_section_matched, matched.size),
                                expanded = matchedExpanded,
                                onClick = { matchedExpanded = !matchedExpanded },
                            )
                        }
                        if (matchedExpanded) items(matched) { draft -> card(draft) }
                    }
                }
            }
        }
    }

    when (val target = picking) {
        null -> Unit
        PickerTarget.Asset -> AccountPickerSheet(
            tree = tree,
            title = stringResource(Res.string.import_account_pick),
            typeOptions = listOf(AccountType.Asset, AccountType.Liability),
            initialType = AccountType.Asset,
            onDismiss = { picking = null },
            onPick = {
                assetId = it.account.id
                picking = null
            },
        )
        is PickerTarget.RowAsset -> AccountPickerSheet(
            tree = tree,
            title = stringResource(Res.string.import_account_pick),
            typeOptions = listOf(
                AccountType.Asset,
                AccountType.Liability,
                AccountType.Expense,
                AccountType.Income,
                AccountType.Equity,
            ),
            initialType = target.draft.accountId?.let { id ->
                tree.flatMap { it.selfAndDescendants }
                    .firstOrNull { it.account.id == id }?.account?.type
            } ?: AccountType.Asset,
            onDismiss = { picking = null },
            onPick = {
                target.draft.accountId = it.account.id
                picking = null
            },
        )
        is PickerTarget.Category -> {
            val natural = when (target.draft.direction) {
                ImportDirection.Expense -> AccountType.Expense
                ImportDirection.Income -> AccountType.Income
                ImportDirection.Transfer -> AccountType.Asset
            }
            val current = target.draft.splits.getOrNull(target.splitIndex)?.categoryId
            val currentType = current?.let { id ->
                tree.flatMap { it.selfAndDescendants }.firstOrNull { it.account.id == id }?.account?.type
            }
            val queue = if (chaining) chainQueue() else emptyList()
            AccountPickerSheet(
                tree = tree,
                // The direction's own type first (the default), then the rest:
                // a refund lands on an income account, a card payment on a
                // liability, and the user shouldn't have to leave the sheet.
                typeOptions = listOf(natural) + (AccountType.entries - natural),
                initialType = currentType ?: natural,
                onTypeChange = { categoryType = it },
                title = stringResource(
                    if (target.draft.direction == ImportDirection.Transfer) {
                        Res.string.import_destination_pick
                    } else {
                        Res.string.import_category_pick
                    },
                ),
                subtitle = if (chaining) {
                    val step = (queue.size - queue.count { it !in visited }).coerceAtLeast(1)
                    // Everything the document said about this row: what the
                    // AI couldn't categorize is usually decided by the date
                    // and the note, so both ride along with payee + amount.
                    listOfNotNull(
                        target.draft.payee.censored().ifBlank { "—" },
                        formatMinorUnits(target.draft.splits.getOrNull(target.splitIndex)?.amount?.minorUnits ?: 0L),
                        dayLabel(dayGroup(target.draft.date)),
                        target.draft.note?.censored()?.takeIf { it.isNotBlank() },
                        stringResource(Res.string.import_chain_progress, step, queue.size),
                    ).joinToString(" · ")
                } else {
                    null
                },
                createLabel = stringResource(Res.string.picker_create),
                onCreate = {
                    picking = null
                    categoryCreated = false
                    if (categoryType == null) categoryType = currentType ?: natural
                    creatingCategory = target
                },
                onDismiss = {
                    picking = null
                    chaining = false
                },
                onPick = {
                    target.draft.splits.getOrNull(target.splitIndex)?.categoryId = it.account.id
                    if (chaining) advanceChain() else picking = null
                },
            )
        }
    }

    creatingCategory?.let { target ->
        val type = categoryType ?: when (target.draft.direction) {
            ImportDirection.Expense -> AccountType.Expense
            ImportDirection.Income -> AccountType.Income
            ImportDirection.Transfer -> AccountType.Asset
        }
        CreateAccountDialog(
            title = stringResource(Res.string.picker_create),
            type = type,
            accounts = accounts,
            onDismiss = {
                creatingCategory = null
                // Cancelling creation shouldn't abort the walk: fall back to
                // the picker for the same row.
                if (chaining && !categoryCreated) picking = target
            },
            onError = { error = it },
            onCreated = { id ->
                target.draft.splits.getOrNull(target.splitIndex)?.categoryId = id
                reloadTree()
                categoryCreated = true
                if (chaining) advanceChain()
            },
        )
    }
}

/**
 * The fallback category for [direction]: the user's default account for that
 * type when it still exists, else the seeded "Otros" (see [resolveDefault]).
 */
private fun defaultCategoryId(
    tree: List<AccountNode>,
    defaults: Map<AccountType, String>,
    direction: ImportDirection,
): String? {
    val type = when (direction) {
        ImportDirection.Expense -> AccountType.Expense
        ImportDirection.Income -> AccountType.Income
        // A transfer's other leg is one of the user's own accounts; there is
        // no sensible "Otros" to fall back to, so the row stays incomplete
        // until the user picks the destination.
        ImportDirection.Transfer -> return null
    }
    return resolveDefault(tree, type, defaults[type])
}

private sealed interface PickerTarget {
    /** The screen-wide fallback account in the bottom bar. */
    data object Asset : PickerTarget
    data class RowAsset(val draft: CandidateDraft) : PickerTarget
    data class Category(val draft: CandidateDraft, val splitIndex: Int) : PickerTarget
}

@Composable
private fun CandidateCard(
    draft: CandidateDraft,
    paths: Map<String, String>,
    names: Map<String, String>,
    fallbackAccountPath: String?,
    fallbackAccountName: String?,
    onPickCategory: (splitIndex: Int) -> Unit,
    onPickAccount: () -> Unit,
    onToggleExpanded: () -> Unit,
    onToggleAssociate: () -> Unit,
) {
    val accountPath = draft.accountId?.let { paths[it] }
    val accountName = draft.accountId?.let { names[it] } ?: fallbackAccountName
    // One split names its own category; several collapse to a count —
    // "3 ítems" is what identifies the row when there's no single category
    // to show, the same way a folded account shows a subaccount count.
    val categoryOrCount = if (draft.splits.size == 1) {
        draft.splits.first().categoryId?.let { names[it] }
    } else {
        stringResource(Res.string.import_items, draft.splits.size)
    }
    val dim = if (draft.include) 1f else 0.4f
    val signed = when (draft.direction) {
        ImportDirection.Expense -> "−"
        ImportDirection.Income -> "+"
        // Neither sign fits: the user's net worth didn't move.
        ImportDirection.Transfer -> "⇄ "
        // The review screen keeps its own −/+ marks: these rows are not yet
        // in the ledger and carry no direction color, so the glyph is the
        // only thing saying which way the money goes.
    } + formatMoney(draft.totalMinor, draft.commodity)
    val amountColor = when {
        !draft.include -> MaterialTheme.colorScheme.onSurfaceVariant
        draft.direction == ImportDirection.Income -> MaterialTheme.colorScheme.primary
        draft.direction == ImportDirection.Transfer -> MaterialTheme.colorScheme.onSurfaceVariant
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
                        draft.payee.censored().ifBlank { "—" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = dim),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        listOfNotNull(dayLabel(dayGroup(draft.date)), accountName, categoryOrCount)
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
                    if (draft.counterCommodity != null) {
                        // An exchange: the other side is a different amount in
                        // a different currency, and that is the whole point.
                        Text(
                            "→ ${draft.counterAmount?.format() ?: draft.counterAmountText} ${draft.counterCommodity}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    } else if (draft.commodity != Money.DEFAULT_COMMODITY) {
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
            draft.suggestion?.let { suggestion ->
                MatchBanner(
                    suggestion = suggestion,
                    associating = draft.associateTo != null,
                    onToggle = onToggleAssociate,
                )
            }
            // Associating writes nothing new, so the editable fields would be
            // lying about what happens on confirm.
            if (draft.expanded && draft.associateTo == null) {
                Column(Modifier.padding(start = 8.dp, end = 8.dp, bottom = 12.dp, top = 4.dp)) {
                    OutlinedTextField(
                        value = draft.payee,
                        onValueChange = { draft.payee = it },
                        label = { Text(stringResource(Res.string.import_payee_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    AccountField(
                        label = stringResource(
                            if (draft.direction == ImportDirection.Transfer) {
                                Res.string.import_origin_label
                            } else {
                                Res.string.import_account_label
                            },
                        ),
                        value = accountPath ?: fallbackAccountPath,
                        placeholder = stringResource(Res.string.import_account_pick),
                        onClick = onPickAccount,
                    )
                    Spacer(Modifier.height(12.dp))
                    if (draft.counterCommodity != null) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = draft.counterAmountText,
                            onValueChange = { draft.counterAmountText = sanitizeAmountInput(it) },
                            visualTransformation = AmountVisualTransformation,
                            label = {
                                Text(
                                    stringResource(
                                        Res.string.import_counter_amount_label,
                                        draft.counterCommodity,
                                    ),
                                )
                            },
                            singleLine = true,
                            isError = draft.counterAmount == null,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    draft.splits.forEachIndexed { index, split ->
                        if (index > 0) Spacer(Modifier.height(8.dp))
                        SplitRow(
                            split = split,
                            transfer = draft.direction == ImportDirection.Transfer,
                            categoryPath = split.categoryId?.let { paths[it] },
                            removable = draft.splits.size > 1,
                            onPickCategory = { onPickCategory(index) },
                            onRemove = { draft.splits.removeAt(index) },
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { draft.splits.add(SplitDraft(ImportSplit(0L, null, null), draft.commodity)) }) {
                            Text(stringResource(Res.string.import_split_add))
                        }
                        // Undoes an accidental split, or one the user decides
                        // isn't worth categorizing separately after all.
                        if (draft.splits.size > 1) {
                            TextButton(
                                onClick = {
                                    val merged = draft.totalMinor
                                    val category = draft.splits.first().categoryId
                                    draft.splits.clear()
                                    draft.splits.add(
                                        SplitDraft(
                                            ImportSplit(merged, category, null),
                                            draft.commodity,
                                        ),
                                    )
                                },
                            ) {
                                Text(stringResource(Res.string.import_split_merge))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Group label; tappable (with a chevron) when it folds a group away. */
@Composable
private fun GroupHeader(
    text: String,
    expanded: Boolean? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick == null) Modifier else Modifier.clickable(onClick = onClick))
            .padding(top = 4.dp, bottom = 2.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (expanded != null) {
            Icon(
                if (expanded) Icons.Filled.ExpandMore else Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * The matcher's verdict for one row, and the switch between the two ways to
 * resolve it. The reasons are what make a merge approvable at a glance instead
 * of an opaque mutation — but only while the row is still a question: once it
 * is resolved (and filed under the collapsed "ya registradas" group, which
 * already says so) the banner shrinks to the target plus the way out.
 */
@Composable
private fun MatchBanner(
    suggestion: ScoredMatch,
    associating: Boolean,
    onToggle: () -> Unit,
) {
    val headline = when {
        !associating && suggestion.relation != MatchRelation.AlreadyImported ->
            stringResource(Res.string.import_match_maybe)
        else -> when (suggestion.relation) {
            MatchRelation.AlreadyImported -> stringResource(Res.string.import_match_already)
            MatchRelation.Duplicate -> stringResource(Res.string.import_match_duplicate)
            MatchRelation.Mirror -> stringResource(Res.string.import_match_mirror)
        }
    }
    val target = stringResource(
        Res.string.import_match_will_associate,
        suggestion.fact.payee.censored().ifBlank { "—" },
    )
    // Two reasons fit the row; the third was always being ellipsized away,
    // and the two strongest signals are what the user is judging anyway.
    val reasons = if (associating) emptyList() else suggestion.reasons.take(2).map { reasonLabel(it) }
    Surface(
        shape = MaterialTheme.shapes.small,
        color = if (associating) {
            MaterialTheme.colorScheme.surfaceContainerHigh
        } else {
            MaterialTheme.colorScheme.secondaryContainer
        },
        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        ) {
            Column(Modifier.weight(1f)) {
                // A resolved row is one line: the group header carries the
                // "why" for all of them.
                if (!associating) {
                    Text(
                        headline,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    target,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (reasons.isNotEmpty()) {
                    Text(
                        reasons.joinToString(", "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            TextButton(onClick = onToggle) {
                Text(
                    stringResource(
                        if (associating) {
                            Res.string.import_match_create
                        } else {
                            Res.string.import_match_associate
                        },
                    ),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

/** [MatchReason] is an enum in sharedLogic precisely so the words live here. */
@Composable
private fun reasonLabel(reason: MatchReason): String = stringResource(
    when (reason) {
        MatchReason.AlreadyImported -> Res.string.import_reason_already
        MatchReason.SameAmount -> Res.string.import_reason_amount
        MatchReason.CloseAmount -> Res.string.import_reason_amount_close
        MatchReason.SameDay -> Res.string.import_reason_day
        MatchReason.NearDay -> Res.string.import_reason_day_near
        MatchReason.SamePayee -> Res.string.import_reason_payee
        MatchReason.SimilarPayee -> Res.string.import_reason_payee_similar
        MatchReason.SameAccount -> Res.string.import_reason_account
        MatchReason.OppositeAccount -> Res.string.import_reason_account_opposite
    },
)

/** One editable split line: its own amount and category, in a row. */
@Composable
private fun SplitRow(
    split: SplitDraft,
    transfer: Boolean,
    categoryPath: String?,
    removable: Boolean,
    onPickCategory: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = split.amountText,
            onValueChange = { split.amountText = sanitizeAmountInput(it) },
            label = { Text(stringResource(Res.string.import_amount_label)) },
            singleLine = true,
            isError = split.amount == null,
            visualTransformation = AmountVisualTransformation,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.weight(0.4f),
        )
        Spacer(Modifier.width(8.dp))
        AccountField(
            label = stringResource(
                if (transfer) Res.string.import_destination_label else Res.string.import_category_label,
            ),
            value = categoryPath,
            placeholder = stringResource(
                if (transfer) Res.string.import_destination_pick else Res.string.import_category_pick,
            ),
            onClick = onPickCategory,
            modifier = Modifier.weight(0.6f),
        )
        if (removable) {
            IconButton(onClick = onRemove) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = stringResource(Res.string.import_split_remove),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = modifier.fillMaxWidth(),
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
