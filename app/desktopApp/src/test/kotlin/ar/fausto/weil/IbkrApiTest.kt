package ar.fausto.weil

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** IBKR through the Flex Web Service, with the real report served by a fake service. */
class IbkrApiTest {

    private val sandbox: File = Files.createTempDirectory("weil-ibkr-api-test").toFile()
    private val fixtures = File("../sharedLogic/src/jvmTest/resources/ibkr")
    private val store = JvmSecureStore(File(sandbox, "store.properties"), seedDevSession = true)
    private val graph = AppGraph(
        store = store,
        passkeys = { JvmDevPasskeys() },
        dbContext = jvmDbDispatcher,
        dbFactory = { _, _, _ -> FakeDatabase(File(sandbox, "ledger.db")) },
    )

    /** Accepts token "ok"; answers [report] for every request, recording the dates asked for. */
    private inner class FakeService(var report: String) : FlexWebService {
        val ranges = mutableListOf<Pair<String?, String?>>()
        var token = "ok"
        override suspend fun sendRequest(token: String, queryId: String, from: String?, to: String?): String {
            if (token != this.token) {
                return "<FlexStatementResponse><Status>Fail</Status><ErrorCode>1015</ErrorCode><ErrorMessage>Token is invalid.</ErrorMessage></FlexStatementResponse>"
            }
            ranges += from to to
            return "<FlexStatementResponse><Status>Success</Status><ReferenceCode>1</ReferenceCode><Url>u</Url></FlexStatementResponse>"
        }
        override suspend fun getStatement(url: String, token: String, referenceCode: String): String = report
    }

    private val service = FakeService(File(fixtures, "trade-ttwo.xml").readText())
    private val ibkr = IbkrRepository(graph.brokers, graph.settings, store, service, sleep = {})
    private val now = iolTime("2026-09-26T12:00:00")!!

    @AfterTest
    fun cleanUp() {
        sandbox.deleteRecursively()
    }

    @Test
    fun aBadTokenStoresNothing() = runBlocking {
        assertFailsWith<IbkrAuthException> { ibkr.connect("bad", "1649339", now) }
        assertEquals(false, ibkr.hasToken)
        assertNull(graph.brokers.accountsFor(IBKR_PROVIDER))
    }

    @Test
    fun connectingFetchesAYearAndSyncingFollowsOn() = runBlocking {
        val first = ibkr.connect("ok", "1649339", now)
        assertEquals("1649339", ibkr.queryId)
        assertEquals("2025-09-26" to "2026-09-25", service.ranges.single())
        assertEquals(emptyList(), first.plan.issues)
        ibkr.apply(first.plan)

        // Covered up to yesterday: the query's own period is enough.
        ibkr.sync(now + 24 * 3_600_000L)
        assertEquals(null to null, service.ranges.last())

        // Two months later: the gap is asked for explicitly.
        ibkr.sync(iolTime("2026-11-30T12:00:00")!!)
        assertEquals("2026-09-26" to "2026-11-29", service.ranges.last())
    }

    @Test
    fun autoSyncWritesWhatIsRoutineAndReportsARevokedToken() = runBlocking {
        // apply() stamps the real clock, so this test runs on it.
        val now = epochMillis()
        // Nothing before a reviewed first import.
        assertNull(ibkr.autoSync(now))
        ibkr.apply(ibkr.connect("ok", "1649339", now).plan)
        assertNull(ibkr.autoSync(now + 60_000))

        val later = now + 7 * 3_600_000L
        val clean = ibkr.autoSync(later)
        assertTrue(clean is BrokerAutoSync.Applied, "$clean")
        assertEquals(emptyList(), clean.transactionIds)

        service.token = "rotated"
        assertEquals(BrokerAutoSync.WrongCredentials, ibkr.autoSync(later, force = true))
        assertEquals(BrokerAutoSync.WrongCredentials, ibkr.lastAutoSync.value)
        ibkr.disconnect()
        assertNull(ibkr.lastAutoSync.value)
        assertEquals(false, ibkr.hasToken)
    }
}
