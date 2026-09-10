package ar.fausto.weil

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** The five accounting categories; type is set at creation and inherited by children. */
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

data class Account(
    val id: String,
    val name: String,
    val parentId: String?,
    val type: AccountType,
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

/** Signed amount in minor units with its commodity; never use floats for money. */
data class Money(val minorUnits: Long, val commodity: String) {
    fun format(): String = formatMinorUnits(minorUnits)

    companion object {
        const val DEFAULT_COMMODITY = "ARS"

        /**
         * Parses user input into minor units (scale 2). Accepts '.' or ',' as
         * the decimal separator. A lone separator with more than 2 digits after
         * it is treated as a thousands separator ("1.234" = 1234). When both
         * appear, the rightmost one is the decimal point. Max 2 decimals.
         */
        fun parse(text: String, commodity: String): Money? {
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
                    if (fracDigits.length > 2) return null
                    intDigits to fracDigits
                }
                lastDot != -1 || lastComma != -1 -> {
                    val at = maxOf(lastDot, lastComma)
                    val after = body.substring(at + 1).filter { it.isDigit() }
                    if (after.length <= 2) {
                        body.substring(0, at).filter { it.isDigit() } to after
                    } else {
                        body.filter { it.isDigit() } to ""
                    }
                }
                else -> body to ""
            }
            if (intPart.isEmpty() && fracPart.isEmpty()) return null
            val whole = (intPart.ifEmpty { "0" }).toLongOrNull() ?: return null
            val frac = when (fracPart.length) {
                0 -> 0L
                1 -> fracPart[0].digitToInt() * 10L
                else -> fracPart[0].digitToInt() * 10L + fracPart[1].digitToInt()
            }
            val total = whole * 100 + frac
            return Money(if (negative) -total else total, commodity)
        }
    }
}

fun formatMinorUnits(units: Long): String {
    val negative = units < 0
    val magnitude = if (negative) -units else units
    val whole = magnitude / 100
    val frac = magnitude % 100
    val wholeText = whole.toString().reversed().chunked(3).joinToString(".").reversed()
    return (if (negative) "-" else "") + wholeText + "," + frac.toString().padStart(2, '0')
}

/** Commodity quick-picks shown in the editor; ARS is the default. */
val QUICK_COMMODITIES = listOf("ARS", "USD")

data class Posting(
    val id: String,
    val transactionId: String,
    val accountId: String,
    val amountMinor: Long,
    val commodity: String,
) {
    fun money() = Money(amountMinor, commodity)
}

data class Transaction(
    val id: String,
    val date: Long,
    val payee: String,
    val note: String?,
    val createdAt: Long,
    val postings: List<Posting>,
)

/** One row in an account's register: the posting plus its running balance. */
data class RegisterEntry(
    val posting: Posting,
    val date: Long,
    val payee: String,
    val balanceAfter: Money,
)

/** Editor-facing posting draft: blank amount = ledger-style elided posting. */
data class DraftPosting(
    val accountId: String?,
    val amountText: String,
    val commodity: String = Money.DEFAULT_COMMODITY,
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
): List<Posting> = buildValidated(drafts, seedTransactionId).first

@OptIn(ExperimentalUuidApi::class)
private fun buildValidated(
    drafts: List<DraftPosting>,
    seedTransactionId: String?,
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
        val money = Money.parse(amountText, draft.commodity)
            ?: throw LedgerValidationException("invalid amount: '$amountText'")
        if (money.minorUnits == 0L) throw LedgerValidationException("amounts cannot be zero")
        residuals[money.commodity] = (residuals[money.commodity] ?: 0L) + money.minorUnits
        resolved += Posting(
            id = Uuid.random().toString(),
            transactionId = seedTransactionId ?: "",
            accountId = accountId,
            amountMinor = money.minorUnits,
            commodity = money.commodity,
        )
    }

    blank?.let { empty ->
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

    for ((commodity, residual) in residuals) {
        if (residual != 0L) {
            throw LedgerValidationException("unbalanced: $commodity still off by ${formatMinorUnits(residual)}")
        }
    }
    return resolved to residuals
}

/** Per-commodity residuals of a draft set, for the editor's live footer. */
fun residualsOf(drafts: List<DraftPosting>): Map<String, Long> {
    val residuals = mutableMapOf<String, Long>()
    for (draft in drafts) {
        val amountText = draft.amountText.trim()
        if (amountText.isBlank()) continue
        val money = Money.parse(amountText, draft.commodity) ?: continue
        residuals[money.commodity] = (residuals[money.commodity] ?: 0L) + money.minorUnits
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
