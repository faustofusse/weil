package ar.fausto.weil

import android.content.Context
import tech.turso.libsql.Connection
import tech.turso.libsql.EmbeddedReplicaDatabase
import tech.turso.libsql.Libsql
import java.io.File

private const val SCHEMA_SQL =
    "drop table if exists cuentas; create table if not exists accounts(id text primary key not null, name text not null);"

class AndroidDatabase(
    context: Context,
    path: String,
    url: String,
    authToken: String,
) : Database {
    private var db: EmbeddedReplicaDatabase? = null
    private var connection: Connection? = null

    init {
        val file = File(context.filesDir, path)
        file.parentFile?.mkdirs()
        db = Libsql.open(
            path = file.absolutePath,
            url = url,
            authToken = authToken,
            syncInterval = 2_000L,
            readYourWrites = true,
        )
        connection = db?.connect()
        connection?.executeBatch(SCHEMA_SQL)
    }

    override fun sync() {
        db?.sync()
    }

    override fun close() {
        connection?.close()
        db?.close()
        connection = null
        db = null
    }

    override fun execute(sql: String, params: Map<String, Any>?) {
        connection?.execute(sql, params ?: emptyMap())
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
