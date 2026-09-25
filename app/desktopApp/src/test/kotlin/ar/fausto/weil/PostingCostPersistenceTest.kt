package ar.fausto.weil

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The SQL half of posting costs (plans/inversiones-brokers.md, phase 1): the
 * `@@` has to survive being written, read by every query that loads
 * postings, and rewritten by an edit. commonTest proves the balancing rule;
 * this proves the columns are actually there and actually round-trip, on
 * the same SQLite FakeDatabase the shot harness runs.
 */
class PostingCostPersistenceTest {

    private val sandbox: File = Files.createTempDirectory("weil-cost-test").toFile()
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

    private val ttwo = "NASDAQ:TTWO"
    private val cartera = "seed-asset-bank" // any account; no FK on postings
    private val cash = "seed-asset-bank-usd"

    /** The real IBKR fill: 0,4939 TTWO for US$ 99,98 plus US$ 1,00 commission. */
    private val buy = listOf(
        DraftPosting(cartera, "49,39", ttwo, costText = "99,98", costCommodity = "USD"),
        DraftPosting(EXTERNAL_EXPENSE_ID, "1,00", "USD"),
        DraftPosting(cash, "-100,98", "USD"),
    )

    private fun Transaction.stock(): Posting = postings.single { it.commodity == ttwo }

    @Test
    fun costIsWrittenAndReadByEveryQuery() = runBlocking {
        val ledger = graph().ledger
        val id = ledger.add(1_790_000_000_000L, "Compra TTWO", null, buy)

        val stored = ledger.get(id)!!
        assertEquals(4939L, stored.stock().amountMinor)
        assertEquals(9998L, stored.stock().costMinor)
        assertEquals("USD", stored.stock().costCommodity)
        assertNull(stored.postings.single { it.accountId == cash }.costMinor)

        // The journal page and the batch read feed different screens.
        assertEquals(9998L, ledger.page().single { it.id == id }.stock().costMinor)
        assertEquals(9998L, ledger.getAll(listOf(id)).getValue(id).stock().costMinor)
        // The register reads postings joined to their transaction.
        val entry = ledger.register(listOf(cartera)).single { it.posting.transactionId == id }
        assertEquals(9998L, entry.posting.costMinor)
        assertEquals(Money(4939L, ttwo), entry.balanceAfter)
    }

    /** The risk named in the plan: an edit that only touches the payee. */
    @Test
    fun anEditKeepsTheCost() = runBlocking {
        val ledger = graph().ledger
        val id = ledger.add(1_790_000_000_000L, "Compra TTWO", null, buy)
        val stored = ledger.get(id)!!

        ledger.update(id, stored.date, "Take-Two", null, stored.postings.map { it.toDraft() })

        val edited = ledger.get(id)!!
        assertEquals("Take-Two", edited.payee)
        assertEquals(9998L, edited.stock().costMinor)
        assertEquals("USD", edited.stock().costCommodity)
        assertEquals(
            stored.postings.map { Triple(it.accountId, it.amountMinor, it.costMinor) }.sortedBy { it.first },
            edited.postings.map { Triple(it.accountId, it.amountMinor, it.costMinor) }.sortedBy { it.first },
        )
    }

    /** Undo of a delete re-creates from drafts: same trip, same guarantee. */
    @Test
    fun undoOfADeleteKeepsTheCost() = runBlocking {
        val ledger = graph().ledger
        val id = ledger.add(1_790_000_000_000L, "Compra TTWO", null, buy)
        val backup = ledger.get(id)!!
        ledger.delete(id)

        val restored = ledger.addAll(
            listOf(NewTransaction(backup.date, backup.payee, backup.note, backup.postings.map { it.toDraft() })),
        ).single()
        assertEquals(9998L, ledger.get(restored)!!.stock().costMinor)
    }

    /**
     * Opening an already-migrated file again replays migrateSchema: the new
     * columns are added with addColumn, which has to tolerate them existing.
     */
    @Test
    fun migrationIsIdempotent() = runBlocking {
        val first = graph().ledger
        val id = first.add(1_790_000_000_000L, "Compra TTWO", null, buy)
        FakeDatabase(dbFile).close()
        FakeDatabase(dbFile).close()
        assertEquals(9998L, graph().ledger.get(id)!!.stock().costMinor)
    }
}
