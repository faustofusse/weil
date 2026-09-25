package ar.fausto.weil

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The IOL connector against a real account's year of history (scrubbed
 * fixtures under jvmTest/resources/iol: account numbers replaced, amounts
 * real). JVM-only because it reads resource files; the code under test is
 * common.
 */
class IolConnectorTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun text(name: String): String =
        IolConnectorTest::class.java.getResource("/iol/$name")!!.readText()

    private val operations: List<IolOperation> = json.decodeFromString(text("operaciones.json"))
    private val details: List<IolOperationDetail> = json.decodeFromString(text("detalles.json"))
    private val titles: Map<String, IolInstrument> = json.decodeFromString(text("titulos.json"))

    /** Right after the last order in the fixtures. */
    private val snapshotAt = iolTime("2026-09-25T11:00:00")!!

    private fun fetch() = IolFetch(
        at = snapshotAt,
        accountState = json.decodeFromString(text("estadocuenta.json")),
        portfolios = listOf(
            json.decodeFromString(text("portafolio_ar.json")),
            json.decodeFromString(text("portafolio_us.json")),
        ),
        operations = operations,
        details = details.associateBy { it.numero },
        instruments = titles,
    )

    private val accounts = BrokerAccounts(
        cash = mapOf("ARS" to "iol-pesos", "USD" to "iol-dolares"),
        holdings = "iol-cartera",
        interest = "intereses",
        dividends = "dividendos",
        capitalGains = "ganancias",
        taxes = "impuestos",
        commissions = "comisiones",
        opening = "saldo-inicial",
        adjustments = "ajustes",
    )

    @Test
    fun amountsAreDecodedExactly() {
        val state: IolAccountState = json.decodeFromString(text("estadocuenta.json"))
        assertEquals("542573.83", state.cuentas.first { iolCurrency(it.moneda) == "ARS" }.saldo!!.toPlainString())
        val redemption = operations.single { it.numero == 190089464L }
        assertEquals("341922.4514", redemption.cantidadOperada!!.toPlainString())
    }

    @Test
    fun timesAreArgentinian() {
        // 12:10:24 in Buenos Aires is 15:10:24 UTC.
        assertEquals(1_786_720_224_000L, iolTime("2026-08-14T12:10:24"))
        assertEquals(1_786_720_224_697L, iolTime("2026-08-14T12:10:24.697"))
        assertEquals(1_786_676_400_000L, iolTime("2026-08-14"))
    }

    @Test
    fun mepAndCableBecomeCurrencyPurchases() {
        val fx = iolBatch(fetch()).events.filterIsInstance<BrokerEvent.FxConversion>().sortedBy { it.at }
        assertEquals(listOf("140633678+140633706", "143066004+143131907"), fx.map { it.ref })
        val mep = fx.first()
        assertEquals("Dólar MEP (AL30)", mep.description)
        // $ 495.291,20 + $ 2.525,99 + $ 50,02 of fees → US$ 381,86 − US$ 1,91.
        assertEquals("497867.21", mep.from.toPlainString())
        assertEquals("379.95", mep.to.toPlainString())
        assertEquals("2576.01", mep.fees.toPlainString())
        assertEquals("Dólar cable (AL30)", fx[1].description)
    }

    @Test
    fun tradesCarryTheirCurrencyAndFees() {
        val events = iolBatch(fetch()).events
        val trades = events.filterIsInstance<BrokerEvent.Trade>()
        // 36 finished trades, 4 of them consumed by the two MEP/cable pairs.
        assertEquals(32, trades.size)
        val ao27 = trades.single { it.ref == "165426177" }
        assertEquals("USD", ao27.cashCommodity)
        assertEquals("5.69", ao27.fees.toPlainString())
        assertEquals(mapOf("ARS" to Decimal.parse("161.68")!!), ao27.foreignFees)
        val rescue = trades.single { it.ref == "190089464" }
        assertEquals("FCI:IOLPORA", rescue.instrument)
        assertEquals("-341922.4514", rescue.quantity.toPlainString())
    }

    @Test
    fun paymentsKeepOnlyTheCashHalfOfEachPair() {
        val events = iolBatch(fetch()).events
        val income = events.filterIsInstance<BrokerEvent.Income>()
        assertEquals(setOf("172510909", "169032416", "154964665"), income.map { it.ref }.toSet())
        assertTrue(income.all { it.cashCommodity == "USD" })
        val principal = events.filterIsInstance<BrokerEvent.Principal>()
        assertEquals(6, principal.size)
        assertTrue(principal.all { it.quantity == null })
        assertEquals("BCBA:S14G6", principal.single { it.ref == "185135140" }.instrument)
    }

    @Test
    fun instrumentsAreKeyedByMarketAndFundsByTheirOwnNamespace() {
        val byId = iolBatch(fetch()).instruments.associateBy { it.id }
        assertEquals(InstrumentInfo("BCBA:MELI", "MELI", "Cedear Mercadolibre Inc.", "cedear", 0, 1, "ARS"), byId["BCBA:MELI"])
        assertEquals(100, byId.getValue("BCBA:S13N6").pricePer)
        assertEquals(4, byId.getValue("FCI:IOLCAMA").scale)
        assertEquals("on", byId.getValue("BCBA:MGC9O").kind)
    }

    /**
     * The whole year planned onto an empty ledger has to land exactly on
     * what IOL says today: every position and both currencies, to the cent.
     * The deposits IOL doesn't report end up in the opening balance.
     */
    @Test
    fun aYearOfHistoryLandsOnIolsSnapshot() {
        val plan = planBrokerImport(iolBatch(fetch()), accounts, BrokerLedgerView(), emptySet())
        assertEquals(emptyList(), plan.issues)
        assertEquals(emptyList(), plan.differences)
        assertEquals(PlannedKind.Opening, plan.transactions.first().kind)
        // Opening + 32 trades + 2 currency purchases + 3 coupons + 6 amortizations.
        assertEquals(1 + 32 + 2 + 3 + 6, plan.transactions.size)
        // S29G5: bought for $ 995.468,80 + $ 2.000,89, paid back $ 1.009.280.
        val s29 = plan.transactions.single { it.ref == "iol:141961954" }
        val gain = resolvePostings(s29.transaction.drafts).single { it.accountId == "ganancias" }
        assertEquals(-1_181_031L, gain.amountMinor)
    }

    @Test
    fun aSecondSyncOnlyAddsWhatIsNew() {
        val first = planBrokerImport(iolBatch(fetch()), accounts, BrokerLedgerView(), emptySet())
        val known = first.transactions.mapNotNull { it.ref }.toSet()
        // What the ledger holds after the first import, as the repository would read it.
        val ledger = BrokerLedgerView(
            cash = mapOf("ARS" to 54_257_383L, "USD" to 304_966L),
            holdings = mapOf(
                "BCBA:MELI" to HeldPosition(13L, 31_925_683L, "ARS"),
                "BCBA:S13N6" to HeldPosition(1_923_076L, 199_351_722L, "ARS"),
            ),
        )
        val second = planBrokerImport(iolBatch(fetch()), accounts, ledger, known)
        assertEquals(emptyList(), second.transactions)
        assertEquals(emptyList(), second.differences)
        assertEquals(known.size, second.skippedRefs.size)
    }
}
