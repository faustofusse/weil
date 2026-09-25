package ar.fausto.weil

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Ledger's `@@` on postings (plans/inversiones-brokers.md, decision 2): a
 * posting with a cost weighs its cost when the transaction is balanced.
 *
 * Amounts in these drafts are minor units written at 2 decimals, as every
 * draft built by code carries them (see [DraftPosting]): "49,39" TTWO is
 * 4939 minor units, i.e. 0,4939 shares at TTWO's scale of 4.
 */
class PostingCostTest {

    private val ttwo = "NASDAQ:TTWO"

    /** The real IBKR fill: 0,4939 TTWO for US$ 99,98 plus US$ 1,00 commission. */
    private fun buy() = listOf(
        DraftPosting("cartera", "49,39", ttwo, costText = "99,98", costCommodity = "USD"),
        DraftPosting("comisiones", "1,00", "USD"),
        DraftPosting("cash", "-100,98", "USD"),
    )

    @Test
    fun buyWithCommissionBalancesOnCost() {
        val postings = resolvePostings(buy())
        val stock = postings.single { it.commodity == ttwo }
        assertEquals(4939L, stock.amountMinor)
        assertEquals(9998L, stock.costMinor)
        assertEquals("USD", stock.costCommodity)
        assertEquals(Money(9998L, "USD"), stock.weight())
        // Postings without a cost weigh their own amount.
        assertEquals(Money(-10098L, "USD"), postings.single { it.accountId == "cash" }.weight())
    }

    @Test
    fun theCashLegCanBeElided() {
        val postings = resolvePostings(
            listOf(
                DraftPosting("cartera", "49,39", ttwo, costText = "99,98", costCommodity = "USD"),
                DraftPosting("comisiones", "1,00", "USD"),
                DraftPosting("cash", "", "USD"),
            ),
        )
        assertEquals(-10098L, postings.single { it.accountId == "cash" }.amountMinor)
    }

    @Test
    fun saleWithGainBalances() {
        // Sell the whole position for US$ 110,00: out at its cost (-100,98),
        // US$ 9,02 of gain, US$ 1,00 commission, US$ 109,00 in.
        val postings = resolvePostings(
            listOf(
                DraftPosting("cartera", "-49,39", ttwo, costText = "-100,98", costCommodity = "USD"),
                DraftPosting("ganancias", "-9,02", "USD"),
                DraftPosting("comisiones", "1,00", "USD"),
                DraftPosting("cash", "109,00", "USD"),
            ),
        )
        assertEquals(4, postings.size)
    }

    @Test
    fun anOffCostIsUnbalanced() {
        assertFailsWith<LedgerValidationException> {
            resolvePostings(
                listOf(
                    DraftPosting("cartera", "49,39", ttwo, costText = "99,98", costCommodity = "USD"),
                    DraftPosting("cash", "-100,98", "USD"),
                ),
            )
        }
    }

    /**
     * The legacy two-commodity exception must not swallow a cost mistake: a
     * TTWO buy priced in USD but paid from a pesos account has the exact
     * shape of an unpriced exchange.
     */
    @Test
    fun theExchangeExceptionDoesNotApplyOnceACostIsStated() {
        assertFailsWith<LedgerValidationException> {
            resolvePostings(
                listOf(
                    DraftPosting("cartera", "49,39", ttwo, costText = "99,98", costCommodity = "USD"),
                    DraftPosting("pesos", "-150000,00", "ARS"),
                ),
            )
        }
    }

    @Test
    fun anUnpricedExchangeStillPasses() {
        // Rows written before costs existed keep validating.
        val postings = resolvePostings(
            listOf(DraftPosting("usd", "100,00", "USD"), DraftPosting("ars", "-154586,00", "ARS")),
        )
        assertTrue(postings.all { it.costMinor == null })
    }

    @Test
    fun aPricedExchangeBalances() {
        // US$ 100 bought for $ 154.586: the dollars carry their cost in pesos.
        resolvePostings(
            listOf(
                DraftPosting("usd", "100,00", "USD", costText = "154586,00", costCommodity = "ARS"),
                DraftPosting("ars", "-154586,00", "ARS"),
            ),
        )
    }

    @Test
    fun rejectsMalformedCosts() {
        fun attempt(cost: DraftPosting) = assertFailsWith<LedgerValidationException> {
            resolvePostings(listOf(cost, DraftPosting("cash", "-100,98", "USD")))
        }
        // No commodity for the cost.
        attempt(DraftPosting("cartera", "49,39", ttwo, costText = "100,98"))
        // Cost in the posting's own commodity.
        attempt(DraftPosting("cash2", "100,98", "USD", costText = "100,98", costCommodity = "USD"))
        // Zero, unparseable, opposite sign.
        attempt(DraftPosting("cartera", "49,39", ttwo, costText = "0", costCommodity = "USD"))
        attempt(DraftPosting("cartera", "49,39", ttwo, costText = "abc", costCommodity = "USD"))
        attempt(DraftPosting("cartera", "49,39", ttwo, costText = "-100,98", costCommodity = "USD"))
        // The elided posting takes a residual; it cannot also state a cost.
        assertFailsWith<LedgerValidationException> {
            resolvePostings(
                listOf(
                    DraftPosting("cash", "-100,98", "USD"),
                    DraftPosting("cartera", "", ttwo, costText = "100,98", costCommodity = "USD"),
                ),
            )
        }
    }

    @Test
    fun residualsCountTheCost() {
        assertEquals(mapOf("USD" to 0L), residualsOf(buy()))
        val short = buy().dropLast(1)
        assertEquals(mapOf("USD" to 10098L), residualsOf(short))
    }

    /**
     * Edit and undo rebuild drafts from stored postings; the cost has to
     * survive that trip, or saving an untouched buy would drop its price.
     */
    @Test
    fun toDraftRoundTripsCostAndMinorUnits() {
        val original = resolvePostings(buy())
        val again = resolvePostings(original.map { it.toDraft() })
        assertEquals(
            original.map { listOf(it.accountId, it.amountMinor, it.commodity, it.costMinor, it.costCommodity) },
            again.map { listOf(it.accountId, it.amountMinor, it.commodity, it.costMinor, it.costCommodity) },
        )
        val plain = original.single { it.accountId == "cash" }.toDraft()
        assertEquals("", plain.costText)
        assertNull(plain.costCommodity)
    }
}
