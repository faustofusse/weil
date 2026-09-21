package ar.fausto.weil

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The quiet-record rule: which finished runs earn a row in `suggestions` and
 * which die where they fell. Pure, because the table behind it is the part
 * that cannot be tested without a device.
 */
class SuggestionWorthinessTest {

    private val candidate = ImportCandidate(
        date = 1_755_172_800_000L,
        payee = "Rappi",
        note = null,
        commodity = "ARS",
        direction = ImportDirection.Expense,
        splits = listOf(ImportSplit(2_138_900L, "food", "Comida:Pedidos")),
    )

    private val movement = ReadResponse(isMovement = true, payee = "Rappi", amount = "21389.00")

    private fun confident(relation: MatchRelation) = MatchOutcome.Confident(
        ScoredMatch(
            fact = LedgerFact("tx", 1_755_172_800_000L, "Rappi", emptyList(), emptySet()),
            relation = relation,
            score = 100,
            reasons = emptyList(),
        ),
    )

    @Test
    fun aMovementWithNothingLikeItIsRecorded() {
        assertTrue(worthRecording(movement, MatchOutcome.None, candidate))
    }

    @Test
    fun aPromotionIsNot() {
        assertFalse(worthRecording(movement.copy(isMovement = false), MatchOutcome.None, candidate))
    }

    @Test
    fun aReadingWithoutACandidateIsNot() {
        // The amount did not parse, so there is nothing to offer.
        assertFalse(worthRecording(movement, MatchOutcome.None, null))
    }

    @Test
    fun aFailedReadIsNot() {
        assertFalse(worthRecording(null, null, null))
    }

    @Test
    fun alreadyImportedIsTheOneMatchThatIsNot() {
        // The user already dealt with this exact message: asking again would
        // be the only outcome with nothing to ask.
        assertFalse(worthRecording(movement, confident(MatchRelation.AlreadyImported), candidate))
    }

    @Test
    fun aConfidentDuplicateStillIs() {
        // Probably recorded through another door, but attaching this message
        // as a source is worth one row that defaults to "asociar".
        assertTrue(worthRecording(movement, confident(MatchRelation.Duplicate), candidate))
    }

    @Test
    fun anAmbiguousOneIs() {
        assertTrue(
            worthRecording(
                movement,
                MatchOutcome.Ambiguous(
                    listOf(
                        ScoredMatch(
                            fact = LedgerFact("tx", 1_755_172_800_000L, "Rappi", emptyList(), emptySet()),
                            relation = MatchRelation.Duplicate,
                            score = 60,
                            reasons = emptyList(),
                        ),
                    ),
                ),
                candidate,
            ),
        )
    }
}
