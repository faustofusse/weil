package ar.fausto.weil

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * One BYMA security on several trading lines (AAPL in pesos, AAPLD in MEP
 * dollars). The case is a real IOL account: CEDEARs and bonds bought on
 * their dollar lines were counted twice, once as the dollar line's buys and
 * once more inside the opening, because the portfolio reports them under
 * the peso ticker.
 */
class TradingLinesTest {

    private fun d(text: String) = Decimal.parse(text)!!

    private val accounts = BrokerAccounts(
        cash = mapOf("ARS" to "cash-ars", "USD" to "cash-usd"),
        holdings = "cartera",
        interest = "interes",
        dividends = "dividendos",
        capitalGains = "ganancias",
        taxes = "impuestos",
        commissions = "comisiones",
        opening = "saldo-inicial",
        adjustments = "ajustes",
    )

    private val aapl = InstrumentInfo("BCBA:AAPL", "AAPL", kind = "cedear", scale = 0, quoteCommodity = "ARS")
    private val aapld = InstrumentInfo("BCBA:AAPLD", "AAPLD", kind = "cedear", scale = 0, quoteCommodity = "USD")
    private val irsa = InstrumentInfo("BCBA:IRSA", "IRSA", kind = "stock", scale = 0, quoteCommodity = "ARS")
    private val irsad = InstrumentInfo("BCBA:IRSAD", "IRSAD", kind = "stock", scale = 0, quoteCommodity = "USD")
    private val aec1o = InstrumentInfo("BCBA:AEC1O", "AEC1O", kind = "other", scale = 0)
    private val aec1d = InstrumentInfo("BCBA:AEC1D", "AEC1D", kind = "on", scale = 0, pricePer = 100, quoteCommodity = "USD")
    private val ypfd = InstrumentInfo("BCBA:YPFD", "YPFD", kind = "stock", scale = 0, quoteCommodity = "ARS")
    private val ypf = InstrumentInfo("NYSE:YPF", "YPF", kind = "stock", scale = 0, quoteCommodity = "USD")

    private val all = listOf(aapl, aapld, irsa, irsad, aec1o, aec1d, ypfd, ypf)

    private val t0 = 1_790_000_000_000L

    private fun PlannedTransaction.lines(): Set<List<Any?>> =
        resolvePostings(transaction.drafts).map { listOf(it.accountId, it.amountMinor, it.commodity, it.costMinor) }.toSet()

    private fun trade(ref: String, at: Long, instrument: String, quantity: String, gross: String, cash: String) =
        BrokerEvent.Trade(ref, at, true, "Trade $instrument", instrument, d(quantity), d(gross), cash)

    @Test
    fun dollarLinesMapToTheirSecurity() {
        val lines = tradingLines(all)
        assertEquals("BCBA:AAPL", lines["BCBA:AAPLD"])
        assertEquals("BCBA:IRSA", lines["BCBA:IRSAD"])
        // An ON's peso line ends in O.
        assertEquals("BCBA:AEC1O", lines["BCBA:AEC1D"])
        // YPFD is YPF's peso ticker, not a line of anything; other markets never are.
        assertNull(lines["BCBA:YPFD"])
        assertNull(lines["NYSE:YPF"])
        assertEquals(3, lines.size)
    }

    /** The reported case: 3 AAPL bought in pesos, 8 AAPLD in dollars, the portfolio says 11 AAPL. */
    @Test
    fun theOpeningCountsEveryLineOfTheSecurity() {
        val batch = BrokerBatch(
            "iol",
            listOf(
                trade("1", t0 + 1, "BCBA:AAPLD", "8", "98.59", "USD"),
                trade("2", t0 + 2, "BCBA:AAPL", "3", "35723.97", "ARS"),
            ),
            BrokerSnapshot(
                t0 + 10,
                mapOf("ARS" to d("0"), "USD" to d("0")),
                listOf(SnapshotPosition("BCBA:AAPL", d("11"), price = d("12800"))),
            ),
            listOf(aapl, aapld),
        )
        val result = planBrokerImport(batch, accounts, BrokerLedgerView(), emptySet())
        val opening = result.transactions.firstOrNull { it.kind == PlannedKind.Opening }
        // No units before the first buy: only the cash that paid for them.
        assertTrue(opening == null || opening.lines().none { it[2] == "BCBA:AAPL" || it[2] == "BCBA:AAPLD" }, opening?.lines().toString())
        assertTrue(result.differences.none { it.accountId == "cartera" }, result.differences.toString())
    }

    /** What an unfixed ledger looks like to the planner now: the security disagrees, by the double count. */
    @Test
    fun differencesAreBySecurity() {
        val batch = BrokerBatch(
            "iol",
            emptyList(),
            BrokerSnapshot(t0, emptyMap(), listOf(SnapshotPosition("BCBA:AAPL", d("11")))),
            listOf(aapl, aapld),
        )
        val ledger = BrokerLedgerView(
            holdings = mapOf(
                "BCBA:AAPL" to HeldPosition(11L, 1_000L, "ARS"),
                "BCBA:AAPLD" to HeldPosition(8L, 9_859L, "USD"),
            ),
        )
        val result = planBrokerImport(batch, accounts, ledger, emptySet())
        assertEquals(listOf(BalanceDifference("cartera", "BCBA:AAPL", 19L, 11L)), result.differences)
    }

    /** Bought as IRSA in pesos, sold as IRSAD for dollars: the sale draws the peso line. */
    @Test
    fun aSaleOnTheDollarLineDrawsFromThePesoLine() {
        val batch = BrokerBatch(
            "iol",
            listOf(trade("9", t0, "BCBA:IRSAD", "-55", "81.95", "USD")),
            null,
            listOf(irsa, irsad),
        )
        val ledger = BrokerLedgerView(
            cash = mapOf("USD" to 1L),
            holdings = mapOf("BCBA:IRSA" to HeldPosition(55L, 9_593_356L, "ARS")),
        )
        val sale = planBrokerImport(batch, accounts, ledger, emptySet()).transactions.single()
        // The pesos' basis travels with the dollars, like a MEP purchase.
        assertEquals(
            setOf(
                listOf("cartera", -55L, "BCBA:IRSA", -9_593_356L),
                listOf("cash-usd", 8_195L, "USD", 9_593_356L),
            ),
            sale.lines(),
        )
    }

    /** A sale bigger than its own line takes the rest from the other line, each at its own cost. */
    @Test
    fun aSaleSpanningTwoLinesSplitsTheProceeds() {
        val batch = BrokerBatch(
            "iol",
            listOf(trade("10", t0, "BCBA:AAPLD", "-10", "150", "USD")),
            null,
            listOf(aapl, aapld),
        )
        val ledger = BrokerLedgerView(
            cash = mapOf("USD" to 1L),
            holdings = mapOf(
                "BCBA:AAPLD" to HeldPosition(8L, 9_859L, "USD"),
                "BCBA:AAPL" to HeldPosition(3L, 3_572_397L, "ARS"),
            ),
        )
        val result = planBrokerImport(batch, accounts, ledger, emptySet())
        assertTrue(result.issues.isEmpty(), result.issues.toString())
        val sale = result.transactions.single()
        val cash = resolvePostings(sale.transaction.drafts).filter { it.accountId == "cash-usd" }
        // 8/10 and 2/10 of US$ 150, adding up exactly.
        assertEquals(setOf(12_000L, 3_000L), cash.map { it.amountMinor }.toSet())
        assertEquals(
            setOf(-8L to "BCBA:AAPLD", -2L to "BCBA:AAPL"),
            resolvePostings(sale.transaction.drafts).filter { it.accountId == "cartera" }.map { it.amountMinor to it.commodity }.toSet(),
        )
    }

    /** An ON bought on its dollar line matures under its peso ticker. */
    @Test
    fun aRedemptionClosesEveryLine() {
        val batch = BrokerBatch(
            "iol",
            listOf(BrokerEvent.Principal("11", t0, true, "Amortización AEC1O", "BCBA:AEC1O", null, d("96"), "USD")),
            null,
            listOf(aec1o, aec1d),
        )
        val ledger = BrokerLedgerView(
            cash = mapOf("USD" to 1L),
            holdings = mapOf("BCBA:AEC1D" to HeldPosition(96L, 9_867L, "USD")),
        )
        val result = planBrokerImport(batch, accounts, ledger, emptySet())
        assertTrue(result.issues.isEmpty(), result.issues.toString())
        assertTrue(listOf("cartera", -96L, "BCBA:AEC1D", -9_867L) in result.transactions.single().lines())
    }

    /** A rebuild opens against what stays: a hand-typed deposit is not counted twice. */
    @Test
    fun aRebuildSubtractsWhatTheLedgerKeeps() {
        val batch = BrokerBatch(
            "iol",
            emptyList(),
            BrokerSnapshot(t0, mapOf("ARS" to d("1000")), emptyList()),
            emptyList(),
        )
        val kept = BrokerLedgerView(cash = mapOf("ARS" to 40_000L))
        val result = planBrokerImport(batch, accounts, kept, emptySet(), rebuild = true)
        val opening = result.transactions.single { it.kind == PlannedKind.Opening }
        assertEquals(setOf(listOf("cash-ars", 60_000L, "ARS", null), listOf("saldo-inicial", -60_000L, "ARS", null)), opening.lines())
        assertTrue(result.differences.isEmpty())
        // Without rebuild, a non-empty ledger gets no opening at all.
        assertTrue(planBrokerImport(batch, accounts, kept, emptySet()).transactions.isEmpty())
    }

    // --- valuation ---------------------------------------------------------------

    private val valuation = Valuation(
        commodities = all.associateBy { it.id },
        prices = mapOf("BCBA:AAPL" to PriceQuote("BCBA:AAPL", "ARS", t0, d("12800"), "iol")),
        mep = PriceQuote("USD", "ARS", t0, d("1600"), MEP_SOURCE),
    )

    @Test
    fun aLineIsPricedByItsSecurity() {
        // 8 AAPLD are 8 AAPL: $ 102.400.
        assertEquals("ARS" to 10_240_000L, valuation.valueOf("BCBA:AAPLD", 8L))
    }

    @Test
    fun positionsFollowTheDisplay() {
        val holdings = mapOf(
            "BCBA:AAPLD" to HeldPosition(8L, 5_000L, "USD"),
            "BCBA:AAPL" to HeldPosition(3L, 3_000_000L, "ARS"),
        )
        fun line(display: InvestmentsDisplay, id: String) = valuation.positions(holdings, display).single { it.commodity == id }

        // Original: each line in its own currency; $ 102.400 / 1600 = US$ 64,
        // and each gain in the currency the line cost.
        assertEquals("USD" to 6_400L, line(InvestmentsDisplay.Original, "BCBA:AAPLD").let { it.valueCommodity to it.valueMinor })
        assertEquals("ARS" to 3_840_000L, line(InvestmentsDisplay.Original, "BCBA:AAPL").let { it.valueCommodity to it.valueMinor })
        assertEquals(1_400L, line(InvestmentsDisplay.Original, "BCBA:AAPLD").unrealizedMinor)
        assertEquals(840_000L, line(InvestmentsDisplay.Original, "BCBA:AAPL").unrealizedMinor)
        // All in pesos / all in MEP dollars: one row for the security, 11 AAPL.
        val pesos = valuation.positions(holdings, InvestmentsDisplay.Pesos).single()
        assertEquals(listOf("BCBA:AAPL", 11L, "ARS", 14_080_000L), listOf(pesos.commodity, pesos.quantityMinor, pesos.valueCommodity, pesos.valueMinor))
        assertEquals("USD" to 8_800L, line(InvestmentsDisplay.Mep, "BCBA:AAPL").let { it.valueCommodity to it.valueMinor })
        // Without the purchases' dates there is no rate for the other
        // currency's cost, so no gain rather than one at today's rate.
        assertNull(pesos.unrealizedMinor)
    }

    @Test
    fun withoutAMepRateNothingIsConverted() {
        val noRate = valuation.copy(mep = null)
        val line = noRate.positions(mapOf("BCBA:AAPLD" to HeldPosition(8L, 5_000L, "USD")), InvestmentsDisplay.Mep).single()
        // Valued at its security's peso price, and no dollar gain without a rate.
        assertEquals("ARS" to 10_240_000L, line.valueCommodity to line.valueMinor)
        assertNull(line.unrealizedMinor)
        assertEquals(mapOf("ARS" to 100L, "USD" to 5L), noRate.inDisplay(mapOf("ARS" to 100L, "USD" to 5L), InvestmentsDisplay.Mep))
        assertEquals(mapOf("USD" to 500L), valuation.inDisplay(mapOf("ARS" to 480_000L, "USD" to 200L), InvestmentsDisplay.Mep))
    }

    @Test
    fun theMepRateIsAPesoLineOverItsDollarLine() {
        val rate = iolMepRate(mapOf("AL30" to d("83100"), "AL30D" to d("57.10")), t0)!!
        assertEquals(PriceQuote("USD", "ARS", t0, d("1455.34"), MEP_SOURCE), rate)
        // AL30 missing its dollar line: the next bond.
        assertEquals(d("1500.00"), iolMepRate(mapOf("AL30" to d("1"), "GD30" to d("90000"), "GD30D" to d("60")), t0)!!.price)
        assertNull(iolMepRate(emptyMap(), t0))
    }
}
