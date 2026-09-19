package ar.fausto.weil

import kotlinx.serialization.Serializable

/**
 * One recognized movement, ready for the review screen: the [candidate] in the
 * same shape the document importer produces, plus where it came from.
 */
@Serializable
data class InboxCandidate(
    val candidate: ImportCandidate,
    val kind: EventSource,
    val ref: String,
    val ruleId: String,
    /** Headline of the originating message, for the review row's subtitle. */
    val title: String,
)

/**
 * Scans captured notifications and emails for movements the ledger does not
 * have yet.
 *
 * Reading is deliberately one-directional: this never writes. Everything it
 * finds goes through the same review screen the statement import uses, so a
 * push alert can only become a transaction the user (or a confident match)
 * agreed to — a parser bug costs a wrong suggestion, never a wrong ledger.
 */
class IngestRepository(
    private val notifications: NotificationsRepository,
    private val emails: EmailsRepository,
    private val accounts: AccountsRepository,
    private val ledger: TransactionsRepository,
) {

    /**
     * Movements from the last [days] of both doors, newest first, minus the
     * ones already linked to a transaction.
     *
     * The window is what keeps this cheap: the capture table holds tens of
     * thousands of rows (21.383 on the device this was built against) and
     * scanning all of them to re-offer a March notification helps nobody.
     */
    suspend fun inbox(days: Int = 30, maxRows: Int = 400): List<InboxCandidate> {
        val since = epochMillis() - days.toLong() * 86_400_000L
        val flat = accounts.tree().flatMap { it.selfAndDescendants }.map { it.account }

        val movements = mutableListOf<Pair<IngestedMovement, String>>()
        collectNotifications(since, maxRows, movements)
        collectEmails(since, maxRows, movements)

        // A message stays in the inbox until something links it, so the same
        // alert is not offered twice after the user acts on it.
        val known = ledger.knownSourceRefs(movements.map { it.first.sourceRef })
        return buildInbox(movements, flat, known)
    }

    private suspend fun collectNotifications(
        since: Long,
        maxRows: Int,
        into: MutableList<Pair<IngestedMovement, String>>,
    ) {
        var cursor: NotificationCursor? = null
        var scanned = 0
        while (scanned < maxRows) {
            val page = notifications.page(before = cursor)
            if (page.items.isEmpty()) return
            for (item in page.items) {
                if (item.postTime < since) return
                scanned++
                val movement = parseNotification(
                    id = item.id,
                    packageName = item.packageName,
                    title = item.title,
                    text = item.text,
                    postTime = item.postTime,
                ) ?: continue
                into += movement to item.title
            }
            cursor = page.nextCursor ?: return
        }
    }

    private suspend fun collectEmails(
        since: Long,
        maxRows: Int,
        into: MutableList<Pair<IngestedMovement, String>>,
    ) {
        var cursor: EmailCursor? = null
        var scanned = 0
        while (scanned < maxRows) {
            val page = emails.page(before = cursor)
            if (page.items.isEmpty()) return
            for (item in page.items) {
                if (item.receivedAt < since) return
                scanned++
                // The list page omits the bodies, and a bank receipt lives in
                // the body — so the full row is fetched, but only for mail
                // whose sender and subject already look like a receipt.
                val subject = decodeMimeHeader(item.subject.orEmpty())
                if (!looksLikeReceipt(item.fromEmail, subject)) continue
                val full = emails.get(item.id) ?: continue
                val movement = parseEmail(
                    id = full.id,
                    fromEmail = full.fromEmail,
                    subject = full.subject,
                    bodyText = full.bodyText,
                    bodyHtml = full.bodyHtml,
                    receivedAt = full.receivedAt,
                ) ?: continue
                into += movement to subject
            }
            cursor = page.nextCursor ?: return
        }
    }

    /**
     * Cheap pre-filter so a month of newsletters does not turn into a month of
     * full-body reads. Deliberately loose — the rules do the real judging.
     */
    private fun looksLikeReceipt(from: String, subject: String): Boolean =
        RECEIPT_SENDER.containsMatchIn(from) && RECEIPT_SUBJECT.containsMatchIn(subject)

    private companion object {
        val RECEIPT_SENDER = Regex("santander|mercadopago|mercadolibre|galicia|brubank|uala", RegexOption.IGNORE_CASE)
        val RECEIPT_SUBJECT = Regex(
            "consumo|pagaste|compra|débito|debito|transferencia|pago aprobado",
            RegexOption.IGNORE_CASE,
        )
    }
}
