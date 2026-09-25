package ar.fausto.weil

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Subaccounts inherit icon/color unless they override; redundant copies are cleaned up. */
class AccountLooksTest {

    private val sandbox: File = Files.createTempDirectory("weil-looks-test").toFile()

    @AfterTest
    fun cleanUp() {
        sandbox.deleteRecursively()
    }

    private fun acc(id: String, parent: String? = null, icon: String? = null, color: String? = null) =
        Account(id = id, name = id, parentId = parent, type = AccountType.Expense, icon = icon, color = color)

    @Test
    fun inheritsFromNearestAncestorAndOverridesWin() {
        val looks = AccountsRepository.buildTree(
            listOf(
                acc("food", icon = "food", color = "terracota"),
                acc("meat", parent = "food"),
                acc("grill", parent = "meat"),
                acc("super", parent = "food", icon = "cart"),
                acc("plain"),
                acc("plainChild", parent = "plain"),
                acc("orphan", parent = "missing"),
            ),
        ).accountLooks()

        assertEquals(AccountLook("food", "terracota", "food"), looks["food"])
        assertEquals(AccountLook("food", "terracota", "food"), looks["meat"])
        assertEquals(AccountLook("food", "terracota", "food"), looks["grill"])
        assertEquals(AccountLook("cart", "terracota", "food"), looks["super"])
        // No icon or color anywhere: null icon (type default), derived color
        // seeded from the root so parent and child match.
        assertEquals(AccountLook(null, null, "plain"), looks["plainChild"])
        // A missing parent makes it a root.
        assertEquals(AccountLook(null, null, "orphan"), looks["orphan"])
    }

    @Test
    fun migrationClearsOnlyCopiesOfTheParent() {
        val db = FakeDatabase(File(sandbox, "ledger.db"))
        fun insert(id: String, parent: String?, icon: String?, color: String?) = db.execute(
            "insert into accounts(id, name, parent_id, type, icon, color) " +
                "values (:id, :id, ${if (parent == null) "null" else ":parent"}, 'expense', " +
                "${if (icon == null) "null" else ":icon"}, ${if (color == null) "null" else ":color"})",
            buildMap {
                put(":id", id)
                parent?.let { put(":parent", it) }
                icon?.let { put(":icon", it) }
                color?.let { put(":color", it) }
            },
        )
        insert("p", null, "food", "azul")
        insert("copy", "p", "food", "azul")
        insert("own", "p", "cart", "rojo")
        insert("mixed", "p", "food", "rojo")

        db.migrateSchema()

        fun look(id: String) = db.query("select icon, color from accounts where id = :id", mapOf(":id" to id)).single()
        assertEquals(listOf<Any?>("food", "azul"), look("p"))
        assertEquals(listOf<Any?>(null, null), look("copy"))
        assertEquals(listOf<Any?>("cart", "rojo"), look("own"))
        assertNull(look("mixed")[0])
        assertEquals("rojo", look("mixed")[1])
        db.close()
    }
}
