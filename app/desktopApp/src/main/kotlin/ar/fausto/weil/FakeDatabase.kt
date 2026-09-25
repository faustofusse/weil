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
        registerVectorFunctions(conn)
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
        val count = query("select count(*) from transactions", null) { rows ->
            (rows.firstOrNull()?.firstOrNull() as? Number)?.toLong() ?: 0L
        }
        if (count > 0) return
        val cash = "seed-asset-cash"
        val bank = "seed-asset-bank"
        val food = "seed-expense-food"
        val salary = "seed-income-salary"
        // `insert or replace`: migrateSchema() already seeded the starter tree
        // under some of these ids, and the demo's shapes (painted categories)
        // have to win over it.
        fun account(
            id: String,
            name: String,
            type: String,
            icon: String? = null,
            color: String? = null,
        ) = execute(
            "insert or replace into accounts(id, name, parent_id, type, icon, color) " +
                "values (:id, :name, null, :type, " +
                "${if (icon == null) "null" else ":icon"}, " +
                "${if (color == null) "null" else ":color"})",
            mapOf(":id" to id, ":name" to name, ":type" to type) +
                (icon?.let { mapOf(":icon" to it) } ?: emptyMap()) +
                (color?.let { mapOf(":color" to it) } ?: emptyMap()),
        )
        // Cached display name: the harness has no reachable auth worker, and
        // the greeting reads from this mirror first (see UserState).
        execute(
            "insert or replace into settings(key, value, updated_at) values ('profile.name', 'Agostina', :at)",
            mapOf(":at" to System.currentTimeMillis()),
        )
        account(cash, "Efectivo", "asset")
        account(bank, "Banco", "asset")
        account("seed-asset-bank-usd", "Banco USD", "asset")
        // Painted, so the shots show both halves of a palette entry; the
        // income stays unpainted, which is the fallback look.
        account(food, "Comida", "expense", icon = "food", color = "terracota")
        account("seed-expense-transport", "Transporte", "expense", icon = "car", color = "rojo")
        account("seed-expense-home", "Hogar", "expense", icon = "home", color = "azul")
        account("seed-expense-fun", "Ocio", "expense", icon = "gift", color = "lila")
        account(salary, "Sueldo", "income")
        // Children of Comida, so the category screen has chips to filter by.
        // Two inherit Comida's icon and color (null); Supermercado overrides
        // the icon, so the shots show both halves of the rule.
        fun child(id: String, name: String, parent: String, icon: String? = null) = execute(
            "insert or replace into accounts(id, name, parent_id, type, icon) " +
                "values (:id, :name, :parent, 'expense', ${if (icon == null) "null" else ":icon"})",
            buildMap {
                put(":id", id); put(":name", name); put(":parent", parent)
                if (icon != null) put(":icon", icon)
            },
        )
        child("seed-expense-food-super", "Supermercado", food, "cart")
        child("seed-expense-food-meat", "Carnicería", food)
        child("seed-expense-food-bakery", "Panadería", food)

        val now = System.currentTimeMillis()
        val day = 24 * 60 * 60 * 1000L
        fun tx(
            id: String,
            date: Long,
            payee: String,
            legs: List<Triple<String, Long, String>>,
            timeKnown: Boolean = true,
        ) {
            execute(
                "insert into transactions(id, date, payee, note, created_at, time_known)" +
                    " values (:id, :date, :payee, null, :created, :time_known)",
                mapOf(
                    ":id" to id,
                    ":date" to date,
                    ":payee" to payee,
                    ":created" to date,
                    ":time_known" to if (timeKnown) 1L else 0L,
                ),
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
        // Posted to a *child* category, which is what the category screen's
        // chip filter narrows to — and what nothing else in the seed covers.
        tx(
            "seed-tx-sub", now - 5400_000, "Coto",
            listOf(
                Triple(bank, -780000L, "ARS"),
                Triple("seed-expense-food-super", 780000L, "ARS"),
            ),
        )
        // On a child with no icon/color of its own: it must wear Comida's.
        tx(
            "seed-tx-sub-inherit", now - 6000_000, "Carnicería del barrio",
            listOf(
                Triple(cash, -420000L, "ARS"),
                Triple("seed-expense-food-meat", 420000L, "ARS"),
            ),
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
        // Imported from a statement: the document stated a day and no time, so
        // this row is dated at local midnight with time_known = 0 — the
        // harness's coverage of registers/detail that must print no hour.
        tx(
            "seed-tx-5",
            java.time.LocalDate.now().minusDays(2)
                .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(),
            "Suscripción",
            listOf(Triple(bank, -1200L, "USD"), Triple(EXTERNAL_EXPENSE_ID, 1200L, "USD")),
            timeKnown = false,
        )
        // Half-recorded transfer: the statement of the receiving account could
        // not tell the payer was the user, so the far leg landed on a category.
        // The import harness ships the other half of it, which is what makes
        // the review screen's "otra mitad de un traspaso" banner show up.
        tx(
            "seed-tx-6", now - 2 * day, "Transferencia recibida",
            listOf(Triple(bank, 20_000_000L, "ARS"), Triple(EXTERNAL_INCOME_ID, -20_000_000L, "ARS")),
        )

        // Provenance for a row that arrived twice: the push alert first, the
        // statement later. The detail screen lists both.
        fun source(tx: String, kind: String, ref: String, at: Long) = execute(
            "insert or ignore into transaction_sources(transaction_id, kind, ref, event_key, created_at)" +
                " values (:tx, :kind, :ref, null, :at)",
            mapOf(":tx" to tx, ":kind" to kind, ":ref" to ref, ":at" to at),
        )
        source("seed-tx-2", "notification", "seed-notif-x", now - 7200_000)
        source("seed-tx-2", "document", "seed-doc-x", now - 3600_000)

        account("seed-asset-mp", "Mercado Pago", "asset")
        // Verbatim captures from a real device, so the inbox harness exercises
        // the actual sentences the rules are written against — including the
        // promotion that must stay out of it.
        fun notification(id: String, pkg: String, title: String, text: String, at: Long) = execute(
            "insert or ignore into notifications(id, package_name, title, text, category, post_time, received_at)" +
                " values (:id, :pkg, :title, :text, null, :at, :at)",
            mapOf(":id" to id, ":pkg" to pkg, ":title" to title, ":text" to text, ":at" to at),
        )
        notification(
            "seed-notif-1", "com.mercadopago.wallet",
            "Pagaste a Spotify", "Debitamos $ 5.895,57 de tu cuenta.", now - 3 * day,
        )
        notification(
            "seed-notif-2", "com.mercadopago.wallet",
            "Recibiste $ 15.000",
            "Luciano Ramiro Veiga te envi\u00f3 dinero y ya est\u00e1 generando rendimientos en tu cuenta.",
            now - 4 * day,
        )
        // The mirror of seed-tx-6: that transaction records the money
        // *arriving* (filed as plain income because the receiving statement
        // could not tell the payer was the user), and this alert is the same
        // amount *leaving* the other account — so the review screen should
        // offer to complete the transfer instead of inventing a second row.
        notification(
            "seed-notif-3", "ar.com.santander.rio.mbanking",
            "Transferiste con \u00e9xito", "Enviaste $ 200.000,00 a Fausto Fusse.", now - 2 * day,
        )
        notification(
            "seed-notif-4", "com.mercadopago.wallet",
            "\u00a115% OFF en Carrefour! \ud83d\ude31",
            "Aprovech\u00e1 hoy \u00a1Sin tope! Llen\u00e1 el carrito Compra m\u00ednima: $15.000",
            now - 2 * day,
        )
        execute(
            "insert or ignore into emails(id, from_email, to_email, subject, body_text, body_html, received_at)" +
                " values (:id, :from, 'wallet@fausto.ar', :subject, null, :html, :at)",
            mapOf(
                ":id" to "seed-email-1",
                ":from" to "mensajesyavisos@mails.santander.com.ar",
                ":subject" to "Aviso de consumo",
                ":html" to "<style>.t { color: #767676 }</style>" +
                    "<p>Tarjeta Santander Visa Cr\u00e9dito terminada en 1500</p>" +
                    "<td>Monto U\$S21,23</td><td>Cuotas 1</td><td>Comercio ANOMALY</td><td>Fecha 28/08/2026</td>",
                ":at" to now - 5 * day,
            ),
        )
    }
}
