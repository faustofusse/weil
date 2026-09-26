package ar.fausto.weil

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The IBKR Flex connector over real reports (jvmTest/resources/ibkr,
 * account id replaced): the TTWO buy as the account's first import, and the
 * deposit backfill with its advance netted away.
 */
class IbkrFlexTest {

    private fun text(name: String): String = IbkrFlexTest::class.java.getResource("/ibkr/$name")!!.readText()
    private fun d(text: String) = Decimal.parse(text)!!

    private val accounts = BrokerAccounts(
        cash = mapOf("USD" to "ibkr-usd"),
        holdings = "ibkr-cartera",
        interest = "intereses",
        dividends = "dividendos",
        capitalGains = "ganancias",
        taxes = "impuestos",
        commissions = "comisiones",
        opening = "saldo-inicial",
        adjustments = "ajustes",
        transfers = "en-transito",
    )

    @Test
    fun readsTheStatementAndSkipsTheBaseSummary() {
        val statement = parseFlexReport(text("trade-ttwo.xml")).single()
        assertEquals("2026-08-27", statement.fromDate)
        assertEquals("2026-09-25", statement.toDate)
        assertEquals(listOf("USD"), ibkrCurrencies(statement))
        val batch = ibkrBatch(statement)
        assertEquals(mapOf("USD" to d("1794.089997528")), batch.snapshot!!.cash)
        assertEquals(emptyList(), batch.notes)
    }

    @Test
    fun twoFillsOfOneOrderAreOneBuyWithTheCommissionCapitalized() {
        val batch = ibkrBatch(parseFlexReport(text("trade-ttwo.xml")).single())
        val buy = batch.events.single() as BrokerEvent.Trade
        assertEquals("5661338254-20260925", buy.ref)
        assertEquals("Compra TTWO", buy.description)
        assertEquals("NASDAQ:TTWO", buy.instrument)
        assertEquals(d("0.4939"), buy.quantity)
        assertEquals(d("99.980199"), buy.gross)
        assertEquals(d("0.999803472"), buy.fees)
        assertNull(buy.costBasis)
        // 11:35:41 in New York (EDT) = 15:35:41 UTC.
        assertEquals(iolTime("2026-09-25T12:35:41"), buy.at)
        assertTrue(buy.timeKnown)

        val ttwo = batch.instruments.single()
        assertEquals(InstrumentInfo("NASDAQ:TTWO", "TTWO", "Take-Two Interactive Softwre", "stock", 4, 1, "USD", "US8740541094", "6478131"), ttwo)
        assertEquals(listOf(PriceQuote("NASDAQ:TTWO", "USD", flexTime("20260925;160000").first, d("201.44"), "ibkr")), batch.prices)
    }

    @Test
    fun theFirstImportOpensAtTheDepositAndLandsOnIbkrsNumbers() {
        val batch = ibkrBatch(parseFlexReport(text("trade-ttwo.xml")).single())
        val plan = planBrokerImport(batch, accounts, BrokerLedgerView(), emptySet(), emptyMap())
        assertEquals(emptyList(), plan.issues)
        // The report starts after the deposit: the opening is the cash
        // before the buy, US$ 1.895,07, exactly the deposit.
        val opening = plan.transactions.single { it.kind == PlannedKind.Opening }
        // Dated where the report starts, not just before the buy.
        assertEquals(iolTime("2026-08-27"), opening.transaction.date)
        val lines = resolvePostings(opening.transaction.drafts)
        assertEquals(189_507L, lines.single { it.accountId == "ibkr-usd" }.amountMinor)
        // The buy costs 100,98 (IBKR's costBasisMoney), commission included.
        val buy = resolvePostings(plan.transactions.single { it.kind == PlannedKind.Trade }.transaction.drafts)
        val cartera = buy.single { it.accountId == "ibkr-cartera" }
        assertEquals(4939L, cartera.amountMinor)
        assertEquals(10_098L, cartera.costMinor)
        assertEquals(-10_098L, buy.single { it.accountId == "ibkr-usd" }.amountMinor)
        // Afterwards the ledger and IBKR agree on cash and on the position.
        assertEquals(emptyList(), plan.differences)
    }

    @Test
    fun aDepositAdvanceAndItsCancellationDisappear() {
        val batch = ibkrBatch(parseFlexReport(text("backfill-deposit.xml")).single())
        assertEquals(emptyList(), batch.notes)
        val transfers = batch.events.filterIsInstance<BrokerEvent.CashTransfer>()
        assertEquals(listOf("42307900416"), transfers.map { it.ref })
        assertEquals(d("1895.07"), transfers.single().amount)
        assertEquals(false, transfers.single().timeKnown)

        // The account's real first report: it started at zero, so the
        // movements explain all of IBKR's ending cash and nothing is opened.
        val plan = planBrokerImport(batch, accounts, BrokerLedgerView(), emptySet(), emptyMap())
        assertEquals(emptyList(), plan.issues)
        assertTrue(plan.transactions.none { it.kind == PlannedKind.Opening })
        assertEquals(emptyList(), plan.differences)
        assertTrue(plan.transactions.single { it.kind == PlannedKind.Transfer }.needsCounterpart)
    }

    @Test
    fun anAdvanceWhoseCancellationIsInTheNextReportGoesInRowByRow() {
        val xml = text("backfill-deposit.xml").lines().filterNot { "CANCELLATION" in it }.joinToString("\n")
        val transfers = ibkrBatch(parseFlexReport(xml).single()).events.filterIsInstance<BrokerEvent.CashTransfer>()
        assertEquals(listOf("42307900001", "42307900416"), transfers.map { it.ref })
    }

    @Test
    fun aDividendCarriesItsWithholding() {
        val xml = """
            <FlexQueryResponse><FlexStatements><FlexStatement accountId="U0" fromDate="20261001" toDate="20261001">
            <CashTransaction currency="USD" assetCategory="STK" symbol="SPY" conid="756733" dateTime="20261001" amount="1" type="Dividends" transactionID="500" levelOfDetail="DETAIL" description="SPY CASH DIVIDEND" />
            <CashTransaction currency="USD" assetCategory="STK" symbol="SPY" conid="756733" dateTime="20261001" amount="-0.3" type="Withholding Tax" transactionID="501" levelOfDetail="DETAIL" description="SPY US TAX" />
            <CashTransaction currency="USD" assetCategory="" symbol="" conid="" dateTime="20261001" amount="0.1" type="Broker Interest Received" transactionID="600" levelOfDetail="DETAIL" description="USD CREDIT INT" />
            <CashTransaction currency="USD" assetCategory="" symbol="" conid="" dateTime="20261001" amount="-10" type="Other Fees" transactionID="700" levelOfDetail="DETAIL" description="SNAPSHOT FEE" />
            </FlexStatement></FlexStatements></FlexQueryResponse>
        """.trimIndent()
        val batch = ibkrBatch(parseFlexReport(xml).single())
        val incomes = batch.events.filterIsInstance<BrokerEvent.Income>()
        val dividend = incomes.single { it.kind == IncomeKind.Dividend }
        assertEquals(d("1"), dividend.gross)
        assertEquals(d("0.3"), dividend.tax)
        assertEquals("Dividendo SPY", dividend.description)
        assertEquals(d("0.1"), incomes.single { it.kind == IncomeKind.Interest }.gross)
        // A kind of row not modelled is a note, not a guess.
        assertEquals(listOf("ibkr:700"), batch.notes.map { it.ref })
    }

    @Test
    fun aSaleCarriesIbkrsFifoBasis() {
        val sale = """
            <FlexQueryResponse><FlexStatements><FlexStatement accountId="U0" fromDate="20261001" toDate="20261001">
            <Trade currency="USD" assetCategory="STK" subCategory="COMMON" symbol="TTWO" description="TAKE-TWO" conid="6478131"
              listingExchange="NASDAQ" dateTime="20261001;100000" tradeDate="20261001" quantity="-0.2" proceeds="42"
              ibCommission="-1" ibCommissionCurrency="USD" netCash="41" fifoPnlRealized="0.1" ibOrderID="77" transactionID="1"
              levelOfDetail="EXECUTION" taxes="0" />
            </FlexStatement></FlexStatements></FlexQueryResponse>
        """.trimIndent()
        val trade = ibkrBatch(parseFlexReport(sale).single()).events.single() as BrokerEvent.Trade
        assertEquals("Venta TTWO", trade.description)
        assertEquals(d("-0.2"), trade.quantity)
        assertEquals(d("42"), trade.gross)
        assertEquals(d("1"), trade.fees)
        // Basis = what came in − what IBKR says was realized.
        assertEquals(d("40.9"), trade.costBasis)
    }

    @Test
    fun aForexTradeIsAConversion() {
        val fx = """
            <FlexQueryResponse><FlexStatements><FlexStatement accountId="U0" fromDate="20261001" toDate="20261001">
            <Trade currency="USD" assetCategory="CASH" symbol="EUR.USD" dateTime="20261001;100000" tradeDate="20261001"
              quantity="100" proceeds="-116.95" ibCommission="-2" ibCommissionCurrency="USD" ibOrderID="88" transactionID="2"
              levelOfDetail="EXECUTION" />
            </FlexStatement></FlexStatements></FlexQueryResponse>
        """.trimIndent()
        val conversion = ibkrBatch(parseFlexReport(fx).single()).events.single() as BrokerEvent.FxConversion
        assertEquals(d("116.95"), conversion.from)
        assertEquals("USD", conversion.fromCommodity)
        assertEquals(d("100"), conversion.to)
        assertEquals("EUR", conversion.toCommodity)
        assertEquals(d("2"), conversion.fees)
    }

    @Test
    fun easternTimeFollowsDaylightSaving() {
        // EST in January: 10:00 New York = 15:00 UTC = 12:00 ART.
        assertEquals(iolTime("2026-01-15T12:00:00"), flexTime("20260115;100000").first)
        // The day DST starts (2026-03-08), after 02:00: EDT.
        assertEquals(iolTime("2026-03-08T11:00:00"), flexTime("20260308;100000").first)
        assertEquals(iolTime("2026-03-07T12:00:00"), flexTime("20260307;100000").first)
        // The day it ends (2026-11-01).
        assertEquals(iolTime("2026-11-01T12:00:00"), flexTime("20261101;100000").first)
    }

    @Test
    fun anythingElseIsRefused() {
        assertTrue(isFlexReport(text("trade-ttwo.xml").encodeToByteArray()))
        assertTrue(!isFlexReport("%PDF-1.7".encodeToByteArray()))
        assertFailsWith<FlexParseException> { parseFlexReport("<html/>") }
        assertFailsWith<FlexParseException> {
            parseFlexReport("<FlexQueryResponse><FlexStatementResponse><ErrorMessage>x</ErrorMessage></FlexStatementResponse></FlexQueryResponse>")
        }
    }
}
