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
    val receivedAt: Long,
)

class EmailsRepository(private val db: DatabaseProvider) {

    /** Pulls remote changes into the local replica. */
    suspend fun syncNow() {
        db.use { it.sync() }
    }

    suspend fun count(): Long = db.useForRead { d ->
        d.query("select count(*) from emails", null) { rows ->
            (rows.firstOrNull()?.firstOrNull() as? Number)?.toLong() ?: 0L
        }
    }

    /** Keyset-paginated page of emails, newest first. Does not sync — see NotificationsRepository.page. */
    suspend fun page(limit: Int = LIST_PAGE_SIZE, before: EmailCursor? = null): EmailsPage = db.useForRead { d ->
        val where = if (before == null) {
            ""
        } else {
            " where (received_at < :ra or (received_at = :ra and id < :id))"
        }
        val sql = "select id, from_email, subject, received_at from emails$where" +
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

    /** Fetches one email's full detail (subject/body/recipient included) for the detail screen. */
    suspend fun get(id: String): EmailDetail? = db.useForRead { d ->
        d.query(
            "select id, from_email, to_email, subject, body_text, received_at from emails where id = :id",
            mapOf(":id" to id),
        ) { rows ->
            rows.firstOrNull()?.takeIf { it.size >= 6 }?.let {
                EmailDetail(
                    id = it[0]?.toString() ?: "",
                    fromEmail = it[1]?.toString() ?: "",
                    toEmail = it[2]?.toString() ?: "",
                    subject = it[3]?.toString(),
                    bodyText = it[4]?.toString(),
                    receivedAt = (it[5] as? Number)?.toLong() ?: 0L,
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
}
