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

    /**
     * Enough shapes to exercise every branch the movement rows have: a plain
     * expense, an income, an asset→asset transfer (no direction), a split, a
     * non-default commodity, and a second day so day grouping shows up.
     */
    private fun seedDemoData() {
        val count = query("select count(*) from ledger_transactions", null) { rows ->
            (rows.firstOrNull()?.firstOrNull() as? Number)?.toLong() ?: 0L
        }
        if (count > 0) return
        val cash = "seed-asset-cash"
        val bank = "seed-asset-bank"
        val food = "seed-expense-food"
        val salary = "seed-income-salary"
        fun account(id: String, name: String, type: String) = execute(
            "insert or ignore into accounts(id, name, parent_id, type) values (:id, :name, null, :type)",
            mapOf(":id" to id, ":name" to name, ":type" to type),
        )
        account(cash, "Efectivo", "asset")
        account(bank, "Banco", "asset")
        account(food, "Comida", "expense")
        account(salary, "Sueldo", "income")

        val now = System.currentTimeMillis()
        val day = 24 * 60 * 60 * 1000L
        fun tx(id: String, date: Long, payee: String, legs: List<Triple<String, Long, String>>) {
            execute(
                "insert into ledger_transactions(id, date, payee, note, created_at) values (:id, :date, :payee, null, :created)",
                mapOf(":id" to id, ":date" to date, ":payee" to payee, ":created" to date),
            )
            legs.forEachIndexed { i, (account, amount, commodity) ->
                execute(
                    "insert into postings(id, transaction_id, account_id, amount_minor, commodity) " +
                        "values (:id, :tx, :acct, :amount, :commodity)",
                    mapOf(
                        ":id" to "$id-$i",
                        ":tx" to id,
                        ":acct" to account,
                        ":amount" to amount,
                        ":commodity" to commodity,
                    ),
                )
            }
        }
        tx(
            "seed-tx-1", now - 3600_000, "Café",
            listOf(Triple(cash, -50000L, "ARS"), Triple(food, 50000L, "ARS")),
        )
        tx(
            "seed-tx-2", now - 7200_000, "Supermercado Coto de la esquina",
            listOf(
                Triple(bank, -1234500L, "ARS"),
                Triple(food, 900000L, "ARS"),
                Triple(EXTERNAL_EXPENSE_ID, 334500L, "ARS"),
            ),
        )
        tx(
            "seed-tx-3", now - day, "Sueldo enero",
            listOf(Triple(salary, -95000000L, "ARS"), Triple(bank, 95000000L, "ARS")),
        )
        tx(
            "seed-tx-4", now - day - 3600_000, "Retiro cajero",
            listOf(Triple(bank, -2000000L, "ARS"), Triple(cash, 2000000L, "ARS")),
        )
        tx(
            "seed-tx-5", now - 2 * day, "Suscripción",
            listOf(Triple(bank, -1200L, "USD"), Triple(EXTERNAL_EXPENSE_ID, 1200L, "USD")),
        )
    }
}
