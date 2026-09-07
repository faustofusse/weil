package ar.fausto.weil

class Email(
    val id: String,
    val fromEmail: String,
    val subject: String?,
    val receivedAt: Long,
)

class EmailsRepository(private val db: DatabaseProvider) {

    suspend fun list(): List<Email> = db.use { d ->
        d.sync()
        d.query(
            "select id, from_email, subject, received_at from emails order by received_at desc, id",
            null,
        ) { rows ->
            rows.filter { it.size >= 4 }
                .map {
                    Email(
                        id = it[0]?.toString() ?: "",
                        fromEmail = it[1]?.toString() ?: "",
                        subject = it[2]?.toString(),
                        receivedAt = (it[3] as? Number)?.toLong() ?: 0L,
                    )
                }
                .toList()
        }
    }
}
