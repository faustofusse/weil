package ar.fausto.weil

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** Schema v18 drops the dead `accounts.commodity`, keeping every row. */
class DropAccountCommodityTest {
    private val dir: File = Files.createTempDirectory("weil-drop").toFile()
    private val file = File(dir, "ledger.db")

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    private inline fun <T> with(db: FakeDatabase, block: FakeDatabase.() -> T): T = try { db.block() } finally { db.close() }

    private fun columns(db: Database) = db.query("pragma table_info(accounts)", null) { rows ->
        rows.mapNotNull { it.getOrNull(1)?.toString() }.toSet()
    }

    @Test
    fun anOlderDatabaseLosesTheColumnNotTheAccounts() {
        with(FakeDatabase(file)) { val db = this
            // What a v17 device had: the column, populated, and an older version.
            db.execute("alter table accounts add column commodity text", null)
            db.execute(
                "insert into accounts(id, name, parent_id, type, commodity) values('bank-usd', 'Banco USD', null, 'asset', 'USD')",
                null,
            )
            db.execute("pragma user_version = 17", null)
        }
        with(FakeDatabase(file)) { val db = this
            assertFalse("commodity" in columns(db))
            val name = db.query("select name from accounts where id = 'bank-usd'", null) { rows ->
                rows.single()[0]?.toString()
            }
            assertEquals("Banco USD", name)
        }
        // Reopening (and so re-running the migration on a v17 replay) is a no-op.
        with(FakeDatabase(file)) { val db = this
            db.execute("pragma user_version = 17", null)
        }
        with(FakeDatabase(file)) { val db = this; assertFalse("commodity" in columns(db)) }
    }
}
