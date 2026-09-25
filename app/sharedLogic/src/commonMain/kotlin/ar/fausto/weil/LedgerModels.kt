package ar.fausto.weil

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.serialization.Serializable

/** The five accounting categories; type is set at creation and inherited by children. */
@Serializable
enum class AccountType(val db: String) {
    Asset("asset"),
    Liability("liability"),
    Income("income"),
    Expense("expense"),
    Equity("equity");

    companion object {
        fun fromDb(value: String?): AccountType? =
            entries.firstOrNull { it.db.equals(value, ignoreCase = true) }
    }
}

@Serializable
data class Account(
    val id: String,
    val name: String,
    val parentId: String?,
    val type: AccountType,
    /**
     * Whether this account's own balance counts toward Home's net-worth sum.
     * Only meaningful for Asset/Liability; other types are never summed
     * regardless of this flag. Excluding a parent cascades to every
     * descendant — see [LedgerState.excludedFromNetWorth].
     */
    val inNetWorth: Boolean = true,
    /**
     * Key of the icon this account shows in the UI (see `AccountIcons` in
     * sharedUI). A *key*, not a glyph: the catalog is a UI concern and can
     * grow or be re-drawn without touching stored rows, and an unknown key
     * (written by a newer app version on another device) degrades to the
     * per-type default instead of breaking the row.
     */
    val icon: String? = null,
    /**
     * The currency this account holds, when it is restricted to one
     * ("ARS", "USD"). Only meaningful for Asset/Liability; null means
     * unrestricted, which is the right answer for categories and for any
     * account the user never declared.
     *
     * Soft on purpose: it drives defaults, filtering and disambiguation, but
     * postings are never rejected for disagreeing with it — an FX transfer
     * is legitimately one transaction touching two commodities, and history
     * predates whatever the user declares today.
     */
    val commodity: String? = null,
    /**
     * Key of the palette entry this account is painted with (see
     * `AccountColors` in sharedUI), a key for the same reasons as [icon].
     * One key means two colors — a tint for the avatar disc and an ink for
     * the glyph and the label — which is why it can't be a stored hex.
     * Null means "unpainted": the neutral theme pair, i.e. what every
     * account looked like before the palette existed.
     */
    val color: String? = null,
)

/** Node of the in-memory account tree; [path] is the colon-joined chain to it. */
data class AccountNode(
    val account: Account,
    val path: String,
    val children: List<AccountNode>,
) {
    /** This node plus all descendants, depth-first (parents before children). */
    val selfAndDescendants: List<AccountNode>
        get() = listOf(this) + children.flatMap { it.selfAndDescendants }
}

/**
 * How an account is drawn once inheritance is applied: a subaccount with no
 * icon (or color) of its own wears its nearest ancestor's, so "Comida:Verduras"
 * shows Comida's glyph instead of the type's generic one. A value the account
 * stores itself is an override and always wins.
 *
 * [seed] is the id a derived (unpainted) color is computed from: the account
 * that lends the color, or the root when nobody in the chain picked one — so
 * an unpainted subcategory lands on its parent's derived color rather than on
 * a random one of its own.
 */
data class AccountLook(
    val icon: String?,
    val color: String?,
    val seed: String,
)

/** id → [AccountLook] for every node of the forest, resolved top-down. */
fun List<AccountNode>.accountLooks(): Map<String, AccountLook> {
    val out = HashMap<String, AccountLook>()
    fun walk(node: AccountNode, parent: AccountLook?) {
        val account = node.account
        val look = AccountLook(
            icon = account.icon ?: parent?.icon,
            color = account.color ?: parent?.color,
            seed = when {
                account.color != null || parent == null -> account.id
                else -> parent.seed
            },
        )
        out[account.id] = look
        node.children.forEach { walk(it, look) }
    }
    forEach { walk(it, null) }
    return out
}

/** Signed amount in minor units with its commodity; never use floats for money. */
data class Money(val minorUnits: Long, val commodity: String) {
    fun format(): String = formatMinorUnits(minorUnits)

    companion object {
        const val DEFAULT_COMMODITY = "ARS"

        /**
         * Parses user input into minor units at [scale] decimals (2 for
         * money; an instrument's own scale for a quantity: 4 for 0,4939 TTWO,
         * 0 for 13 MELI). Accepts '.' or ',' as the decimal separator. When
         * both appear, the rightmost one is the decimal point.
         *
         * A lone separator is ambiguous ("1.234"): at scale 2 it is a
         * thousands separator when more than 2 digits follow (the rule money
         * always had). At any other scale a repeated separator or a '.'
         * followed by exactly 3 digits groups thousands (es-AR), and anything
         * else is the decimal point. More decimals than [scale] is invalid,
         * never rounded.
         */
        fun parse(text: String, commodity: String, scale: Int = 2): Money? {
            require(scale >= 0) { "negative scale: $scale" }
            val cleaned = text.filter { it != ' ' && it != '\u00a0' }
            if (cleaned.isEmpty()) return null
            val negative = cleaned.startsWith("-")
            val body = cleaned.removePrefix("-")
            if (body.isEmpty() || body.any { !it.isDigit() && it != '.' && it != ',' }) return null

            val lastDot = body.lastIndexOf('.')
            val lastComma = body.lastIndexOf(',')
            val (intPart, fracPart) = when {
                lastDot != -1 && lastComma != -1 -> {
                    val at = maxOf(lastDot, lastComma)
                    val intDigits = body.substring(0, at).filter { it.isDigit() }
                    val fracDigits = body.substring(at + 1).filter { it.isDigit() }
                    if (fracDigits.length > scale) return null
                    intDigits to fracDigits
                }
                lastDot != -1 || lastComma != -1 -> {
                    val at = maxOf(lastDot, lastComma)
                    val separator = body[at]
                    val after = body.substring(at + 1).filter { it.isDigit() }
                    val decimal = when {
                        scale == 2 -> after.length <= 2
                        body.count { it == separator } > 1 -> false
                        separator == '.' && after.length == 3 -> false
                        else -> true
                    }
                    if (decimal) {
                        if (after.length > scale) return null
                        body.substring(0, at).filter { it.isDigit() } to after
                    } else {
                        body.filter { it.isDigit() } to ""
                    }
                }
                else -> body to ""
            }
            if (intPart.isEmpty() && fracPart.isEmpty()) return null
            // Digits concatenated and parsed once: exact at any scale, and an
            // amount that does not fit in a Long is invalid instead of wrapped.
            val total = ((intPart.ifEmpty { "0" }) + fracPart.padEnd(scale, '0')).toLongOrNull() ?: return null
            return Money(if (negative) -total else total, commodity)
        }
    }
}

/**
 * Minor units as es-AR text at [scale] decimals: "1.234,56" for money,
 * "0,4939" for a quantity at scale 4, "13" at scale 0. Parses back through
 * [Money.parse] at the same scale.
 */
fun formatMinorUnits(units: Long, scale: Int = 2): String {
    val negative = units < 0
    val digits = (if (negative) -units else units).toString().padStart(scale + 1, '0')
    val whole = digits.substring(0, digits.length - scale)
    val frac = digits.substring(digits.length - scale)
    val wholeText = whole.reversed().chunked(3).joinToString(".").reversed()
    return (if (negative) "-" else "") + wholeText + (if (scale == 0) "" else ",$frac")
}

/**
 * The symbol a person reads money in. USD keeps the "US" prefix on purpose:
 * in Argentina a bare "$" means pesos, so an unqualified dollar figure is not
 * ambiguous, it is wrong. Anything without a known symbol shows its code,
 * which is never worse than a guess.
 */
fun currencySymbol(commodity: String): String = when (commodity.uppercase()) {
    "ARS" -> "$"
    "USD" -> "US$"
    "EUR" -> "\u20ac"
    "BRL" -> "R$"
    else -> commodity
}

/**
 * Money as it is shown to the user: "$ 1.234,56".
 *
 * Unsigned by default, on purpose: wherever the app prints an amount it also
 * colors it (red leaving, green arriving), and a minus next to a red number
 * says the same thing twice while making every debit one character wider than
 * its neighbours. [signed] is for the few places where color can't carry it —
 * a balance that is negative by bookkeeping convention (income, liabilities)
 * is *not* painted red, so there the sign is the only cue left.
 *
 * Display only. Anything that has to be parsed back (editor drafts, the
 * amount field) keeps [formatMinorUnits], which is symbol-free by design.
 */
fun formatMoney(minorUnits: Long, commodity: String, signed: Boolean = false): String {
    val symbol = currencySymbol(commodity)
    val negative = minorUnits < 0
    val magnitude = if (negative) -minorUnits else minorUnits
    val sign = if (signed && negative) "-" else ""
    return "$sign$symbol ${formatMinorUnits(magnitude)}"
}

/** Commodity quick-picks shown in the editor; ARS is the default. */
val QUICK_COMMODITIES = listOf("ARS", "USD")

data class Posting(
    val id: String,
    val transactionId: String,
    val accountId: String,
    val amountMinor: Long,
    val commodity: String,
    /**
     * Ledger's `@@`: what this posting cost in another commodity, signed like
     * [amountMinor]. A buy of 0,4939 TTWO for US$ 99,98 is `+4939 NASDAQ:TTWO`
     * with cost `+9998 USD`. Null on every posting that is not an exchange.
     */
    val costMinor: Long? = null,
    val costCommodity: String? = null,
) {
    fun money() = Money(amountMinor, commodity)

    /**
     * What this posting counts as when the transaction is balanced: its cost
     * when it has one, else its own amount (ledger's rule, see
     * [resolvePostings]).
     */
    fun weight(): Money =
        if (costMinor != null && costCommodity != null) Money(costMinor, costCommodity) else money()

    /**
     * Back into an editable draft, cost included. Every screen that rebuilds a
     * transaction from its postings (edit, undo of a delete) goes through
     * here: dropping the cost would silently turn a buy into an unbalanced
     * row, or, worse, into one that balances by the old two-commodity
     * exception and loses its price.
     *
     * [scale] is the commodity's (see [DraftPosting.scale]); the default 2
     * round-trips any commodity exactly, a screen that shows the amount to a
     * person passes the real one.
     */
    fun toDraft(scale: Int = 2): DraftPosting = DraftPosting(
        accountId = accountId,
        amountText = formatMinorUnits(amountMinor, scale),
        commodity = commodity,
        scale = scale,
        costText = costMinor?.let { formatMinorUnits(it) }.orEmpty(),
        costCommodity = costCommodity,
    )
}

data class Transaction(
    val id: String,
    val date: Long,
    val payee: String,
    val note: String?,
    val createdAt: Long,
    val postings: List<Posting>,
    /**
     * False when only the day is known (a statement row states a date, not a
     * moment): [date] then sits at local midnight and callers must render the
     * day alone, never the time.
     */
    val timeKnown: Boolean = true,
)

/** One row in an account's register: the posting plus its running balance. */
data class RegisterEntry(
    val posting: Posting,
    val date: Long,
    val payee: String,
    val balanceAfter: Money,
    /** See [Transaction.timeKnown]. */
    val timeKnown: Boolean = true,
)

/** One transaction to create, used by batch writes (see `addAll`). */
data class NewTransaction(
    val date: Long,
    val payee: String,
    val note: String?,
    val drafts: List<DraftPosting>,
    /** See [Transaction.timeKnown]. */
    val timeKnown: Boolean = true,
    val sourceDocumentId: String? = null,
    /** Provenance rows written alongside the transaction (see `transaction_sources`). */
    val sources: List<TransactionSource> = emptyList(),
)

/**
 * An origin as stored, with when it was linked — [TransactionSource] is the
 * write shape, this is the read one.
 */
data class StoredSource(
    val kind: EventSource,
    val ref: String,
    val eventKey: String?,
    val createdAt: Long,
)

/**
 * Attaching an incoming event to a transaction that already records it.
 *
 * [retargetPostingId] completes a half-recorded transfer: the other document
 * filed this movement under an expense/income category because it could not
 * see that the counterparty was the user, and this repoints that leg at
 * [retargetAccountId] — turning two half-wrong rows into one correct transfer.
 */
data class AssociateOp(
    val transactionId: String,
    val sources: List<TransactionSource>,
    val retargetPostingId: String? = null,
    val retargetAccountId: String? = null,
)

/** Everything needed to undo one [AssociateOp] from a Snackbar. */
data class AssociationUndo(
    val transactionId: String,
    val sources: List<TransactionSource>,
    val postingId: String? = null,
    val previousAccountId: String? = null,
)

/**
 * Editor-facing posting draft: blank amount = ledger-style elided posting.
 *
 * [amountText] is read at [scale] decimals. Drafts built by code keep the
 * default 2 and carry `formatMinorUnits(minor)`, which round-trips the exact
 * minor units for any commodity (0,4939 TTWO travels as "49,39" and comes
 * back as 4939). A screen a person types into sets the commodity's real
 * scale, so the same buy reads "0,4939" there; the ledger only ever sees
 * integers either way.
 */
data class DraftPosting(
    val accountId: String?,
    val amountText: String,
    val commodity: String = Money.DEFAULT_COMMODITY,
    /** The `@@` cost, same text rules as [amountText]; blank = no cost. */
    val costText: String = "",
    val costCommodity: String? = null,
    /** Decimals [amountText] is written at; the cost is money and always at 2. */
    val scale: Int = 2,
)

class LedgerValidationException(message: String) : Exception(message)

/**
 * Balances a set of drafts: at most one elided (blank) posting overall; it
 * takes the remaining residual of its commodity. Returns fully-specified
 * postings or throws [LedgerValidationException].
 */
fun resolvePostings(
    drafts: List<DraftPosting>,
    seedTransactionId: String? = null,
    allowZero: Boolean = false,
): List<Posting> = buildValidated(drafts, seedTransactionId, allowZero).first

@OptIn(ExperimentalUuidApi::class)
private fun buildValidated(
    drafts: List<DraftPosting>,
    seedTransactionId: String?,
    /**
     * A zero amount is a typo in hand entry — except for a QR payment, where
     * the purchase is known and the figure genuinely isn't yet (the QR carries
     * no amount; the wallet's push brings it). Callers opt in explicitly.
     */
    allowZero: Boolean = false,
): Pair<List<Posting>, Map<String, Long>> {
    if (drafts.size < 2) throw LedgerValidationException("a transaction needs at least two postings")
    if (drafts.count { it.amountText.isBlank() } > 1) {
        throw LedgerValidationException("only one posting may leave its amount blank")
    }

    val residuals = mutableMapOf<String, Long>()
    val resolved = ArrayList<Posting>(drafts.size)
    var blank: DraftPosting? = null

    for (draft in drafts) {
        val accountId = draft.accountId
            ?: throw LedgerValidationException("every posting needs an account")
        val amountText = draft.amountText.trim()
        if (amountText.isBlank()) {
            if (blank != null) throw LedgerValidationException("only one posting may leave its amount blank")
            blank = draft
            continue
        }
        val money = Money.parse(amountText, draft.commodity, draft.scale)
            ?: throw LedgerValidationException("invalid amount: '$amountText'")
        if (money.minorUnits == 0L && !allowZero) throw LedgerValidationException("amounts cannot be zero")
        val cost = parseCost(draft, money)
        val posting = Posting(
            id = Uuid.random().toString(),
            transactionId = seedTransactionId ?: "",
            accountId = accountId,
            amountMinor = money.minorUnits,
            commodity = money.commodity,
            costMinor = cost?.minorUnits,
            costCommodity = cost?.commodity,
        )
        val weight = posting.weight()
        residuals[weight.commodity] = (residuals[weight.commodity] ?: 0L) + weight.minorUnits
        resolved += posting
    }

    blank?.let { empty ->
        if (empty.costText.isNotBlank()) {
            throw LedgerValidationException("the balancing posting cannot carry a cost")
        }
        val commodity = empty.commodity
        val residual = residuals[commodity] ?: 0L
        if (residual == 0L) {
            throw LedgerValidationException("the balancing posting has nothing to balance")
        }
        residuals[commodity] = 0L
        resolved += Posting(
            id = Uuid.random().toString(),
            transactionId = seedTransactionId ?: "",
            accountId = empty.accountId ?: throw LedgerValidationException("every posting needs an account"),
            amountMinor = -residual,
            commodity = commodity,
        )
    }

    // Every posting above was summed at its *weight*: the cost when it has
    // one. That is what lets a buy with a commission balance — +0,4939 TTWO
    // at cost +99,98 USD, +1,00 USD commission, -100,98 USD cash — and what
    // an exchange recorded with a cost uses too.
    //
    // A currency exchange recorded *without* a cost cannot balance per
    // commodity: pesos leave one account and dollars arrive in another, and
    // the rate lives in the row's prose, not in a posting. Its shape is unmistakable — exactly two
    // postings, one commodity each, moving in opposite directions — so it is
    // exempted narrowly. Anything else (a four-posting transaction with one
    // currency short) is still a typo and still rejected.
    // Legacy shape only: once any posting states a cost, the transaction has
    // said how it balances, and an off residual is a real error (a TTWO buy
    // priced in USD but paid from a pesos account would otherwise slip
    // through as an "exchange").
    val exchange = resolved.size == 2 &&
        resolved.none { it.costMinor != null } &&
        residuals.size == 2 &&
        residuals.values.all { it != 0L } &&
        residuals.values.map { it > 0L }.distinct().size == 2
    if (!exchange) {
        for ((commodity, residual) in residuals) {
            if (residual != 0L) {
                throw LedgerValidationException("unbalanced: $commodity still off by ${formatMinorUnits(residual)}")
            }
        }
    }
    return resolved to residuals
}

/**
 * The `@@` cost of a draft, validated against the amount it prices, or null
 * when the draft has none.
 */
private fun parseCost(draft: DraftPosting, amount: Money): Money? {
    val text = draft.costText.trim()
    if (text.isEmpty()) return null
    val commodity = draft.costCommodity?.takeIf { it.isNotBlank() }
        ?: throw LedgerValidationException("a cost needs a commodity")
    // A cost in the posting's own commodity says nothing (10 USD @@ 10 USD)
    // and would double-count it in the balance.
    if (commodity == amount.commodity) {
        throw LedgerValidationException("a cost must be in another commodity than ${amount.commodity}")
    }
    val cost = Money.parse(text, commodity)
        ?: throw LedgerValidationException("invalid cost: '$text'")
    if (cost.minorUnits == 0L) throw LedgerValidationException("a cost cannot be zero")
    // Signed like the amount: buying (+quantity) costs +money, selling
    // (-quantity) at cost removes -money. Opposite signs are a typo.
    if ((cost.minorUnits > 0) != (amount.minorUnits > 0)) {
        throw LedgerValidationException("a cost must have the sign of its amount")
    }
    return cost
}

/**
 * Per-commodity residuals of a draft set, for the editor's live footer.
 * Summed by weight, like [resolvePostings]: a posting with a cost counts in
 * its cost's commodity.
 */
fun residualsOf(drafts: List<DraftPosting>): Map<String, Long> {
    val residuals = mutableMapOf<String, Long>()
    for (draft in drafts) {
        val amountText = draft.amountText.trim()
        if (amountText.isBlank()) continue
        val money = Money.parse(amountText, draft.commodity, draft.scale) ?: continue
        val cost = draft.costCommodity?.takeIf { draft.costText.isNotBlank() }
            ?.let { Money.parse(draft.costText.trim(), it) }
        val weight = cost ?: money
        residuals[weight.commodity] = (residuals[weight.commodity] ?: 0L) + weight.minorUnits
    }
    return residuals
}

/** True when the draft set is saveable (balanced, with at most one elided posting). */
fun isValidTransaction(drafts: List<DraftPosting>): Boolean = try {
    resolvePostings(drafts)
    true
} catch (_: LedgerValidationException) {
    false
}
