package ar.fausto.weil

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * planBrokerImport (plans/inversiones-brokers.md, phase 2). Figures are the
 * real ones where they exist: the IBKR account's deposit and TTWO fill, the
 * IOL fund redemption and letra amortization.
 */
class BrokerageTest {

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
        transfers = "en-transito",
    )

    private fun d(text: String) = Decimal.parse(text)!!

    private val ttwo = InstrumentInfo("NASDAQ:TTWO", "TTWO", "Take-Two", "stock", scale = 4, quoteCommodity = "USD")
    private val s13n6 = InstrumentInfo("BCBA:S13N6", "S13N6", "Letra", "letra", scale = 0, pricePer = 100, quoteCommodity = "ARS")

    private val t0 = 1_790_000_000_000L

    /** Postings of a planned transaction as (account, amount, commodity, cost) for assertions. */
    private fun PlannedTransaction.lines(): Set<List<Any?>> =
        resolvePostings(transaction.drafts).map { listOf(it.accountId, it.amountMinor, it.commodity, it.costMinor) }.toSet()

    private fun plan(
        events: List<BrokerEvent>,
        ledger: BrokerLedgerView = BrokerLedgerView(),
        snapshot: BrokerSnapshot? = null,
        known: Set<String> = emptySet(),
        instruments: List<InstrumentInfo> = listOf(ttwo, s13n6),
        scales: Map<String, Int> = emptyMap(),
    ) = planBrokerImport(
        BrokerBatch("ibkr", events, snapshot, instruments),
        accounts, ledger, known, scales,
    )

    // --- IBKR, the real account ------------------------------------------------

    /** The deposit, already netted of its advance/cancellation pair by the connector. */
    private val deposit = BrokerEvent.CashTransfer(
        "42307900416", t0, false, "Depósito", d("1895.07"), "USD",
    )

    private val ttwoBuy = BrokerEvent.Trade(
        "tt1", t0 + 1000, true, "Compra TTWO", "NASDAQ:TTWO",
        quantity = d("0.4939"), gross = d("99.98"), cashCommodity = "USD", fees = d("1.00"),
    )

    @Test
    fun depositIsAFlaggedTransferAndNeedsNoOpening() {
        val result = plan(
            listOf(deposit),
            snapshot = BrokerSnapshot(t0 + 5, mapOf("USD" to d("1895.07")), emptyList()),
        )
        val transfer = result.transactions.single()
        assertEquals(PlannedKind.Transfer, transfer.kind)
        assertTrue(transfer.needsCounterpart)
        assertEquals(setOf(listOf("cash-usd", 189507L, "USD", null), listOf("en-transito", -189507L, "USD", null)), transfer.lines())
        assertEquals(listOf(TransactionSource(EventSource.Broker, "ibkr:42307900416")), transfer.transaction.sources)
        // Everything the snapshot holds is explained by the batch: no opening.
        assertTrue(result.differences.isEmpty())
        assertTrue(result.issues.isEmpty())
    }

    /** Commissions are capitalized: the buy costs 100,98 and nothing is expensed. */
    @Test
    fun buyCapitalizesTheCommission() {
        val result = plan(
            listOf(ttwoBuy),
            ledger = BrokerLedgerView(cash = mapOf("USD" to 189507L)),
            snapshot = BrokerSnapshot(
                t0 + 5000,
                mapOf("USD" to d("1794.09")),
                listOf(SnapshotPosition("NASDAQ:TTWO", d("0.4939"), price = d("202.84"))),
            ),
        )
        val buy = result.transactions.single()
        assertEquals(
            setOf(listOf("cartera", 4939L, "NASDAQ:TTWO", 10098L), listOf("cash-usd", -10098L, "USD", null)),
            buy.lines(),
        )
        assertEquals("Comisión US$ 1,00", buy.transaction.note)
        // Cash and position both agree with IBKR afterwards.
        assertTrue(result.differences.isEmpty(), result.differences.toString())
        assertEquals(listOf(ttwo, s13n6), result.newCommodities)
        assertEquals(listOf(PriceQuote("NASDAQ:TTWO", "USD", t0 + 5000, d("202.84"), "ibkr")), result.prices)
    }

    /** IBKR states the FIFO basis of what a sale closes; the gain follows from it. */
    @Test
    fun saleUsesTheBrokersBasis() {
        val sale = BrokerEvent.Trade(
            "tt2", t0 + 2000, true, "Venta TTWO", "NASDAQ:TTWO",
            quantity = d("-0.4939"), gross = d("110.00"), cashCommodity = "USD", fees = d("1.00"),
            costBasis = d("100.98"),
        )
        val result = plan(
            listOf(sale),
            ledger = BrokerLedgerView(
                cash = mapOf("USD" to 178409L),
                holdings = mapOf("NASDAQ:TTWO" to HeldPosition(4939L, 10098L, "USD")),
            ),
        )
        assertEquals(
            setOf(
                listOf("cartera", -4939L, "NASDAQ:TTWO", -10098L),
                listOf("cash-usd", 10900L, "USD", null),
                // 109,00 net − 100,98 basis = 8,02 of gain, a credit.
                listOf("ganancias", -802L, "USD", null),
            ),
            result.transactions.single().lines(),
        )
    }

    // --- IOL-style: average cost ------------------------------------------------

    @Test
    fun partialSaleAtAverageCost() {
        val sale = BrokerEvent.Trade(
            "900", t0, true, "Venta MELI", "BCBA:MELI",
            quantity = d("-3"), gross = d("75000"), cashCommodity = "ARS",
        )
        val result = plan(
            listOf(sale),
            ledger = BrokerLedgerView(holdings = mapOf("BCBA:MELI" to HeldPosition(1300L, 31_707_000L, "ARS"))),
            instruments = emptyList(),
        )
        // 13 held at $ 317.070 → 3 at average cost = $ 73.170; gain $ 1.830.
        assertEquals(
            setOf(
                listOf("cartera", -300L, "BCBA:MELI", -7_317_000L),
                listOf("cash-ars", 7_500_000L, "ARS", null),
                listOf("ganancias", -183_000L, "ARS", null),
            ),
            result.transactions.single().lines(),
        )
    }

    @Test
    fun sellingMoreThanTheLedgerHoldsIsAnIssueNotAGuess() {
        val sale = BrokerEvent.Trade(
            "901", t0, true, "Venta MELI", "BCBA:MELI",
            quantity = d("-5"), gross = d("125000"), cashCommodity = "ARS",
        )
        val result = plan(
            listOf(sale),
            ledger = BrokerLedgerView(holdings = mapOf("BCBA:MELI" to HeldPosition(300L, 7_317_000L, "ARS"))),
            instruments = emptyList(),
        )
        assertTrue(result.transactions.isEmpty())
        assertEquals("ibkr:901", result.issues.single().ref)
    }

    /** IOL's letra S14G6 paid back: principal at average cost, the rest is gain. */
    @Test
    fun amortizationBooksLikeASaleWithoutFees() {
        val principal = BrokerEvent.Principal(
            "185135140", t0, true, "Amortización S14G6", "BCBA:S14G6",
            quantity = d("2168320"), cash = d("2342410.09"), cashCommodity = "ARS",
        )
        val result = plan(
            listOf(principal),
            ledger = BrokerLedgerView(holdings = mapOf("BCBA:S14G6" to HeldPosition(2_168_320L, 220_000_000L, "ARS"))),
            instruments = emptyList(),
            scales = mapOf("BCBA:S14G6" to 0),
        )
        assertEquals(
            setOf(
                listOf("cartera", -2_168_320L, "BCBA:S14G6", -220_000_000L),
                listOf("cash-ars", 234_241_009L, "ARS", null),
                listOf("ganancias", -14_241_009L, "ARS", null),
            ),
            result.transactions.single().lines(),
        )
        assertEquals(PlannedKind.Principal, result.transactions.single().kind)
    }

    @Test
    fun dividendWithWithholding() {
        val dividend = BrokerEvent.Income(
            "d1", t0, false, "Dividendo TTWO", IncomeKind.Dividend,
            gross = d("10.00"), cashCommodity = "USD", tax = d("3.00"), instrument = "NASDAQ:TTWO",
        )
        assertEquals(
            setOf(
                listOf("cash-usd", 700L, "USD", null),
                listOf("dividendos", -1000L, "USD", null),
                listOf("impuestos", 300L, "USD", null),
            ),
            plan(listOf(dividend)).transactions.single().lines(),
        )
    }

    @Test
    fun couponWithoutTaxGoesToInterest() {
        val coupon = BrokerEvent.Income(
            "172510909", t0, false, "Renta AO27", IncomeKind.Interest, gross = d("5.64"), cashCommodity = "USD",
        )
        assertEquals(
            setOf(listOf("cash-usd", 564L, "USD", null), listOf("interes", -564L, "USD", null)),
            plan(listOf(coupon)).transactions.single().lines(),
        )
    }

    @Test
    fun fxCarriesItsCostAndExpensesTheFee() {
        val fx = BrokerEvent.FxConversion(
            "fx1", t0, true, "Compra MEP", from = d("154686.00"), fromCommodity = "ARS",
            to = d("100.00"), toCommodity = "USD", fees = d("100.00"),
        )
        assertEquals(
            setOf(
                listOf("cash-usd", 10_000L, "USD", 15_458_600L),
                listOf("cash-ars", -15_468_600L, "ARS", null),
                listOf("comisiones", 10_000L, "ARS", null),
            ),
            plan(listOf(fx)).transactions.single().lines(),
        )
    }

    @Test
    fun splitChangesQuantityButNotCost() {
        val split = BrokerEvent.QuantityChange("s1", t0, false, "Split 2:1 TTWO", "NASDAQ:TTWO", d("0.4939"))
        val result = plan(
            listOf(split),
            ledger = BrokerLedgerView(holdings = mapOf("NASDAQ:TTWO" to HeldPosition(4939L, 10098L, "USD"))),
            snapshot = BrokerSnapshot(t0 + 1, emptyMap(), listOf(SnapshotPosition("NASDAQ:TTWO", d("0.9878")))),
        )
        assertEquals(
            setOf(listOf("cartera", 4939L, "NASDAQ:TTWO", null), listOf("ajustes", -4939L, "NASDAQ:TTWO", null)),
            result.transactions.single().lines(),
        )
        assertTrue(result.differences.isEmpty(), result.differences.toString())
    }

    // --- idempotency, gaps, differences -----------------------------------------

    @Test
    fun knownRefsAreSkipped() {
        val result = plan(listOf(deposit, ttwoBuy), known = setOf("ibkr:42307900416"))
        assertEquals(listOf("ibkr:tt1"), result.transactions.map { it.ref })
        assertEquals(listOf("ibkr:42307900416"), result.skippedRefs)
    }

    @Test
    fun missingCashAccountIsAnIssue() {
        val eur = BrokerEvent.CashTransfer("e1", t0, false, "Depósito", d("10"), "EUR")
        val result = plan(listOf(eur))
        assertTrue(result.transactions.isEmpty())
        assertTrue("EUR" in result.issues.single().message)
    }

    /** A non-empty ledger never gets an opening: a gap is reported, not papered over. */
    @Test
    fun differencesAreReportedNotBooked() {
        val result = plan(
            emptyList(),
            ledger = BrokerLedgerView(cash = mapOf("USD" to 189507L)),
            snapshot = BrokerSnapshot(t0, mapOf("USD" to d("2095.07")), emptyList()),
        )
        assertTrue(result.transactions.isEmpty())
        assertEquals(listOf(BalanceDifference("cash-usd", "USD", 189507L, 209507L)), result.differences)
        assertEquals(20000L, result.differences.single().deltaMinor)
    }

    // --- opening -----------------------------------------------------------------

    /**
     * IOL's first import: the snapshot holds a letra bought *before* the
     * window (valued from the snapshot, per 100 VN) and pesos, while the
     * batch's own buy of MELI explains its whole position.
     */
    @Test
    fun openingIsTheSnapshotMinusTheBatch() {
        val meliBuy = BrokerEvent.Trade(
            "185183992", t0 + 10, true, "Compra MELI", "BCBA:MELI",
            quantity = d("13"), gross = d("317070"), cashCommodity = "ARS", fees = d("0"),
        )
        val snapshot = BrokerSnapshot(
            t0 + 100,
            mapOf("ARS" to d("542573.83")),
            listOf(
                SnapshotPosition("BCBA:MELI", d("13"), price = d("23420")),
                SnapshotPosition("BCBA:S13N6", d("1923076"), price = d("106.251")),
            ),
        )
        val meli = InstrumentInfo("BCBA:MELI", "MELI", kind = "cedear", scale = 0, quoteCommodity = "ARS")
        val result = planBrokerImport(
            BrokerBatch("iol", listOf(meliBuy), snapshot, listOf(meli, s13n6)),
            accounts, BrokerLedgerView(), emptySet(),
        )
        val opening = result.transactions.first()
        assertEquals(PlannedKind.Opening, opening.kind)
        assertEquals(t0 + 9, opening.transaction.date)
        // S13N6 at 1.923.076 × 106,251 / 100; pesos = 542.573,83 + 317.070 spent on MELI.
        assertEquals(
            setOf(
                listOf("cartera", 1_923_076L, "BCBA:S13N6", 204_328_748L),
                listOf("cash-ars", 85_964_383L, "ARS", null),
                listOf("saldo-inicial", -290_293_131L, "ARS", null),
            ),
            opening.lines(),
        )
        assertEquals(PlannedKind.Trade, result.transactions[1].kind)
        // After opening + batch the ledger holds exactly what IOL says.
        assertTrue(result.differences.isEmpty(), result.differences.toString())
        assertTrue(result.issues.isEmpty(), result.issues.toString())
    }

    /** Units sold in the window but bought before it open at the broker's basis. */
    @Test
    fun openingValuesSoldUnitsAtTheBrokersBasis() {
        val sale = BrokerEvent.Trade(
            "tt9", t0 + 10, true, "Venta TTWO", "NASDAQ:TTWO",
            quantity = d("-0.4939"), gross = d("110.00"), cashCommodity = "USD", fees = d("1.00"),
            costBasis = d("100.98"),
        )
        val result = plan(
            listOf(sale),
            snapshot = BrokerSnapshot(t0 + 100, mapOf("USD" to d("109.00")), emptyList()),
        )
        val (opening, trade) = result.transactions
        assertEquals(
            setOf(listOf("cartera", 4939L, "NASDAQ:TTWO", 10098L), listOf("saldo-inicial", -10098L, "USD", null)),
            opening.lines(),
        )
        assertTrue(listOf("ganancias", -802L, "USD", null) in trade.lines())
        assertTrue(result.differences.isEmpty(), result.differences.toString())
    }

    @Test
    fun anUnpriceableOpeningIsAnIssue() {
        val result = plan(
            emptyList(),
            snapshot = BrokerSnapshot(t0, emptyMap(), listOf(SnapshotPosition("BCBA:XYZ", d("5")))),
            instruments = emptyList(),
        )
        assertTrue(result.transactions.isEmpty())
        assertTrue("BCBA:XYZ" in result.issues.single().message)
        // …and the position still shows up as a difference to resolve.
        assertEquals(listOf(BalanceDifference("cartera", "BCBA:XYZ", 0L, 500L)), result.differences)
    }
}
