package ar.fausto.weil

import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

/**
 * Dev-only [Database] over plain SQLite (org.xerial:sqlite-jdbc), local file
 * at ~/.weil/dev.db. No sync, no network — this is a UI workbench, not a
 * Turso replica. See docs/desktop-target-plan.md Phase 1/2.
 */
class FakeDatabase(
    file: File = File(System.getProperty("user.home"), ".weil/dev.db"),
) : Database {
    private val conn: Connection

    init {
        file.parentFile?.mkdirs()
        Class.forName("org.sqlite.JDBC")
        conn = DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}")
        // SCHEMA_SQL is a single `;`-joined string of statements; sqlite-jdbc's
        // default Statement.execute only runs the first one, so split it here.
        SCHEMA_SQL.split(";").map { it.trim() }.filter { it.isNotEmpty() }.forEach { stmt ->
            conn.createStatement().use { it.execute(stmt) }
        }
        migrateSchema()
        seedDemoData()
    }

    override fun sync() = Unit

    override fun close() {
        conn.close()
    }

    override fun execute(sql: String, params: Map<String, Any>?) {
        prepare(sql, params).use { it.execute() }
    }

    override fun <T> query(sql: String, params: Map<String, Any>?, block: (Sequence<Row>) -> T): T {
        prepare(sql, params).use { ps ->
            ps.executeQuery().use { rs ->
                return block(rs.asSequence())
            }
        }
    }

    /** Rewrites SQL `:name` placeholders (required by the real engine) to
     * JDBC `?` positional ones, in encounter order. */
    private fun prepare(sql: String, params: Map<String, Any>?) = run {
        val order = mutableListOf<String>()
        val rewritten = Regex(":[A-Za-z_][A-Za-z0-9_]*").replace(sql) { m ->
            order += m.value
            "?"
        }
        val ps = conn.prepareStatement(rewritten)
        order.forEachIndexed { i, name ->
            ps.setObject(i + 1, params?.get(name))
        }
        ps
    }

    private fun ResultSet.asSequence(): Sequence<Row> = sequence {
        val count = metaData.columnCount
        while (next()) {
            yield((1..count).map { getObject(it) })
        }
    }

    private fun seedDemoData() {
        val count = query("select count(*) from ledger_transactions", null) { rows ->
            (rows.firstOrNull()?.firstOrNull() as? Number)?.toLong() ?: 0L
        }
        if (count > 0) return
        val assetId = "seed-asset-cash"
        execute(
            "insert or ignore into accounts(id, name, parent_id, type) values (:id, :name, null, 'asset')",
            mapOf(":id" to assetId, ":name" to "Efectivo"),
        )
        val now = System.currentTimeMillis()
        val txId = "seed-tx-1"
        execute(
            "insert into ledger_transactions(id, date, payee, note, created_at) values (:id, :date, :payee, null, :created)",
            mapOf(":id" to txId, ":date" to now, ":payee" to "Café", ":created" to now),
        )
        execute(
            "insert into postings(id, transaction_id, account_id, amount_minor, commodity) values (:id, :tx, :acct, :amount, 'ARS')",
            mapOf(":id" to "$txId-1", ":tx" to txId, ":acct" to assetId, ":amount" to -50000L),
        )
        execute(
            "insert into postings(id, transaction_id, account_id, amount_minor, commodity) values (:id, :tx, :acct, :amount, 'ARS')",
            mapOf(":id" to "$txId-2", ":tx" to txId, ":acct" to EXTERNAL_EXPENSE_ID, ":amount" to 50000L),
        )
    }
}
