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

class EmailsRepository(private val db: DatabaseProvider) {

    /** Pulls remote changes into the local replica. */
    suspend fun syncNow() {
        db.use { it.sync() }
    }

    suspend fun count(): Long = db.use { d ->
        d.query("select count(*) from emails", null) { rows ->
            (rows.firstOrNull()?.firstOrNull() as? Number)?.toLong() ?: 0L
        }
    }

    /** Keyset-paginated page of emails, newest first. Does not sync — see NotificationsRepository.page. */
    suspend fun page(limit: Int = LIST_PAGE_SIZE, before: EmailCursor? = null): EmailsPage = db.use { d ->
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
