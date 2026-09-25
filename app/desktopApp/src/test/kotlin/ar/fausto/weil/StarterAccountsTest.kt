package ar.fausto.weil

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A fresh ledger gets the starter tree once, and never gets it back. */
class StarterAccountsTest {

    private val sandbox: File = Files.createTempDirectory("weil-starter-test").toFile()

    @AfterTest
    fun cleanUp() {
        sandbox.deleteRecursively()
    }

    /** FakeDatabase paints its demo over the seed; wipe it to get a fresh ledger. */
    private fun freshLedger(): FakeDatabase = FakeDatabase(File(sandbox, "ledger.db")).apply {
        // Postings too: the demo's rows point at the seeded "Otros", and
        // adoptOrphanSeedPostings (rightly) recreates it for them.
        execute("delete from postings")
        execute("delete from accounts")
        execute("delete from settings")
        migrateSchema()
    }

    private fun Database.paths(): Set<String> {
        val rows = query("select id, name, parent_id, type from accounts", null) { r -> r.toList() }
        val byId = rows.associateBy { it[0].toString() }
        fun path(id: String): String {
            val row = byId.getValue(id)
            val parent = row[2]?.toString()
            return if (parent == null) "${row[3]}:${row[1]}" else "${path(parent)}:${row[1]}"
        }
        return byId.keys.map(::path).toSet()
    }

    @Test
    fun seedsTheStarterTreeOnAnEmptyLedger() {
        val db = freshLedger()
        val paths = db.paths()
        assertEquals(24, paths.size)
        assertTrue("asset:Cuenta bancaria:Pesos" in paths)
        assertTrue("asset:Cuenta bancaria:Dólares" in paths)
        assertTrue("liability:Tarjeta de crédito" in paths)
        assertTrue("expense:Vivienda:Alquiler" in paths)
        assertTrue("expense:$EXTERNAL_ACCOUNT_NAME" in paths)
        assertTrue("income:$EXTERNAL_ACCOUNT_NAME" in paths)
        val defaults = db.query("select key, value from settings", null) { r ->
            r.associate { it[0].toString() to it[1].toString() }
        }
        assertEquals(EXTERNAL_EXPENSE_ID, defaults[defaultAccountKey(AccountType.Expense)])
        assertEquals(EXTERNAL_INCOME_ID, defaults[defaultAccountKey(AccountType.Income)])
        db.close()
    }

    @Test
    fun neverResurrectsDeletedAccounts() {
        val db = freshLedger()
        db.execute("delete from accounts where id <> 'seed-asset-cash'")
        db.migrateSchema()
        assertEquals(setOf("asset:Efectivo"), db.paths())
        db.close()
    }
}
