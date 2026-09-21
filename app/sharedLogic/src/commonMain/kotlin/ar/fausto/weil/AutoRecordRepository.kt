package ar.fausto.weil

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The end of the message→transaction path: the run that starts when a bank
 * message arrives now **writes the transaction**.
 *
 * There is no inbox and no banner. A bank push is already the bank telling
 * the user what happened; asking them to confirm it a second time turned
 * every movement into a chore, and the queue was the part nobody opened. So
 * the rule is inverted: the row is written, it shows up in the journal like
 * any other, and fixing it is the same edit as fixing a typo. The provenance
 * row (the door plus the fingerprint) is what keeps one purchase from being
 * written twice when the notification, the mail, the statement and the
 * WhatsApp bot all describe it.
 *
 * Two doors reach this class and they differ only in what wakes them: a
 * notification is an event (the Android listener calls [apply] on the spot),
 * while an email is written into the user's database server-side and only
 * appears locally after a sync — nothing announces it, so [sweepEmails] goes
 * looking.
 *
 * What is *not* written is decided by [worthRecording] (promotions,
 * unreadable amounts, messages already imported) and by the matcher: a
 * [MatchOutcome.Confident] match is attached to the transaction that already
 * records the movement instead of creating a second one.
 */
class AutoRecordRepository(
    private val accounts: AccountsRepository,
    private val emails: EmailsRepository,
    private val ledger: TransactionsRepository,
    private val settings: SettingsRepository,
    private val suggestions: SuggestTracer,
) {
    /** One sweep at a time; two would pay twice for the same mail. */
    private val sweepMutex = Mutex()

    /**
     * Applies one finished run. Returns what it did, for the log — nothing
     * here reports to the user, and a failure must never take the capture
     * down with it (the message row is already saved by then).
     */
    suspend fun apply(source: EventSource, ref: String, trace: SuggestTrace): AutoRecordOutcome {
        // The same message can be read twice (a reposted alert, a second
        // device sweeping the same mailbox): if the ref is already attached to
        // a transaction, the movement is recorded and there is nothing to do.
        // Cheap, and it runs before the tree read.
        if (ledger.knownSourceRefs(listOf(ref)).isNotEmpty()) {
            return AutoRecordOutcome.Skipped(AutoRecordSkip.AlreadyRecorded)
        }
        val tree = accounts.tree()
        val defaults = settings.defaultAccounts()
        return when (val plan = planAutoRecord(source, ref, trace, tree, defaults)) {
            is AutoRecordPlan.Skip -> AutoRecordOutcome.Skipped(plan.reason)
            is AutoRecordPlan.Attach -> {
                ledger.associate(listOf(plan.op))
                syncQuietly()
                AutoRecordOutcome.Associated(plan.op.transactionId)
            }
            is AutoRecordPlan.Create -> {
                val id = ledger.addAll(listOf(plan.entry)).first()
                syncQuietly()
                AutoRecordOutcome.Created(id)
            }
        }
    }

    /**
     * Reads the mail that arrived since the last sweep and records what is in
     * it. Returns one outcome per message actually put through the pipeline.
     *
     * Three things keep a mailbox from turning into a bill:
     *
     * - a **watermark** in `settings` (synced, so a second device does not pay
     *   for the same mail again), advanced row by row so a sweep cut short by
     *   a dead network resumes where it stopped instead of skipping the gap;
     * - [limit] rows per sweep — a first run on an old account must not read
     *   four thousand newsletters;
     * - [hasAmount], the same currency-anchored prefilter the embedding sweep
     *   uses. It is not a parser — the reader still decides what the message
     *   says — it only declines to spend two model calls on a mail with no
     *   money written anywhere in it.
     */
    suspend fun sweepEmails(limit: Int = EMAIL_BATCH): List<AutoRecordOutcome> = sweepMutex.withLock {
        // The rows are written server-side, so without a pull there is
        // nothing new to find.
        try {
            emails.syncNow()
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
        }
        val stored = settings.all()[EMAIL_WATERMARK_KEY]?.toLongOrNull()
        // A fresh install starts at yesterday, not at the beginning of time:
        // the backlog is the user's whole mail history, and reading it is both
        // expensive and mostly wrong — those movements are long since recorded
        // or were never worth recording.
        val from = stored ?: (epochMillis() - FIRST_SWEEP_WINDOW_MS)
        val rows = emails.since(from, limit)
        val known = ledger.knownSourceRefs(rows.map { it.id })
        val outcomes = mutableListOf<AutoRecordOutcome>()
        var watermark = from
        for (row in rows) {
            val body = row.bodyHtml ?: row.bodyText.orEmpty()
            val worthReading = row.id !in known &&
                hasAmount(decodeMimeHeader(row.subject.orEmpty()) + "\n" + body)
            if (worthReading) {
                outcomes += try {
                    apply(EventSource.Email, row.id, suggestions.traceEmail(row.id))
                } catch (e: Throwable) {
                    if (e is CancellationException) throw e
                    // Network down, session expired, worker overloaded: stop
                    // here and leave the watermark *before* this row, so the
                    // next sweep reads it instead of skipping it forever.
                    break
                }
            }
            watermark = row.receivedAt
        }
        if (watermark != stored) settings.set(EMAIL_WATERMARK_KEY, watermark.toString())
        outcomes
    }

    /**
     * The write already landed locally and the engine replicates it on the
     * next sync anyway; pushing now is a courtesy to the other devices, not a
     * condition for the row to exist.
     */
    private suspend fun syncQuietly() {
        try {
            ledger.syncNow()
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
        }
    }

    private companion object {
        /** `received_at` of the last mail looked at, in ms. Synced like any setting. */
        const val EMAIL_WATERMARK_KEY = "autorecord.email_watermark"

        const val EMAIL_BATCH = 25
        const val FIRST_SWEEP_WINDOW_MS = 24L * 60 * 60 * 1000
    }
}

/** What [AutoRecordRepository.apply] did with one run. */
sealed interface AutoRecordOutcome {
    data class Created(val transactionId: String) : AutoRecordOutcome
    data class Associated(val transactionId: String) : AutoRecordOutcome
    data class Skipped(val reason: AutoRecordSkip) : AutoRecordOutcome
}

/** Why a run wrote nothing. */
enum class AutoRecordSkip {
    /** The reader says the message is not a movement (a promotion, a login). */
    NotAMovement,

    /** No amount could be read, so there is no transaction to write. */
    NoAmount,

    /** This exact message is already attached to a transaction. */
    AlreadyRecorded,

    /** No account of the user's to post against — not even a default. */
    NoOwnAccount,

    /** No category and no fallback: the ledger has no expense/income account. */
    NoCategory,
}

/**
 * What to do with one finished run, decided without touching the database so
 * the rule can be tested. Three outcomes, in order of how sure the path is:
 * attach to an existing row, write a new one, or do nothing.
 */
sealed interface AutoRecordPlan {
    data class Skip(val reason: AutoRecordSkip) : AutoRecordPlan
    data class Attach(val op: AssociateOp) : AutoRecordPlan
    data class Create(val entry: NewTransaction) : AutoRecordPlan
}

internal fun planAutoRecord(
    source: EventSource,
    ref: String,
    trace: SuggestTrace,
    tree: List<AccountNode>,
    defaults: Map<AccountType, String>,
): AutoRecordPlan {
    val candidate = trace.candidate()
    if (trace.read?.isMovement != true) return AutoRecordPlan.Skip(AutoRecordSkip.NotAMovement)
    if (candidate == null || candidate.total == 0L) {
        return AutoRecordPlan.Skip(AutoRecordSkip.NoAmount)
    }
    if (!worthRecording(trace.read, trace.match, candidate)) {
        return AutoRecordPlan.Skip(AutoRecordSkip.AlreadyRecorded)
    }

    // Which account of the user's the money moved through. The model names it
    // when the message does ("tu cuenta Mercado Pago"); otherwise the stored
    // default is the only honest guess, and a wrong one is visible in the
    // journal and one tap to fix. The pick is checked against the live tree:
    // the run read the accounts minutes ago, and posting to an id another
    // device deleted in between writes a leg nothing can render.
    val known = tree.flatMap { it.selfAndDescendants }.mapTo(HashSet()) { it.account.id }
    val asset = candidate.accountId?.takeIf { it in known }
        ?: resolveDefault(tree, AccountType.Asset, defaults[AccountType.Asset])
        ?: return AutoRecordPlan.Skip(AutoRecordSkip.NoOwnAccount)

    val eventKey = candidate.toEvent(ref, asset).eventKey
    val sources = listOf(TransactionSource(source, ref, eventKey))

    // The movement is already in the ledger (the bot wrote it, a statement was
    // imported, the push arrived before the mail): attach this message as one
    // more origin rather than writing a second copy. Only *confident* matches
    // — an ambiguous one means "there is something that looks like this", and
    // silently merging two coffees of the same price on the same day makes
    // money disappear, while writing both leaves a duplicate the user sees.
    (trace.match as? MatchOutcome.Confident)?.let { confident ->
        val match = confident.match
        val mirror = match.relation == MatchRelation.Mirror
        return AutoRecordPlan.Attach(
            AssociateOp(
                transactionId = match.fact.transactionId,
                sources = sources,
                retargetPostingId = if (mirror) match.retargetPostingId else null,
                retargetAccountId = if (mirror) asset else null,
            ),
        )
    }

    // Expense and transfer both take money out of the account; only the far
    // leg differs, and for a transfer whose destination the model could not
    // name it degrades to the expense fallback. That miscategorizes the
    // movement, but the account balance is right — and a balance that silently
    // drifts is the one error nobody catches.
    val categoryType =
        if (candidate.direction == ImportDirection.Income) AccountType.Income else AccountType.Expense
    val fallbackCategory = resolveDefault(tree, categoryType, defaults[categoryType])
    val categories = candidate.splits.map {
        it.categoryAccountId?.takeIf { id -> id in known } ?: fallbackCategory
    }
    if (categories.any { it == null }) return AutoRecordPlan.Skip(AutoRecordSkip.NoCategory)

    val total = candidate.total
    val assetLeg = when (candidate.direction) {
        ImportDirection.Expense, ImportDirection.Transfer ->
            DraftPosting(asset, formatMinorUnits(-total), candidate.commodity)
        ImportDirection.Income -> DraftPosting(asset, formatMinorUnits(total), candidate.commodity)
    }
    val categoryLegs = candidate.splits.mapIndexed { index, split ->
        val account = categories[index]!!
        when (candidate.direction) {
            ImportDirection.Expense, ImportDirection.Transfer ->
                DraftPosting(account, formatMinorUnits(split.amountMinor), candidate.commodity)
            ImportDirection.Income ->
                DraftPosting(account, formatMinorUnits(-split.amountMinor), candidate.commodity)
        }
    }
    return AutoRecordPlan.Create(
        NewTransaction(
            date = candidate.date,
            payee = candidate.payee.trim().ifBlank { UNTITLED_PAYEE },
            note = candidate.note,
            // A push alert and a receipt both carry a real clock time, unlike
            // a statement row.
            timeKnown = true,
            drafts = listOf(assetLeg) + categoryLegs,
            sources = sources,
        ),
    )
}

/** Payee of last resort: the reader found a movement but named no merchant. */
private const val UNTITLED_PAYEE = "Movimiento"

/**
 * Whether a finished run is worth writing. Pure, so the rule is testable
 * without a database.
 *
 * [MatchOutcome.Confident] with [MatchRelation.AlreadyImported] is the one
 * match that means "this exact message is already in the ledger" — every
 * other outcome still has something to write (a new transaction, or one more
 * origin on an existing one).
 */
internal fun worthRecording(
    read: ReadResponse?,
    match: MatchOutcome?,
    candidate: ImportCandidate?,
): Boolean {
    if (read?.isMovement != true) return false
    if (candidate == null) return false
    if (match is MatchOutcome.Confident && match.match.relation == MatchRelation.AlreadyImported) {
        return false
    }
    return true
}
