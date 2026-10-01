package ar.fausto.weil

import kotlin.math.abs

/*
 * The bank side of a broker transfer (plans/inversiones-brokers.md, decision
 * 5, «buscar la pata en el banco»). Money reaches a broker from one of the
 * user's own accounts, and that account usually saw it first: a bank push or
 * mail recorded "Transferencia a Interactive Brokers" against a category
 * (nobody knew it was a transfer), or the user typed the transfer by hand.
 * Importing the broker's deposit on top of either would count the money
 * twice, and the planner can only book it against the opening balance.
 *
 * So before a deposit is created, the review looks for that other half among
 * the stored transactions, and offers to link it instead:
 *  - [TransferLink.Kind.Mirror]: the bank's half, filed against a category or
 *    equity. Linking repoints that leg to the broker's cash account, so the
 *    two half-wrong rows become one transfer.
 *  - [TransferLink.Kind.Recorded]: the transfer is already booked into the
 *    broker's cash account (typed by hand). Linking only attaches the
 *    broker's ref, so the next sync skips it.
 * Pure, like Reconcile.kt: the screen loads the facts and decides.
 */

/** A stored transaction that is the other half of a broker transfer. */
data class TransferLink(
    val transactionId: String,
    val date: Long,
    val payee: String,
    /** The own account the money left (deposit) or reached (withdrawal). */
    val ownAccountId: String,
    val amountMinor: Long,
    val commodity: String,
    val kind: Kind,
    /** Leg to move onto the broker's cash account; null for [Kind.Recorded]. */
    val retargetPostingId: String?,
    val score: Int,
) {
    enum class Kind { Mirror, Recorded }
}

/** The cash leg of a planned transfer: which broker account, how much (signed). */
data class TransferLeg(val accountId: String, val amountMinor: Long, val commodity: String)

/** Words a bank uses for each broker in its payee, lowercase; a match nudges the score. */
private val BROKER_ALIASES = mapOf(
    IOL_PROVIDER to listOf("invertironline", "invertir online", "iol"),
    IBKR_PROVIDER to listOf("interactive brokers", "interactive", "ibkr"),
)

private const val DAY_MS = 86_400_000L

/** How far apart a bank debit and the broker's credit can be (wires take days). */
const val TRANSFER_WINDOW_DAYS = 7

/** [planned]'s leg on one of [cashAccounts], when it is a transfer. */
fun transferLeg(planned: PlannedTransaction, cashAccounts: Collection<String>): TransferLeg? {
    if (planned.kind != PlannedKind.Transfer) return null
    val postings = runCatching { resolvePostings(planned.transaction.drafts) }.getOrNull() ?: return null
    val cash = postings.singleOrNull { it.accountId in cashAccounts } ?: return null
    return TransferLeg(cash.accountId, cash.amountMinor, cash.commodity)
}

/** True when [payee] names [provider] the way a bank would. */
fun namesBroker(payee: String, provider: String): Boolean {
    val text = payee.lowercase()
    return BROKER_ALIASES[provider].orEmpty().any { alias ->
        // Whole words only: "iol" must not match "violeta".
        Regex("(^|[^a-z])" + Regex.escape(alias) + "($|[^a-z])").containsMatchIn(text)
    }
}

/**
 * Every stored transaction in [facts] that could be the other half of the
 * broker's [leg] dated [date], best first. Exact amount only: a transfer
 * moves the same money on both sides, and a near miss linked would leave the
 * broker's balance off by the difference. Transactions that already carry a
 * broker ref belong to another event and are left out, and so is anything
 * touching [brokerAccounts] other than the [Kind.Recorded] case.
 *
 * [date] null searches every fact (an IOL cash difference has no date); the
 * caller then demands [namesBroker], since the amount alone is not evidence.
 */
fun transferLinks(
    leg: TransferLeg,
    date: Long?,
    brokerAccounts: Set<String>,
    provider: String,
    facts: List<LedgerFact>,
    windowDays: Int = TRANSFER_WINDOW_DAYS,
): List<TransferLink> = facts.mapNotNull { fact ->
    if (fact.sourceRefs.any { ref -> ref.startsWith("$provider:") }) return@mapNotNull null
    val days = if (date == null) 0L else abs(dayIndex(fact.date) - dayIndex(date))
    if (days > windowDays) return@mapNotNull null
    val named = namesBroker(fact.payee, provider)
    val score = 100 - 8 * days.toInt() + (if (named) 30 else 0)

    // Already booked into the broker's cash: the same leg, same sign.
    val recorded = fact.legs.singleOrNull { it.accountId == leg.accountId }
    if (recorded != null) {
        if (recorded.amountMinor != leg.amountMinor || recorded.commodity != leg.commodity) return@mapNotNull null
        val own = fact.legs.firstOrNull { it.postingId != recorded.postingId && it.isOwn } ?: return@mapNotNull null
        if (own.accountId in brokerAccounts) return@mapNotNull null
        return@mapNotNull TransferLink(
            fact.transactionId, fact.date, fact.payee, own.accountId, own.amountMinor, own.commodity,
            TransferLink.Kind.Recorded, null, score + 20,
        )
    }
    if (fact.legs.any { it.accountId in brokerAccounts }) return@mapNotNull null

    // The bank's half: two legs, the own one moving the opposite way by the
    // same amount, the other a category or equity nobody knew was a transfer.
    if (fact.legs.size != 2) return@mapNotNull null
    val own = fact.legs.singleOrNull { it.isOwn } ?: return@mapNotNull null
    val other = fact.legs.single { it !== own }
    if (other.type != AccountType.Expense && other.type != AccountType.Income && other.type != AccountType.Equity) {
        return@mapNotNull null
    }
    if (own.commodity != leg.commodity || own.amountMinor != -leg.amountMinor) return@mapNotNull null
    TransferLink(
        fact.transactionId, fact.date, fact.payee, own.accountId, own.amountMinor, own.commodity,
        TransferLink.Kind.Mirror, other.postingId, score,
    )
}.sortedByDescending { it.score }

/**
 * The preselection: for each key its best link, no transaction given to two
 * keys (greedy by score, so the closest pairs win). Keys without one are
 * absent and keep the planner's fallback.
 */
fun <K> assignTransferLinks(candidates: Map<K, List<TransferLink>>): Map<K, TransferLink> {
    val all = candidates.flatMap { (key, links) -> links.map { key to it } }.sortedByDescending { it.second.score }
    val chosen = mutableMapOf<K, TransferLink>()
    val taken = mutableSetOf<String>()
    for ((key, link) in all) {
        if (key in chosen || link.transactionId in taken) continue
        chosen[key] = link
        taken += link.transactionId
    }
    return chosen
}

/**
 * Writes [link] as the other half of the broker event [ref] (null for an IOL
 * difference, which has no event): the broker's ref as a source, so the next
 * sync skips the event, and on a mirror the dangling leg moved to [cashAccountId].
 */
fun transferAssociation(link: TransferLink, ref: String?, cashAccountId: String): AssociateOp = AssociateOp(
    transactionId = link.transactionId,
    sources = listOfNotNull(ref?.let { TransactionSource(EventSource.Broker, it) }),
    retargetPostingId = link.retargetPostingId,
    retargetAccountId = link.retargetPostingId?.let { cashAccountId },
)

/** Search window for links: around every transfer of [plan], plus [extraDays] of history for differences. */
fun transferSearchRange(plan: BrokerPlan, now: Long, extraDays: Int = 90): LongRange {
    val dates = plan.transactions.filter { it.kind == PlannedKind.Transfer }.map { it.transaction.date }
    val from = minOf(dates.minOrNull() ?: now, now - extraDays * DAY_MS) - TRANSFER_WINDOW_DAYS * DAY_MS
    val to = maxOf(dates.maxOrNull() ?: now, now) + TRANSFER_WINDOW_DAYS * DAY_MS
    return from..to
}
