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
    private val categories: CategorySuggester? = null,
) {
    /**
     * Records [qr] and returns the new transaction id, or null when the tree
     * has no account to post to (a fresh, empty database).
     */
    suspend fun record(qr: QrPayment): RecordedQr? {
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
        val id = ledger.add(
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
        return RecordedQr(id, category)
    }

    /**
     * Asks the model which of the user's expense accounts this merchant
     * belongs to. No writes and no reads of the row being recorded, so it is
     * meant to run **concurrently** with [record]: the user is inside the
     * wallet app for ten seconds or more and the guess takes a few hundred
     * milliseconds, which is why the category can be right by the time they
     * come back without the ledger write ever waiting on the network.
     *
     * Null covers both "no suggester" and every failure — offline at a
     * counter is the normal case here, and the recorded row must not depend
     * on it.
     */
    suspend fun guessCategory(qr: QrPayment): CategorySuggestion? {
        val suggester = categories ?: return null
        val merchant = qr.merchant?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val tree = accounts.tree()
        val flat = tree.flatMap { it.selfAndDescendants }
        val paths = accountPaths(flat.map { it.account })
        // Parents included, as everywhere else: a posting can name any node.
        val options = flat.filter { it.account.type == AccountType.Expense }
            .mapNotNull { node -> paths[node.account.id]?.let { CategoryOption(node.account.id, it) } }
        if (options.isEmpty()) return null
        return suggester.suggest(
            text = merchant,
            options = options,
            kind = ImportDirection.Expense,
            // A dynamic QR carries the figure, and the same name means
            // different things at different magnitudes.
            amount = qr.amountMinor?.let { "${qr.commodity ?: Money.DEFAULT_COMMODITY} ${formatMinorUnits(it)}" },
            context = qrContext(qr.mcc),
        )
    }

    /**
     * Moves the placeholder category of [recorded] onto the guessed account.
     * Keyed on the account [record] actually wrote, so a user who already
     * fixed the row by hand keeps their pick.
     */
    suspend fun applyCategory(recorded: RecordedQr, accountId: String): Boolean =
        ledger.recategorize(recorded.transactionId, recorded.categoryId, accountId)
}

/** What [QrPayments.record] wrote: the row, and the category leg to correct. */
data class RecordedQr(val transactionId: String, val categoryId: String)

/**
 * Replaces the worker's default "the user is typing this" framing. The text
 * is not half-written here, it is a merchant name as the acquirer registered
 * it, and the QR may also declare a merchant category code — which is the
 * merchant's own trade, stated by the payment network rather than guessed
 * from a name.
 */
private fun qrContext(mcc: String?): String = buildString {
    append(
        "The merchant name printed in a payment QR the user just scanned at a counter in " +
            "Argentina. It is complete but written as the acquirer registered it: often " +
            "uppercase, abbreviated, sometimes a legal entity or a branch code instead of " +
            "the trade name.",
    )
    if (!mcc.isNullOrBlank()) {
        append(" The QR also declares ISO 18245 merchant category code ")
        append(mcc)
        append(", which states the merchant's trade directly.")
    }
}

/** Payee for a QR whose payload doesn't name the merchant (tag 59 absent). */
const val QR_FALLBACK_PAYEE = "Pago con QR"
