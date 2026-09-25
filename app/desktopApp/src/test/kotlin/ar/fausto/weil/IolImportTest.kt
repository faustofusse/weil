package ar.fausto.weil

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * IOL end to end on the real repositories and SQL (plans/inversiones-brokers.md,
 * phase 4): a year of the real account's responses (the scrubbed fixtures the
 * connector's unit test uses) → connect → preview → apply → a second sync that
 * finds nothing new and nothing out of balance.
 */
class IolImportTest {

    private val sandbox: File = Files.createTempDirectory("weil-iol-test").toFile()
    private val fixtures = File("../sharedLogic/src/jvmTest/resources/iol")
    private val json = Json { ignoreUnknownKeys = true }

    private val store = JvmSecureStore(File(sandbox, "store.properties"), seedDevSession = true)
    private val graph = AppGraph(
        store = store,
        passkeys = { JvmDevPasskeys() },
        qrScanner = { null },
        dbContext = jvmDbDispatcher,
        dbFactory = { _, _, _ -> FakeDatabase(File(sandbox, "ledger.db")) },
    )

    /** Recorded IOL answers; refuses any password but "ok". */
    private inner class RecordedIol : IolSource {
        private fun text(name: String) = File(fixtures, name).readText()
        private val details: Map<Long, IolOperationDetail> =
            json.decodeFromString<List<IolOperationDetail>>(text("detalles.json")).associateBy { it.numero }
        private val titles: Map<String, IolInstrument> = json.decodeFromString(text("titulos.json"))
        var detailCalls = 0

        override suspend fun verify(candidate: IolCredentials) {
            if (candidate.password != "ok") throw IolAuthException("refused")
        }
        override suspend fun accountState(): IolAccountState = json.decodeFromString(text("estadocuenta.json"))
        override suspend fun portfolio(country: String): IolPortfolio =
            json.decodeFromString(text(if (country == "argentina") "portafolio_ar.json" else "portafolio_us.json"))
        override suspend fun operations(from: String, to: String): List<IolOperation> =
            json.decodeFromString(text("operaciones.json"))
        override suspend fun operation(numero: Long): IolOperationDetail {
            detailCalls++
            return details.getValue(numero)
        }
        override suspend fun instrument(market: String, symbol: String): IolInstrument =
            titles[symbol] ?: IolInstrument(symbol)
    }

    private val source = RecordedIol()
    private val iol = IolRepository(store, graph.brokers, graph.settings, source)

    @AfterTest
    fun cleanUp() {
        sandbox.deleteRecursively()
    }

    @Test
    fun aWrongPasswordStoresNothing() = runBlocking {
        assertFailsWith<IolAuthException> { iol.connect("user", "bad") }
        assertNull(iol.username)
        assertNull(graph.brokers.accountsFor(IOL_PROVIDER))
    }

    @Test
    fun theInvestmentsTabSeesTheConnectionAndItsLastSync() = runBlocking {
        assertEquals(emptyList(), graph.brokers.connections())
        val accounts = iol.connect("user", "ok")
        val before = graph.brokers.connections().single()
        assertEquals(IOL_PROVIDER, before.provider)
        assertEquals(accounts, before.accounts)
        assertNull(before.syncedAt)
        iol.apply(iol.preview())
        assertTrue(graph.brokers.connections().single().syncedAt != null)
        // Credentials are this device's; the connection is the account's.
        iol.disconnect()
        assertEquals(1, graph.brokers.connections().size)
    }

    @Test
    fun firstImportLandsOnIolAndTheSecondFindsNothingNew() = runBlocking {
        val accounts = iol.connect("user", "ok")
        assertEquals("user", iol.username)
        val paths = graph.accounts.tree().flatMap { it.selfAndDescendants }.associate { it.account.id to it.path }
        assertEquals("IOL:Pesos", paths[accounts.cash.getValue("ARS")])
        assertEquals("IOL:Cartera", paths[accounts.holdings])
        assertEquals("Rendimientos:Ganancias de capital", paths[accounts.capitalGains])
        assertEquals("Costos de inversión:Comisiones", paths[accounts.commissions])

        val first = iol.preview()
        assertEquals(emptyList(), first.issues)
        assertEquals(emptyList(), first.differences)
        assertEquals(44, first.transactions.size)
        assertEquals(36, source.detailCalls)

        val ids = iol.apply(first)
        assertEquals(44, ids.size)

        // What the real SQL reads back is what IOL says.
        val view = graph.brokers.ledgerView(accounts)
        assertEquals(mapOf("ARS" to 54_257_383L, "USD" to 304_966L), view.cash)
        assertEquals(HeldPosition(13L, 31_925_683L, "ARS"), view.holdings["BCBA:MELI"])
        assertEquals(HeldPosition(1_923_076L, 199_351_722L, "ARS"), view.holdings["BCBA:S13N6"])
        // Closed positions sit at zero quantity and zero cost: average cost
        // left no residue behind.
        assertEquals(0L, view.holdings.getValue("FCI:IOLPORA").quantityMinor)
        assertEquals(0L, view.holdings.getValue("FCI:IOLPORA").costMinor)
        assertEquals(4, graph.brokers.scales()["FCI:IOLCAMA"])

        // Second sync: known refs skip every event, no detail is fetched
        // again, and the ledger still agrees with IOL.
        source.detailCalls = 0
        val second = iol.preview()
        assertEquals(emptyList(), second.transactions)
        assertEquals(emptyList(), second.differences)
        assertEquals(emptyList(), second.issues)
        assertEquals(0, source.detailCalls)

        // Connecting again reuses the accounts instead of duplicating them.
        val before = graph.accounts.tree().flatMap { it.selfAndDescendants }.size
        assertEquals(accounts, iol.connect("user", "ok"))
        assertEquals(before, graph.accounts.tree().flatMap { it.selfAndDescendants }.size)
    }

    /**
     * The real sequence after an import: bank transfers IOL never reported
     * get recorded against IOL:Pesos, the opening (a remainder) no longer
     * adds up, and one adjustment settles it.
     */
    @Test
    fun aTransferRecordedLaterIsSettledAgainstTheOpening() = runBlocking {
        val accounts = iol.connect("user", "ok")
        iol.apply(iol.preview())
        val pesos = accounts.cash.getValue("ARS")
        // A withdrawal from IOL to the bank, recorded from the bank's side.
        graph.ledger.add(
            iolTime("2026-01-10T10:00:00")!!, "Transferencia a Santander", null,
            listOf(DraftPosting(pesos, "-1056293,72"), DraftPosting("seed-asset-bank", "1056293,72")),
        )

        val difference = iol.preview().differences.single()
        assertEquals(pesos, difference.accountId)
        assertEquals(105_629_372L, difference.deltaMinor)

        val id = graph.brokers.adjustOpening(accounts, difference, "Ajuste de saldo inicial IOL")
        val adjustment = graph.ledger.get(id)!!
        // Before everything else the account has: it belongs to the opening.
        assertEquals(graph.ledger.earliestDate(accounts.cash.values.toList()), adjustment.date)
        assertEquals(
            setOf(pesos to 105_629_372L, accounts.opening to -105_629_372L),
            adjustment.postings.map { it.accountId to it.amountMinor }.toSet(),
        )
        assertEquals(emptyList(), iol.preview().differences)
    }

    @Test
    fun onlyCashDifferencesCanBeAdjusted() = runBlocking<Unit> {
        val accounts = iol.connect("user", "ok")
        assertFailsWith<IllegalArgumentException> {
            graph.brokers.adjustOpening(
                accounts, BalanceDifference(accounts.holdings, "BCBA:MELI", 13L, 14L), "x",
            )
        }
    }

    @Test
    fun appliedTransactionsCarryTheirBrokerOrigin() = runBlocking {
        iol.connect("user", "ok")
        val plan = iol.preview()
        val ids = iol.apply(plan)
        val sources = graph.ledger.sourcesFor(ids).values.flatten()
        assertTrue(sources.all { it.kind == EventSource.Broker })
        assertTrue(sources.any { it.ref == "iol:185183992" }) // Compra MELI
        assertTrue(sources.any { it.ref == "iol:140633678+140633706" }) // Dólar MEP
    }
}
