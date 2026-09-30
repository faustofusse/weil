package ar.fausto.weil

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Two differences a real IOL account showed against IOL's own app:
 *  - YPF split 1:10 in 2026 and IOL's operations have no row for it, so the
 *    first import opened 9 YPFD that never existed, at the pre-split price;
 *  - in pesos/MEP IOL shows one row per security (INTC + INTCD = 13 INTC)
 *    and the gain against each purchase converted at its day's MEP.
 */
class SplitsAndDatedCostTest {

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

    private val ypfd = InstrumentInfo("BCBA:YPFD", "YPFD", kind = "stock", scale = 0, quoteCommodity = "ARS")
    private val intc = InstrumentInfo("BCBA:INTC", "INTC", kind = "cedear", scale = 0, quoteCommodity = "ARS")
    private val intcd = InstrumentInfo("BCBA:INTCD", "INTCD", kind = "cedear", scale = 0, quoteCommodity = "USD")

    private val day = 24L * 60 * 60 * 1000
    private val t0 = 1_735_900_000_000L // early January 2025

    private fun PlannedTransaction.lines(): Set<List<Any?>> =
        resolvePostings(transaction.drafts).map { listOf(it.accountId, it.amountMinor, it.commodity, it.costMinor) }.toSet()

    /** Axel's YPFD: 1 bought for $53.500 + $375 of fees; IOL now reports 10 at a PPC of $5.350. */
    private fun ypfBatch() = BrokerBatch(
        "iol",
        listOf(BrokerEvent.Trade("1", t0, true, "Compra YPFD", "BCBA:YPFD", d("1"), d("53500"), "ARS", fees = d("375"))),
        BrokerSnapshot(
            t0 + 600 * day,
            mapOf("ARS" to d("0")),
            listOf(SnapshotPosition("BCBA:YPFD", d("10"), price = d("8110"), cost = d("53500"), costCommodity = "ARS")),
        ),
        listOf(ypfd),
    )

    @Test
    fun aSplitTheOperationsDontShowIsInferredInsteadOfOpened() {
        val plan = planBrokerImport(ypfBatch(), accounts, BrokerLedgerView(), emptySet())
        // No phantom units at the old price: the opening is only the cash the buy spent.
        assertTrue(plan.transactions.filter { it.kind == PlannedKind.Opening }.none { o -> o.lines().any { it[0] == "cartera" } })
        val split = plan.transactions.single { it.kind == PlannedKind.QuantityChange }
        assertEquals("Split YPFD 1:10", split.transaction.payee)
        assertTrue(split.inferred)
        assertEquals(setOf(listOf("cartera", 9L, "BCBA:YPFD", null), listOf("ajustes", -9L, "BCBA:YPFD", null)), split.lines())
        // After the buy: 10 units, still costing what the one did.
        assertTrue(plan.differences.none { it.accountId == "cartera" })
        assertTrue(plan.issues.isEmpty())
        // An inferred movement is never written with nobody looking.
        assertTrue(!isRoutine(plan))
    }

    @Test
    fun unitsHeldFromBeforeTheHistoryStillOpen() {
        // Same quantities, but IOL's average is what the one bought cost:
        // the other 9 were there before the history starts.
        val batch = ypfBatch().let { b ->
            b.copy(snapshot = b.snapshot!!.copy(positions = listOf(SnapshotPosition("BCBA:YPFD", d("10"), cost = d("535000"), costCommodity = "ARS"))))
        }
        val plan = planBrokerImport(batch, accounts, BrokerLedgerView(), emptySet())
        assertTrue(plan.transactions.none { it.kind == PlannedKind.QuantityChange })
        val opening = plan.transactions.single { it.kind == PlannedKind.Opening }
        assertTrue(opening.lines().any { it[0] == "cartera" && it[1] == 9L })
    }

    @Test
    fun aLaterSplitIsInferredOnARegularSync() {
        val ledger = BrokerLedgerView(holdings = mapOf("BCBA:YPFD" to HeldPosition(10L, 53_587_500L, "ARS")))
        val batch = BrokerBatch(
            "iol",
            emptyList(),
            BrokerSnapshot(t0, emptyMap(), listOf(SnapshotPosition("BCBA:YPFD", d("100"), cost = d("535000"), costCommodity = "ARS"))),
            listOf(ypfd),
        )
        val split = planBrokerImport(batch, accounts, ledger, emptySet()).transactions.single()
        assertEquals(PlannedKind.QuantityChange, split.kind)
        assertTrue(split.lines().contains(listOf("cartera", 90L, "BCBA:YPFD", null)))

        // A reverse split, 10:1.
        val reverse = batch.copy(
            snapshot = BrokerSnapshot(t0, emptyMap(), listOf(SnapshotPosition("BCBA:YPFD", d("1"), cost = d("535000"), costCommodity = "ARS"))),
        )
        val contra = planBrokerImport(reverse, accounts, ledger, emptySet()).transactions.single()
        assertEquals("Contrasplit YPFD 10:1", contra.transaction.payee)
        assertTrue(contra.lines().contains(listOf("cartera", -9L, "BCBA:YPFD", null)))
    }

    @Test
    fun aMissingTradeIsNotASplit() {
        // 20 where the ledger has 10, but IOL's average is the ledger's: a
        // trade the history is missing, left to the differences.
        val ledger = BrokerLedgerView(holdings = mapOf("BCBA:YPFD" to HeldPosition(10L, 53_587_500L, "ARS")))
        val batch = BrokerBatch(
            "iol",
            emptyList(),
            BrokerSnapshot(t0, emptyMap(), listOf(SnapshotPosition("BCBA:YPFD", d("20"), cost = d("1070000"), costCommodity = "ARS"))),
            listOf(ypfd),
        )
        val plan = planBrokerImport(batch, accounts, ledger, emptySet())
        assertTrue(plan.transactions.isEmpty())
        assertEquals(listOf(BalanceDifference("cartera", "BCBA:YPFD", 10L, 20L)), plan.differences)
    }

    /**
     * Axel's SPY: 3 SPYD at US$ 31,39 in Dec-2024, a 1:3 CEDEAR ratio change,
     * 15 SPYD at US$ 12,89 in Jul-2026. IOL reports 24 SPY at a PPC in pesos.
     */
    private val spy = InstrumentInfo("BCBA:SPY", "SPY", kind = "cedear", scale = 0, quoteCommodity = "ARS")
    private val spyd = InstrumentInfo("BCBA:SPYD", "SPYD", kind = "cedear", scale = 0, quoteCommodity = "USD")

    private fun spyBatch(julyGross: String) = BrokerBatch(
        "iol",
        listOf(
            BrokerEvent.Trade("dec", t0, true, "Compra SPYD", "BCBA:SPYD", d("3"), d("93.60"), "USD", fees = d("0.57")),
            BrokerEvent.Trade("jul", t0 + 570 * day, true, "Compra SPYD", "BCBA:SPYD", d("15"), julyGross.let(::d), "USD", fees = d("1.16")),
        ),
        BrokerSnapshot(
            t0 + 600 * day,
            mapOf("USD" to d("0")),
            listOf(SnapshotPosition("BCBA:SPY", d("24"), price = d("20600"), cost = d("392681.74"), costCommodity = "ARS")),
        ),
        listOf(spy, spyd),
    )

    @Test
    fun aSplitBetweenTwoPurchasesIsInferredFromTheirPrices() {
        val plan = planBrokerImport(spyBatch("192.15"), accounts, BrokerLedgerView(), emptySet())
        val split = plan.transactions.single { it.kind == PlannedKind.QuantityChange }
        assertEquals("Split SPY 1:3", split.transaction.payee)
        assertTrue(split.lines().contains(listOf("cartera", 6L, "BCBA:SPYD", null)))
        // Between the two buys, right before the second.
        assertEquals(t0 + 570 * day - 1, split.transaction.date)
        assertTrue(plan.transactions.filter { it.kind == PlannedKind.Opening }.none { o -> o.lines().any { it[0] == "cartera" } })
        assertTrue(plan.differences.none { it.accountId == "cartera" })

        // Same quantities, but the second buy priced like the first: no
        // split, the 6 were there before the history.
        val flat = planBrokerImport(spyBatch("480.00"), accounts, BrokerLedgerView(), emptySet())
        assertTrue(flat.transactions.none { it.kind == PlannedKind.QuantityChange })
    }

    @Test
    fun aSplitAnEarlierImportHidIsReported() {
        // What the first import wrote before splits were inferred: 10 units
        // at ten times IOL's average. Quantities agree; costs don't.
        val ledger = BrokerLedgerView(holdings = mapOf("BCBA:YPFD" to HeldPosition(10L, 53_537_500L, "ARS")))
        val batch = ypfBatch().copy(events = emptyList())
        val plan = planBrokerImport(batch, accounts, ledger, emptySet())
        assertTrue(plan.transactions.isEmpty())
        assertTrue(plan.issues.single().message.contains("rebuild"))
    }

    // --- payments the history can't place ----------------------------------------

    /** Axel's STCEO: held before IOL's history, redeemed whole for US$ 100. */
    @Test
    fun aRedemptionOfSomethingHeldBeforeTheHistoryComesFromTheOpening() {
        val stceo = InstrumentInfo("BCBA:STCEO", "STCEO", kind = "on", scale = 0, pricePer = 100, quoteCommodity = "USD")
        val batch = BrokerBatch(
            "iol",
            listOf(BrokerEvent.Principal("r", t0, true, "Amortización STCEO", "BCBA:STCEO", null, d("100"), "USD")),
            BrokerSnapshot(t0 + day, mapOf("USD" to d("100")), emptyList()),
            listOf(stceo),
        )
        val plan = planBrokerImport(batch, accounts, BrokerLedgerView(), emptySet())
        assertTrue(plan.issues.isEmpty())
        val redemption = plan.transactions.single { it.ref == "iol:r" }
        assertEquals(setOf(listOf("cash-usd", 10_000L, "USD", null), listOf("saldo-inicial", -10_000L, "USD", null)), redemption.lines())
        // The cash lands on the broker's without a hand-made adjustment.
        assertTrue(plan.differences.isEmpty())
    }

    /** RVS1O paying back $ 8.750 of its principal: the units stay, the money arrives. */
    @Test
    fun aPartialAmortizationIsIncomeAndLeavesThePositionAlone() {
        val rvs = InstrumentInfo("BCBA:RVS1O", "RVS1O", kind = "on", scale = 0, pricePer = 100, quoteCommodity = "ARS")
        val withAccount = accounts.copy(amortizations = "amortizaciones")
        val ledger = BrokerLedgerView(
            cash = mapOf("ARS" to 1L),
            holdings = mapOf("BCBA:RVS1O" to HeldPosition(35_000L, 3_500_000L, "ARS")),
        )
        val event = BrokerEvent.Income("a", t0, true, "Amortización parcial RVS1O", IncomeKind.PrincipalReturn, d("8750"), "ARS", instrument = rvs.id)
        val plan = planBrokerImport(BrokerBatch("iol", listOf(event), instruments = listOf(rvs)), withAccount, ledger, emptySet())
        assertEquals(
            setOf(listOf("cash-ars", 875_000L, "ARS", null), listOf("amortizaciones", -875_000L, "ARS", null)),
            plan.transactions.single().lines(),
        )
    }

    // --- valuation in one currency ------------------------------------------------

    /** Closes: 1.200 until the 10th day, 1.500 after; today's live quote 1.600. */
    private val valuation = Valuation(
        commodities = listOf(ypfd, intc, intcd).associateBy { it.id },
        prices = mapOf("BCBA:INTC" to PriceQuote("BCBA:INTC", "ARS", t0 + 30 * day, d("38400"), "iol")),
        mep = PriceQuote("USD", "ARS", t0 + 30 * day, d("1600"), MEP_SOURCE),
        mepHistory = (0..20).map { i ->
            PriceQuote("USD", "ARS", t0 + i * day, if (i < 10) d("1200") else d("1500"), MEP_CLOSE_SOURCE)
        } + PriceQuote("USD", "ARS", t0 + 30 * day, d("1600"), MEP_SOURCE),
    )

    private fun move(at: Long, commodity: String, quantity: Long, cost: Long?, costCommodity: String?) =
        HoldingMovement("cartera", at, commodity, quantity, cost, costCommodity)

    @Test
    fun theRateOfAPurchaseIsItsDaysClose() {
        assertEquals(d("1200"), valuation.mepAt(t0 + 9 * day + 1))
        assertEquals(d("1500"), valuation.mepAt(t0 + 10 * day))
        // A purchase earlier in the day than the close still reads that day's.
        assertEquals(d("1500"), valuation.mepAt(t0 + 10 * day - 60_000))
        // Before the history, or past a week-long hole in it: no rate.
        assertNull(valuation.mepAt(t0 - day))
        assertNull(valuation.mepAt(t0 + 29 * day))
    }

    @Test
    fun datedCostsConvertEachPurchaseAtItsDay() {
        val costs = valuation.datedCosts(
            listOf(
                // 7 INTC for $ 36.000 when the MEP was 1.200 (US$ 30), then
                // 1 more for $ 15.000 at 1.500 (US$ 10); one sold at average.
                move(t0 + 1, "BCBA:INTC", 7L, 3_600_000L, "ARS"),
                move(t0 + 11 * day, "BCBA:INTC", 1L, 1_500_000L, "ARS"),
                move(t0 + 12 * day, "BCBA:INTC", -1L, -637_500L, "ARS"),
                // 6 INTCD for US$ 27 at 1.500: $ 40.500.
                move(t0 + 12 * day, "BCBA:INTCD", 6L, 2_700L, "USD"),
                // A split adds units and no cost.
                move(t0 + 13 * day, "BCBA:INTCD", 6L, null, null),
            ),
        )
        // 7/8 of (30 + 10) dollars and of (36.000 + 15.000) pesos.
        assertEquals(DatedCost(4_462_500L, 3_500L), costs["BCBA:INTC"])
        assertEquals(DatedCost(4_050_000L, 2_700L), costs["BCBA:INTCD"])
    }

    @Test
    fun inPesosOrMepASecurityIsOneRowWithItsDollarReturn() {
        val holdings = mapOf(
            "BCBA:INTC" to HeldPosition(7L, 3_600_000L, "ARS"),
            "BCBA:INTCD" to HeldPosition(6L, 2_700L, "USD"),
        )
        val movements = listOf(
            move(t0 + 1, "BCBA:INTC", 7L, 3_600_000L, "ARS"),
            move(t0 + 12 * day, "BCBA:INTCD", 6L, 2_700L, "USD"),
        )
        val mep = valuation.positions(holdings, InvestmentsDisplay.Mep, movements).single()
        // 13 × $ 38.400 = $ 499.200 = US$ 312 at today's 1.600; cost 30 + 27.
        assertEquals("BCBA:INTC", mep.commodity)
        assertEquals(13L, mep.quantityMinor)
        assertEquals("USD" to 31_200L, mep.valueCommodity to mep.valueMinor)
        assertEquals(5_700L, mep.costMinor)
        assertEquals(25_500L, mep.unrealizedMinor)
        assertEquals(listOf(UnrealizedTotal("USD", 25_500L, 5_700L)), unrealizedTotals(listOf(mep)))

        // In pesos: cost $ 36.000 + US$ 27 × 1.500.
        val pesos = valuation.positions(holdings, InvestmentsDisplay.Pesos, movements).single()
        assertEquals("ARS" to 49_920_000L, pesos.valueCommodity to pesos.valueMinor)
        assertEquals(7_650_000L, pesos.costMinor)
        assertEquals(42_270_000L, pesos.unrealizedMinor)

        // Original keeps a row per line, each gain in its own currency.
        assertEquals(setOf("BCBA:INTC", "BCBA:INTCD"), valuation.positions(holdings, InvestmentsDisplay.Original, movements).map { it.commodity }.toSet())
        assertEquals(setOf("BCBA:INTC", "BCBA:INTCD"), valuation.linesOf("BCBA:INTC"))
    }

    @Test
    fun aPurchaseWithoutARateLeavesItsSecurityWithoutAGain() {
        val holdings = mapOf("BCBA:INTC" to HeldPosition(7L, 3_600_000L, "ARS"))
        val line = valuation.positions(holdings, InvestmentsDisplay.Mep, listOf(move(t0 - 100 * day, "BCBA:INTC", 7L, 3_600_000L, "ARS"))).single()
        assertEquals("USD" to 16_800L, line.valueCommodity to line.valueMinor)
        assertNull(line.costMinor)
        assertNull(line.unrealizedMinor)
    }

    @Test
    fun commissionsAreLeftOutOfTheGain() {
        // Your MELI at IOL: 13 × PPC 24.390 = $ 317.070, booked at
        // $ 319.256,83 because the $ 2.186,83 commission is capitalized.
        val buy = BrokerEvent.Trade("m", t0, true, "Compra MELI", "BCBA:INTC", d("13"), d("317070"), "ARS", fees = d("2186.83"))
        val plan = planBrokerImport(BrokerBatch("iol", listOf(buy), instruments = listOf(intc)), accounts, BrokerLedgerView(cash = mapOf("ARS" to 1L)), emptySet())
        val posting = resolvePostings(plan.transactions.single().transaction.drafts).single { it.accountId == "cartera" }
        assertEquals(31_925_683L, posting.costMinor)
        assertEquals(218_683L, posting.feeMinor)

        val holdings = mapOf("BCBA:INTC" to HeldPosition(13L, 31_925_683L, "ARS"))
        val movements = listOf(HoldingMovement("cartera", t0 + 1, "BCBA:INTC", 13L, 31_925_683L, "ARS", 218_683L))
        for (display in listOf(InvestmentsDisplay.Original, InvestmentsDisplay.Pesos)) {
            assertEquals(31_707_000L, valuation.positions(holdings, display, movements).single().costMinor)
        }
        // Without the postings (or before fee_minor existed), the booked cost.
        assertEquals(31_925_683L, valuation.positions(holdings).single().costMinor)
    }

    @Test
    fun mepClosesAreTheMidOfBuyAndSell() {
        val body = """[{"casa":"bolsa","compra":1509.6,"venta":1521.6,"fecha":"2026-08-14"},{"casa":"bolsa","compra":null,"venta":2,"fecha":"2026-08-15"}]"""
        val quote = parseMepHistory(body).single()
        assertEquals(d("1515.6"), quote.price)
        assertEquals(MEP_CLOSE_SOURCE, quote.source)
        assertEquals(iolTime("2026-08-14T15:00:00"), quote.at)
    }
}
