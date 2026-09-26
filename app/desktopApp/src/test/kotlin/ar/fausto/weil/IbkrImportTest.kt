package ar.fausto.weil

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** IBKR end to end on the real repositories and SQL, with the real reports. */
class IbkrImportTest {

    private val sandbox: File = Files.createTempDirectory("weil-ibkr-test").toFile()
    private val fixtures = File("../sharedLogic/src/jvmTest/resources/ibkr")
    private fun bytes(name: String) = File(fixtures, name).readBytes()

    private val graph = AppGraph(
        store = JvmSecureStore(File(sandbox, "store.properties"), seedDevSession = true),
        passkeys = { JvmDevPasskeys() },
        dbContext = jvmDbDispatcher,
        dbFactory = { _, _, _ -> FakeDatabase(File(sandbox, "ledger.db")) },
    )

    @AfterTest
    fun cleanUp() {
        sandbox.deleteRecursively()
    }

    @Test
    fun theDepositThenTheBuyLandOnIbkrsNumbersWithNoGap() = runBlocking {
        val backfill = graph.ibkr.preview(bytes("backfill-deposit.xml"))
        assertNull(backfill.gap)
        assertEquals(emptyList(), backfill.plan.issues)
        val paths = graph.accounts.tree().flatMap { it.selfAndDescendants }.associate { it.account.id to it.path }
        assertEquals("IBKR:Dólares", paths[backfill.accounts.cash.getValue("USD")])
        // No in-transit account: the deposit waits on the opening balance.
        val deposit = backfill.plan.transactions.single { it.kind == PlannedKind.Transfer }
        assertTrue(deposit.transaction.drafts.any { it.accountId == backfill.accounts.opening })
        graph.ibkr.apply(backfill.plan)

        val trade = graph.ibkr.preview(bytes("trade-ttwo.xml"))
        assertNull(trade.gap)
        assertEquals(emptyList(), trade.plan.issues)
        assertEquals(emptyList(), trade.plan.differences)
        assertEquals(listOf(PlannedKind.Trade), trade.plan.transactions.map { it.kind })
        graph.ibkr.apply(trade.plan)

        val view = graph.brokers.ledgerView(trade.accounts)
        assertEquals(mapOf("USD" to 179_409L), view.cash)
        assertEquals(HeldPosition(4939L, 10_098L, "USD"), view.holdings["NASDAQ:TTWO"])
        assertEquals(listOf("2026-08-01" to "2026-09-25"), graph.ibkr.coverage())
        assertTrue(graph.brokers.connections().any { it.provider == IBKR_PROVIDER && it.syncedAt != null })

        // The same report again: nothing new, nothing off.
        val again = graph.ibkr.preview(bytes("trade-ttwo.xml"))
        assertEquals(emptyList(), again.plan.transactions)
        assertEquals(emptyList(), again.plan.differences)
    }

    @Test
    fun aReportAfterAHoleSaysWhichDaysAreMissing() = runBlocking {
        graph.ibkr.apply(graph.ibkr.preview(bytes("backfill-deposit.xml")).plan)
        val later = File(fixtures, "trade-ttwo.xml").readText().replace("fromDate=\"20260827\"", "fromDate=\"20260905\"")
        val preview = graph.ibkr.preview(later.encodeToByteArray())
        assertEquals("2026-08-27" to "2026-09-04", preview.gap)
        assertTrue(preview.plan.issues.any { "2026-08-27" in it.message })
    }
}
