package ar.fausto.weil

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class AccountsRepository(private val db: DatabaseProvider) {

    suspend fun list(): List<Account> = db.use { d ->
        d.sync()
        d.query("select id, name from accounts order by lower(name), id", null) { rows ->
            rows.filter { it.size >= 2 }
                .map { Account(it[0]?.toString() ?: "", it[1]?.toString() ?: "") }
                .toList()
        }
    }

    suspend fun add(name: String) {
        db.use { d ->
            d.execute(
                "insert into accounts(id, name) values(:id, :name)",
                mapOf(":id" to Uuid.random().toString(), ":name" to name),
            )
            d.sync()
        }
    }

    suspend fun rename(id: String, name: String) {
        db.use { d ->
            d.execute(
                "update accounts set name = :name where id = :id",
                mapOf(":name" to name, ":id" to id),
            )
            d.sync()
        }
    }

    suspend fun delete(id: String) {
        db.use { d ->
            d.execute("delete from accounts where id = :id", mapOf(":id" to id))
            d.sync()
        }
    }
}
