package ar.fausto.weil

import android.content.Context
import tech.turso.libsql.Libsql
import tech.turso.libsql.Connection
import tech.turso.libsql.EmbeddedReplicaDatabase
import java.io.File

class AndroidDatabase(
    context: Context,
    private val url: String,
    private val authToken: String,
) : Database {
    var db: EmbeddedReplicaDatabase? = null
    var connection: Connection? = null

    init {
        db = Libsql.open(
            path = File(context.filesDir, "local.db").absolutePath,
            url = url,
            authToken = authToken,
            syncInterval = 2_000L,
            readYourWrites = true,
        )
        connection = db?.connect()
        connection?.executeBatch("create table if not exists cuentas(id text, nombre text)")
    }

    override fun sync() {
        db?.sync()
    }

    override fun execute(sql: String) {
        connection?.execute(sql)
    }

    override fun <T> query(sql: String, params: Map<String, Any>?, block: (Sequence<Row>) -> T): T {
        val conn = connection ?: return block(emptySequence())
        // Rows is a native-backed cursor (Iterable<Row> + AutoCloseable) holding
        // a raw pointer; use {} closes it when block returns — safe even if
        // block only consumes part of the sequence. libsql's Row is
        // typealias Row = List<Any?> — identical to ours, so no cast needed.
        return conn.query(sql, params ?: emptyMap()).use { rows ->
            block(rows.asSequence())
        }
    }
}