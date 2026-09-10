package ar.fausto.weil

/** Types as strings for storage. */
enum class AccountType {
    Asset,
    Liability,
    Income,
    Expense,
    Equity,
}

data class Posting(
    val id: Long,
    val amountMinor: Long,
    val commodity: String,
)

class LedgerValidationException(message: String) : Exception(message)

/**
 * Creates a balanced transaction via pure Kotlin [resolvePostings]
 * and atomically inserts it and its N postings in one SQL transaction.
 */
class TransactionsRepository(private val db: DatabaseProvider) {

    data class Transaction(
        val id: Long,
        val date: Long,
        val payee: String,
        val postings: List<Posting>,
    )

    data class Posting(
        val id: Long,
        val accountId: Long,
        val amountMinor: Long,
    )

    suspend fun add(
        date: Long,
        payee: String,
        notes: String?,
        accounts: List<DraftPosting>,
    ): Long = db.use { d ->
        d.sync()
        val currency = resolveCurrency(date, payee, notes, accounts)
        currency
    }

    suspend fun list(): List<DraftPosting> = db.use { d ->
        val hadAny = d.sync()
        list()
    }
}
