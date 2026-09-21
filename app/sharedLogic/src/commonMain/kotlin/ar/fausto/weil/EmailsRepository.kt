package ar.fausto.weil

class Email(
    val id: String,
    val fromEmail: String,
    val subject: String?,
    val receivedAt: Long,
)

/** Last row of a page; querying strictly before it yields the next page. */
data class EmailCursor(
    val receivedAt: Long,
    val id: String,
)

data class EmailsPage(
    val items: List<Email>,
    val nextCursor: EmailCursor?,
)

/** Full row, fetched only for the detail screen — the list page skips [toEmail]/[bodyText]. */
class EmailDetail(
    val id: String,
    val fromEmail: String,
    val toEmail: String,
    val subject: String?,
    val bodyText: String?,
    /** Sanitized by the worker; null on rows ingested before HTML was captured. */
    val bodyHtml: String?,
    val receivedAt: Long,
)

class EmailsRepository(private val db: DatabaseProvider) {

    /** Pulls remote changes into the local replica. */
    suspend fun syncNow() {
        db.use { it.sync() }
    }

    /** Total rows, optionally restricted to mail that reads like a movement. */
    suspend fun count(onlyTransactions: Boolean = false): Long = db.useForRead { d ->
        val where = if (onlyTransactions) " where $TRANSACTION_FILTER" else ""
        d.query("select count(*) from emails$where", null) { rows ->
            (rows.firstOrNull()?.firstOrNull() as? Number)?.toLong() ?: 0L
        }
    }

    /**
     * Keyset-paginated page of emails, newest first. Does not sync — see
     * NotificationsRepository.page.
     *
     * [onlyTransactions] is the same cheap prefilter as the notification
     * list's — a currency sign anywhere in the subject or body — not the
     * currency-anchored [hasAmount] the auto-record sweep uses: a list filter
     * is read by a person who discards a false positive in one glance, so it
     * can afford to be looser and stay index-friendly.
     */
    suspend fun page(
        limit: Int = LIST_PAGE_SIZE,
        before: EmailCursor? = null,
        onlyTransactions: Boolean = false,
    ): EmailsPage = db.useForRead { d ->
        val where = buildList {
            if (onlyTransactions) add(TRANSACTION_FILTER)
            if (before != null) add("(received_at < :ra or (received_at = :ra and id < :id))")
        }.joinToString(" and ")
        val sql = "select id, from_email, subject, received_at from emails" +
            (if (where.isEmpty()) "" else " where $where") +
            " order by received_at desc, id desc limit $limit"
        d.query(sql, cursorParams(before)) { rows ->
            val items = rows.filter { it.size >= 4 }
                .map {
                    Email(
                        id = it[0]?.toString() ?: "",
                        fromEmail = it[1]?.toString() ?: "",
                        subject = it[2]?.toString(),
                        receivedAt = (it[3] as? Number)?.toLong() ?: 0L,
                    )
                }
                .toList()
            val next = if (items.size < limit) {
                null
            } else {
                items.lastOrNull()?.let { EmailCursor(receivedAt = it.receivedAt, id = it.id) }
            }
            EmailsPage(items, next)
        }
    }

    /**
     * Full rows received strictly after [after], **oldest first** — the
     * auto-record sweep's input. Ascending on purpose: the sweep advances a
     * watermark as it goes, so a batch cut short by a dead network resumes
     * exactly where it stopped instead of skipping the gap.
     *
     * Bodies are included because the whole point is to read them, which is
     * also why [limit] is small: each row costs two model calls downstream.
     */
    suspend fun since(after: Long, limit: Int): List<EmailDetail> = db.useForRead { d ->
        d.query(
            "select id, from_email, to_email, subject, body_text, body_html, received_at from emails" +
                " where received_at > :after order by received_at asc, id asc limit $limit",
            mapOf(":after" to after),
        ) { rows ->
            rows.filter { it.size >= 7 }.map {
                EmailDetail(
                    id = it[0]?.toString() ?: "",
                    fromEmail = it[1]?.toString() ?: "",
                    toEmail = it[2]?.toString() ?: "",
                    subject = it[3]?.toString(),
                    bodyText = it[4]?.toString(),
                    bodyHtml = it[5]?.toString(),
                    receivedAt = (it[6] as? Number)?.toLong() ?: 0L,
                )
            }.toList()
        }
    }

    /** Fetches one email's full detail (subject/body/recipient included) for the detail screen. */
    suspend fun get(id: String): EmailDetail? = db.useForRead { d ->
        d.query(
            "select id, from_email, to_email, subject, body_text, body_html, received_at from emails where id = :id",
            mapOf(":id" to id),
        ) { rows ->
            rows.firstOrNull()?.takeIf { it.size >= 7 }?.let {
                EmailDetail(
                    id = it[0]?.toString() ?: "",
                    fromEmail = it[1]?.toString() ?: "",
                    toEmail = it[2]?.toString() ?: "",
                    subject = it[3]?.toString(),
                    bodyText = it[4]?.toString(),
                    bodyHtml = it[5]?.toString(),
                    receivedAt = (it[6] as? Number)?.toLong() ?: 0L,
                )
            }
        }
    }

    private fun cursorParams(before: EmailCursor?): Map<String, Any>? =
        if (before == null) {
            null
        } else {
            buildMap {
                put(":ra", before.receivedAt)
                put(":id", before.id)
            }
        }

    private companion object {
        const val TRANSACTION_FILTER =
            "(subject like '%\$%' or body_text like '%\$%' or body_html like '%\$%')"
    }
}
