package ar.fausto.weil

import kotlinx.serialization.Serializable

/**
 * Reconciliation engine: decides whether an incoming event (a statement row,
 * a push notification, an email receipt) is already in the ledger.
 *
 * Everything here is a pure function over value types — no database, no
 * network, no platform. That is deliberate: the notification listener runs in
 * a background service, often offline, against rows that have not synced yet,
 * so it cannot ask the worker "does this already exist?". See
 * docs/reconciliation.md.
 */

/** Which door an event came through. [db] is what lands in `transaction_sources.kind`. */
@Serializable
enum class EventSource(val db: String) {
    Document("document"),
    Notification("notification"),
    Email("email"),
    WhatsApp("whatsapp"),

    /**
     * A merchant QR scanned here and paid in a wallet app. The ref is the
     * payload itself. The amount on such a transaction is a placeholder (the
     * QR doesn't carry one), so it is the wallet's push that completes it.
     */
    Qr("qr"),
    Manual("manual");

    companion object {
        fun fromDb(value: String?): EventSource? =
            entries.firstOrNull { it.db.equals(value, ignoreCase = true) }
    }
}

/**
 * Provenance row: one origin of a transaction. A single purchase legitimately
 * has a notification, an email receipt and a statement row, which is why this
 * is a table and not a column.
 *
 * [ref] identifies the origin document/message (R2 content hash, notification
 * id, email id); [eventKey] is the source-independent fingerprint used to
 * recognize the same movement arriving twice.
 */
@Serializable
data class TransactionSource(
    val kind: EventSource,
    val ref: String,
    val eventKey: String? = null,
)

/**
 * Source-agnostic incoming movement, the matcher's only input shape.
 *
 * [amountMinor] is *signed from the owner's point of view*: negative when
 * money left [ownAccountId]. That single convention is what makes a duplicate
 * (same account, same sign) and a transfer mirror (other account, opposite
 * sign) fall out of one comparison.
 */
@Serializable
data class CandidateEvent(
    val source: EventSource,
    val sourceRef: String?,
    val ownAccountId: String?,
    val amountMinor: Long,
    val commodity: String,
    val date: Long,
    val rawPayee: String,
    val direction: ImportDirection,
) {
    /**
     * Fingerprint of the movement, independent of which door it arrived
     * through: two analyses of the same statement (or a statement and a CSV of
     * the same account) produce the same key, so a re-import of an overlapping
     * period is caught without any scoring.
     */
    val eventKey: String
        get() = listOf(
            ownAccountId ?: "?",
            dayIndex(date).toString(),
            amountMinor.toString(),
            commodity,
            normalizePayee(rawPayee),
        ).joinToString("|")
}

/** One movement of an existing transaction, with the type of its account. */
@Serializable
data class FactLeg(
    val postingId: String,
    val accountId: String,
    val type: AccountType?,
    val amountMinor: Long,
    val commodity: String,
) {
    /** Asset/liability: money the user owns or owes, i.e. a real account. */
    val isOwn: Boolean get() = type == AccountType.Asset || type == AccountType.Liability

    /**
     * Expense/income: a category. On a half-recorded transfer this is the
     * "dangling" leg — the side that should have been another own account and
     * got filed as spending or earnings instead.
     */
    val isCategory: Boolean get() = type == AccountType.Expense || type == AccountType.Income
}

/** An existing transaction as the matcher sees it: legs plus known origins. */
@Serializable
data class LedgerFact(
    val transactionId: String,
    val date: Long,
    val payee: String,
    val legs: List<FactLeg>,
    val eventKeys: Set<String> = emptySet(),
    val sourceRefs: Set<String> = emptySet(),
)

/** Why the matcher thinks two movements are the same; the UI translates these. */
@Serializable
enum class MatchReason {
    AlreadyImported,
    SameAmount,
    CloseAmount,
    SameDay,
    NearDay,
    SamePayee,
    SimilarPayee,
    SameAccount,
    OppositeAccount,
}

/** What kind of coincidence a match is — it decides what "associate" does. */
@Serializable
enum class MatchRelation {
    /** The transaction already carries this event's fingerprint. Nothing to do. */
    AlreadyImported,

    /** Same own account, same direction: the movement is already recorded. */
    Duplicate,

    /**
     * The other half of a transfer: the same amount left/entered a *different*
     * own account. Associating also retargets the existing dangling
     * expense/income leg to this event's account, turning two half-wrong rows
     * into one correct transfer.
     */
    Mirror,
}

data class ScoredMatch(
    val fact: LedgerFact,
    val relation: MatchRelation,
    val score: Int,
    val reasons: List<MatchReason>,
    /** Leg to repoint at the event's own account when associating a [MatchRelation.Mirror]. */
    val retargetPostingId: String? = null,
)

sealed interface MatchOutcome {
    /** Nothing comparable in the window: create the transaction. */
    data object None : MatchOutcome

    /** Good enough to default to "associate" without asking a model or the user. */
    data class Confident(val match: ScoredMatch) : MatchOutcome

    /** Plausible but not decisive: shown to the user, never applied silently. */
    data class Ambiguous(val matches: List<ScoredMatch>) : MatchOutcome
}

/**
 * Tuning for [matchEvent]. [authority] is a parameter rather than a constant
 * because arrival order is inverted per source: a statement corrects an
 * earlier notification, but a notification must not overwrite a statement.
 */
@Serializable
data class MatchPolicy(
    val dateWindowDays: Int = 3,
    /** Absolute slack, in minor units, before two amounts stop matching. */
    val amountToleranceMinor: Long = 0,
    /** Relative slack in basis points (100 = 1%), for FX/pre-auth drift. */
    val amountToleranceBps: Int = 0,
    val autoScore: Int = 85,
    val reviewScore: Int = 55,
    /** How far ahead of the runner-up a match must be to be [MatchOutcome.Confident]. */
    val decisiveMargin: Int = 15,
    val authority: MatchAuthority = MatchAuthority.Incoming,
) {
    val windowMs: Long get() = dateWindowDays.toLong() * DAY_MS
}

/** Which side wins when the two records of one event disagree. */
@Serializable
enum class MatchAuthority {
    /** The arriving event is the better record (a statement over a notification). */
    Incoming,

    /** The stored transaction wins (a notification arriving after a statement). */
    Stored,
}

private const val DAY_MS = 86_400_000L

/** Epoch ms → day bucket; matching is day-granular (documents are noon UTC). */
fun dayIndex(epochMs: Long): Long = epochMs.floorDiv(DAY_MS)

/**
 * Matches [event] against a window of existing transactions.
 *
 * [facts] should be everything within [MatchPolicy.dateWindowDays] of the
 * event (see `TransactionsRepository.reconcileFacts`); this function does no
 * IO and assumes the blocking step already narrowed the set.
 */
fun matchEvent(
    event: CandidateEvent,
    facts: List<LedgerFact>,
    policy: MatchPolicy = MatchPolicy(),
): MatchOutcome {
    val alreadyImported = facts.firstOrNull { event.eventKey in it.eventKeys }
    if (alreadyImported != null) {
        return MatchOutcome.Confident(
            ScoredMatch(
                fact = alreadyImported,
                relation = MatchRelation.AlreadyImported,
                score = Int.MAX_VALUE,
                reasons = listOf(MatchReason.AlreadyImported),
            ),
        )
    }

    val scored = facts
        .mapNotNull { score(event, it, policy) }
        .sortedByDescending { it.score }
    val best = scored.firstOrNull() ?: return MatchOutcome.None
    if (best.score < policy.reviewScore) return MatchOutcome.None
    val runnerUp = scored.getOrNull(1)?.score ?: 0
    return if (best.score >= policy.autoScore && best.score - runnerUp >= policy.decisiveMargin) {
        MatchOutcome.Confident(best)
    } else {
        MatchOutcome.Ambiguous(scored.filter { it.score >= policy.reviewScore })
    }
}

/**
 * Matches a whole batch, then enforces that **one stored transaction is
 * claimed by at most one event**.
 *
 * [matchEvent] answers about a single event, so on a statement that prints the
 * same charge four times (or four genuinely identical charges) every one of
 * them independently points at the same existing row, and confirming would
 * silently collapse four movements into one. Here the best-scoring claimant
 * keeps its confident match and the rest are demoted to suggestions, so they
 * default to *crear* and say why they might not need to be.
 */
fun matchAll(
    events: List<CandidateEvent>,
    facts: List<LedgerFact>,
    policy: MatchPolicy = MatchPolicy(),
): List<MatchOutcome> {
    val outcomes = events.map { matchEvent(it, facts, policy) }.toMutableList()
    val winner = mutableMapOf<String, Int>()
    outcomes.forEachIndexed { index, outcome ->
        if (outcome !is MatchOutcome.Confident) return@forEachIndexed
        val txId = outcome.match.fact.transactionId
        val held = winner[txId]
        val heldScore = (outcomes[held ?: index] as? MatchOutcome.Confident)?.match?.score ?: Int.MIN_VALUE
        if (held == null || outcome.match.score > heldScore) {
            winner[txId] = index
            if (held != null) outcomes[held] = demote(outcomes[held])
        } else {
            outcomes[index] = demote(outcome)
        }
    }
    return outcomes
}

/** Keeps the evidence, drops the automatic decision. */
private fun demote(outcome: MatchOutcome): MatchOutcome = when (outcome) {
    is MatchOutcome.Confident -> MatchOutcome.Ambiguous(listOf(outcome.match))
    else -> outcome
}

/** Best [ScoredMatch] between one event and one existing transaction, if any. */
private fun score(event: CandidateEvent, fact: LedgerFact, policy: MatchPolicy): ScoredMatch? {
    val days = dayIndex(event.date) - dayIndex(fact.date)
    val distance = if (days < 0) -days else days
    if (distance > policy.dateWindowDays) return null

    val candidates = fact.legs.filter { it.isOwn && it.commodity == event.commodity }
    if (candidates.isEmpty()) return null

    var best: ScoredMatch? = null
    for (leg in candidates) {
        val sameSign = (leg.amountMinor < 0) == (event.amountMinor < 0)
        val comparable = if (sameSign) leg.amountMinor else -leg.amountMinor
        val slack = tolerance(event.amountMinor, policy)
        val delta = abs(comparable - event.amountMinor)
        if (delta > slack) continue

        val sameAccount = event.ownAccountId != null && event.ownAccountId == leg.accountId
        // Same account + same direction is the same movement seen twice. The
        // other half of a transfer is the opposite sign somewhere else; when
        // the incoming account is unknown we cannot tell the two apart, so the
        // sign alone decides and the user confirms.
        val relation = when {
            sameSign && (sameAccount || event.ownAccountId == null) -> MatchRelation.Duplicate
            !sameSign && !sameAccount -> MatchRelation.Mirror
            else -> continue
        }

        val reasons = mutableListOf<MatchReason>()
        var total = 0

        if (delta == 0L) {
            total += 50
            reasons += MatchReason.SameAmount
        } else {
            // Linear decay across the allowed slack: an exact hit is worth far
            // more than one that only just fits.
            total += (50 - (50.0 * delta / (slack + 1)).toInt()).coerceAtLeast(0)
            reasons += MatchReason.CloseAmount
        }

        if (distance == 0L) {
            total += 25
            reasons += MatchReason.SameDay
        } else {
            total += (25 - 6 * distance.toInt()).coerceAtLeast(0)
            reasons += MatchReason.NearDay
        }

        when (payeeAffinity(event.rawPayee, fact.payee)) {
            PayeeAffinity.Same -> {
                total += 20
                reasons += MatchReason.SamePayee
            }
            PayeeAffinity.Contained -> {
                total += 12
                reasons += MatchReason.SimilarPayee
            }
            PayeeAffinity.SharedToken -> {
                total += 6
                reasons += MatchReason.SimilarPayee
            }
            PayeeAffinity.None -> Unit
        }

        if (sameAccount) {
            total += 10
            reasons += MatchReason.SameAccount
        } else if (relation == MatchRelation.Mirror) {
            total += 10
            reasons += MatchReason.OppositeAccount
        }

        // The leg to repoint when completing a transfer: the category side the
        // other document filed this movement under. Without one there is
        // nothing to fix, so the mirror is only a hint.
        val dangling = if (relation == MatchRelation.Mirror) {
            fact.legs.firstOrNull { it.isCategory && it.commodity == event.commodity }?.postingId
        } else {
            null
        }

        val match = ScoredMatch(fact, relation, total, reasons, dangling)
        if (best == null || match.score > best.score) best = match
    }
    return best
}

private fun tolerance(amountMinor: Long, policy: MatchPolicy): Long {
    val relative = abs(amountMinor) * policy.amountToleranceBps / 10_000
    return maxOf(policy.amountToleranceMinor, relative)
}

private fun abs(value: Long): Long = if (value < 0) -value else value

private enum class PayeeAffinity { Same, Contained, SharedToken, None }

private fun payeeAffinity(left: String, right: String): PayeeAffinity {
    val a = normalizePayee(left)
    val b = normalizePayee(right)
    if (a.isEmpty() || b.isEmpty()) return PayeeAffinity.None
    if (a == b) return PayeeAffinity.Same
    if (a.length >= 4 && b.length >= 4 && (a.contains(b) || b.contains(a))) {
        return PayeeAffinity.Contained
    }
    val tokens = payeeTokens(a)
    return if (tokens.isNotEmpty() && tokens.intersect(payeeTokens(b)).isNotEmpty()) {
        PayeeAffinity.SharedToken
    } else {
        PayeeAffinity.None
    }
}

private fun payeeTokens(normalized: String): Set<String> =
    normalized.split(' ').filter { it.length >= 3 }.toSet()

/**
 * Canonical form of a counterparty name, shared by the fingerprint, the
 * matcher and (later) the learned category priors: the same merchant must
 * collapse to one key whether it arrives as "MERPAGO*COTO 4821", "Coto CICSA"
 * or "compra en COTO".
 *
 * The noise list is intentionally modest — structural suffixes, payment-channel
 * prefixes and reference codes. Dropping more (every "pago", "compra") would
 * empty out whole rows; when the filter does empty a name, the unfiltered form
 * is kept instead so two identical rows still compare equal.
 */
fun normalizePayee(raw: String): String {
    val folded = buildString(raw.length) {
        for (ch in raw.lowercase()) {
            val mapped = ACCENTS[ch] ?: ch
            append(if (mapped.isLetterOrDigit()) mapped else ' ')
        }
    }
    val tokens = folded.split(' ').filter { it.isNotBlank() }
    val kept = tokens.filterNot { token ->
        token in NOISE_TOKENS ||
            token.all { it.isDigit() } ||
            // Reference codes: a letter or two glued to a long number
            // ("tc1520", "rg4240" keeps meaning, "x00012345" does not).
            (token.length >= 6 && token.count { it.isDigit() } >= token.length - 2)
    }
    return (if (kept.isEmpty()) tokens else kept).joinToString(" ")
}

private val ACCENTS = mapOf(
    'á' to 'a', 'à' to 'a', 'ä' to 'a', 'â' to 'a',
    'é' to 'e', 'è' to 'e', 'ë' to 'e', 'ê' to 'e',
    'í' to 'i', 'ì' to 'i', 'ï' to 'i', 'î' to 'i',
    'ó' to 'o', 'ò' to 'o', 'ö' to 'o', 'ô' to 'o',
    'ú' to 'u', 'ù' to 'u', 'ü' to 'u', 'û' to 'u',
    'ñ' to 'n', 'ç' to 'c',
)

/** Channel prefixes, card noise and legal suffixes — never the merchant itself. */
private val NOISE_TOKENS = setOf(
    "merpago", "mercadolibre", "mp", "visa", "master", "mastercard", "maestro",
    "cabal", "amex", "debin", "tarj", "tarjeta", "nro", "num", "terminada",
    "sa", "srl", "sas", "sacif", "cicsa", "ltda", "inc", "llc",
    "ar", "arg", "argentina",
)

/**
 * The event behind an import candidate. [fallbackOwnAccountId] covers rows the
 * document did not attribute to one of the user's accounts — the review
 * screen's screen-wide default.
 */
fun ImportCandidate.toEvent(
    sourceRef: String?,
    fallbackOwnAccountId: String?,
    amountMinor: Long = total,
): CandidateEvent {
    val signed = when (direction) {
        // A transfer leaves its account exactly like an expense does; only the
        // far leg differs.
        ImportDirection.Expense, ImportDirection.Transfer -> -amountMinor
        ImportDirection.Income -> amountMinor
    }
    return CandidateEvent(
        source = EventSource.Document,
        sourceRef = sourceRef,
        ownAccountId = accountId ?: fallbackOwnAccountId,
        amountMinor = signed,
        commodity = commodity,
        date = date,
        rawPayee = payee,
        direction = direction,
    )
}
