package ar.fausto.weil

/**
 * Writes the ledger entry for a QR the user just paid in a wallet app.
 *
 * MP's QRs carry no amount (it lives in the order behind the payload), so the
 * row lands with the amount the QR happened to declare or **zero** — a
 * placeholder that says "this purchase happened, the number is coming". The
 * wallet's push notification arrives seconds later with the real figure; the
 * `kind='qr'` provenance row is the handle that lets a later pass find this
 * transaction and correct it instead of creating a second one.
 *
 * Zero rather than "no posting": a transaction must balance and its postings
 * must name the accounts, so the shape has to be complete from the start. An
 * amount of 0,00 is also the most visible possible reminder in the journal.
 */
class QrPayments(
    private val accounts: AccountsRepository,
    private val settings: SettingsRepository,
    private val ledger: TransactionsRepository,
) {
    /**
     * Records [qr] and returns the new transaction id, or null when the tree
     * has no account to post to (a fresh, empty database).
     */
    suspend fun record(qr: QrPayment): String? {
        val tree = accounts.tree()
        if (tree.isEmpty()) return null
        val stored = settings.defaultAccounts()
        val asset = resolveDefault(tree, AccountType.Asset, stored[AccountType.Asset]) ?: return null
        // resolveDefault refuses to guess a category when there are several
        // candidates; the seeded "External" account is the honest landing spot
        // until the user recategorizes.
        val category = resolveDefault(tree, AccountType.Expense, stored[AccountType.Expense])
            ?: EXTERNAL_EXPENSE_ID
        val amount = qr.amountMinor ?: 0L
        return ledger.add(
            date = epochMillis(),
            payee = qr.merchant?.takeIf { it.isNotBlank() } ?: QR_FALLBACK_PAYEE,
            note = null,
            drafts = listOf(
                DraftPosting(asset, formatMinorUnits(-amount)),
                DraftPosting(category, formatMinorUnits(amount)),
            ),
            // The payload is the ref: unique per order on dynamic QRs, so the
            // pass that later reconciles the push can key on it.
            sources = listOf(TransactionSource(EventSource.Qr, qr.raw)),
            allowZeroAmounts = true,
        )
    }
}

/** Payee for a QR whose payload doesn't name the merchant (tag 59 absent). */
const val QR_FALLBACK_PAYEE = "Pago con QR"
