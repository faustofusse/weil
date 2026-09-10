package ar.fausto.weil

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LedgerModelsTest {

    private fun resolved(drafts: List<DraftPosting>): List<Posting> = resolvePostings(drafts)

    @Test
    fun rejectsSinglePosting() {
        assertFailsWith<LedgerValidationException> {
            resolvePostings(listOf(DraftPosting("a", "100")))
        }
    }

    @Test
    fun rejectsWithoutAccount() {
        assertFailsWith<LedgerValidationException> {
            resolvePostings(listOf(DraftPosting(null, "100"), DraftPosting("a", "-100")))
        }
    }

    @Test
    fun rejectsMoreThanOneBlank() {
        assertFailsWith<LedgerValidationException> {
            resolvePostings(
                listOf(DraftPosting("a", "100"), DraftPosting("b", ""), DraftPosting("c", "")),
            )
        }
    }

    @Test
    fun rejectsZeroAmount() {
        assertFailsWith<LedgerValidationException> {
            resolvePostings(listOf(DraftPosting("a", "0"), DraftPosting("b", "0")))
        }
    }

    @Test
    fun requiresExplicitBalanceWithoutElision() {
        assertFailsWith<LedgerValidationException> {
            resolvePostings(listOf(DraftPosting("a", "100"), DraftPosting("b", "100")))
        }
    }

    @Test
    fun elidedTakesResidual() {
        val postings = resolved(
            listOf(DraftPosting("a", "100"), DraftPosting("b", "-30"), DraftPosting("c", "")),
        )
        assertEquals(3, postings.size)
        val c = postings.first { it.accountId == "c" }
        assertEquals(-7000L, c.amountMinor)
        assertEquals("ARS", c.commodity)
    }

    @Test
    fun elidedWithNothingToBalanceFails() {
        assertFailsWith<LedgerValidationException> {
            resolvePostings(listOf(DraftPosting("a", "100"), DraftPosting("b", "-100"), DraftPosting("c", "")))
        }
    }

    @Test
    fun multiCommodityBalancing() {
        val postings = resolved(
            listOf(
                DraftPosting("a", "1100", "ARS"),
                DraftPosting("b", "-1100", "ARS"),
                DraftPosting("c", "95,50", "USD"),
                DraftPosting("d", "-95,50", "USD"),
            ),
        )
        assertEquals(4, postings.size)
        // still valid: same drafts but with unbalanced USD would fail
        assertFailsWith<LedgerValidationException> {
            resolvePostings(
                listOf(
                    DraftPosting("a", "1100", "ARS"),
                    DraftPosting("b", "-1100", "ARS"),
                    DraftPosting("c", "95,50", "USD"),
                    DraftPosting("d", "-85,50", "USD"),
                ),
            )
        }
    }

    @Test
    fun conservedWhenValid() {
        val drafted = listOf(
            DraftPosting("a", "1.500,75"),
            DraftPosting("b", "-500,75"),
            DraftPosting("c", ""),
        )
        // balanced total (a + b + c) sums to 0 by construction
        val postings = resolvePostings(drafted)
        assertEquals(0L, postings.sumOf { it.amountMinor })
    }

    @Test
    fun elisionSumPerTouchedCommodityOnly() {
        // residual of ARS goes to the elided ARS posting; USD untouched
        assertFailsWith<LedgerValidationException> {
            resolvePostings(
                listOf(
                    DraftPosting("a", "100", "ARS"),
                    DraftPosting("b", "-105,50", "ARS"),
                    DraftPosting("c", "10", "USD"),
                    DraftPosting("d", "-95,50", "USD"),
                ),
            )
        }
    }

    @Test
    fun residualHelperIgnoresBlankDrafts() {
        val residuals = residualsOf(listOf(DraftPosting("a", "100"), DraftPosting("b", "")))
        assertEquals(mapOf("ARS" to 10000L), residuals)
    }

    @Test
    fun isValidMatchesValidation() {
        assertTrue(isValidTransaction(listOf(DraftPosting("a", "100"), DraftPosting("b", "-100"))))
        assertFalse(isValidTransaction(listOf(DraftPosting("a", "100"), DraftPosting("b", "100"))))
        assertFalse(isValidTransaction(listOf(DraftPosting(null, "1"), DraftPosting("b", "-1"))))
    }

    @Test
    fun seedIdPropagatesToTransactionId() {
        val postings = resolvePostings(
            listOf(DraftPosting("a", "100"), DraftPosting("b", "-100")),
            seedTransactionId = "tx-1",
        )
        assertTrue(postings.all { it.transactionId == "tx-1" })
    }

    @Test
    fun resolvedBalancedSetPassesTypeShape() {
        val postings = resolved(listOf(DraftPosting("a", "99,99"), DraftPosting("b", "")))
        assertIs<Posting>(postings[0])
        assertEquals(9999L, postings[0].amountMinor)
    }
}
