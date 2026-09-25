package ar.fausto.weil

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Multi-select rename: only the payee changes, and Deshacer puts each one back. */
class BatchRenameTest {

    private val sandbox: File = Files.createTempDirectory("weil-rename-test").toFile()
    private val dbFile = File(sandbox, "ledger.db")

    private fun graph() = AppGraph(
        store = JvmSecureStore(File(sandbox, "store.properties"), seedDevSession = true),
        passkeys = { JvmDevPasskeys() },
        qrScanner = { null },
        dbContext = jvmDbDispatcher,
        dbFactory = { _, _, _ -> FakeDatabase(dbFile) },
    )

    @AfterTest
    fun cleanUp() {
        sandbox.deleteRecursively()
    }

    private fun drafts(amount: String) = listOf(
        DraftPosting(EXTERNAL_EXPENSE_ID, amount, "ARS"),
        DraftPosting("seed-asset-bank", "-$amount", "ARS"),
    )

    @Test
    fun renamesOnlyTheSelectionAndUndoRestoresEachPayee() = runBlocking {
        val ledger = graph().ledger
        val a = ledger.add(1_790_000_000_000L, "MERPAGO*PANADERIA", null, drafts("300"))
        val b = ledger.add(1_790_000_100_000L, "MP *PANADERIA LA", null, drafts("450"))
        val c = ledger.add(1_790_000_200_000L, "Sueldo", null, drafts("10"))

        val previous = ledger.renamePayees(listOf(a, b), "  Panadería  ")
        assertEquals(mapOf(a to "MERPAGO*PANADERIA", b to "MP *PANADERIA LA"), previous)
        assertEquals("Panadería", ledger.get(a)!!.payee)
        assertEquals("Panadería", ledger.get(b)!!.payee)
        assertEquals("Sueldo", ledger.get(c)!!.payee)
        assertEquals(30000L, ledger.get(a)!!.postings.first { it.accountId == EXTERNAL_EXPENSE_ID }.amountMinor)

        ledger.restorePayees(previous)
        assertEquals("MERPAGO*PANADERIA", ledger.get(a)!!.payee)
        assertEquals("MP *PANADERIA LA", ledger.get(b)!!.payee)
    }
}
