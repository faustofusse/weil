package ar.fausto.weil

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val DAY = 86_400_000L
private const val AUG14 = 1_755_172_800_000L // 2025-08-14T12:00Z, the noon-UTC shape docs use

private fun fact(
    id: String,
    date: Long = AUG14,
    payee: String,
    legs: List<FactLeg>,
    eventKeys: Set<String> = emptySet(),
) = LedgerFact(id, date, payee, legs, eventKeys)

private fun own(account: String, amount: Long, id: String = "p-$account") =
    FactLeg(id, account, AccountType.Asset, amount, "ARS")

private fun category(account: String, amount: Long, id: String = "p-$account") =
    FactLeg(id, account, AccountType.Expense, amount, "ARS")

private fun event(
    account: String? = "mp",
    amount: Long,
    date: Long = AUG14,
    payee: String = "Transferencia",
) = CandidateEvent(
    source = EventSource.Document,
    sourceRef = "doc1",
    ownAccountId = account,
    amountMinor = amount,
    commodity = "ARS",
    date = date,
    rawPayee = payee,
    direction = if (amount < 0) ImportDirection.Expense else ImportDirection.Income,
)

class PayeeNormalizationTest {

    @Test
    fun collapses_channel_noise_and_reference_codes() {
        assertEquals("coto", normalizePayee("MERPAGO*COTO 4821"))
        assertEquals("coto", normalizePayee("Coto CICSA"))
        assertEquals("coto", normalizePayee("coto"))
    }

    @Test
    fun folds_accents_and_punctuation() {
        assertEquals("almacen jose", normalizePayee("Almacén  José!"))
    }

    @Test
    fun keeps_something_when_every_token_is_noise() {
        // "visa tarj 1234" is all noise; emptying it would make two unrelated
        // rows compare equal, so the unfiltered form survives.
        assertEquals("visa tarj 1234", normalizePayee("VISA tarj 1234"))
    }

    @Test
    fun fingerprint_ignores_the_door_the_event_came_through() {
        val fromDoc = event(amount = -20_000_00).eventKey
        val fromPush = event(amount = -20_000_00).copy(
            source = EventSource.Notification,
            sourceRef = "notif-9",
        ).eventKey
        assertEquals(fromDoc, fromPush)
    }

    @Test
    fun fingerprint_is_day_granular() {
        val noon = event(amount = -1_000_00, date = AUG14)
        val evening = event(amount = -1_000_00, date = AUG14 + 8 * 3_600_000L)
        assertEquals(noon.eventKey, evening.eventKey)
        assertTrue(noon.eventKey != event(amount = -1_000_00, date = AUG14 + DAY).eventKey)
    }
}

class MatchEventTest {

    @Test
    fun no_facts_means_create() {
        assertIs<MatchOutcome.None>(matchEvent(event(amount = -1_000_00), emptyList()))
    }

    @Test
    fun already_imported_wins_without_scoring() {
        val incoming = event(amount = -1_000_00)
        val outcome = matchEvent(
            incoming,
            listOf(
                fact(
                    "t1",
                    payee = "otra cosa",
                    legs = listOf(own("santander", -1_000_00)),
                    eventKeys = setOf(incoming.eventKey),
                ),
            ),
        )
        val match = assertIs<MatchOutcome.Confident>(outcome).match
        assertEquals(MatchRelation.AlreadyImported, match.relation)
    }

    @Test
    fun same_account_same_sign_is_a_duplicate() {
        val outcome = matchEvent(
            event(amount = -12_400_00, payee = "COTO CICSA"),
            listOf(
                fact(
                    "t1",
                    payee = "MERPAGO*COTO 4821",
                    legs = listOf(own("mp", -12_400_00), category("comida", 12_400_00)),
                ),
            ),
        )
        val match = assertIs<MatchOutcome.Confident>(outcome).match
        assertEquals(MatchRelation.Duplicate, match.relation)
        assertNull(match.retargetPostingId)
        assertTrue(MatchReason.SameAmount in match.reasons)
    }

    @Test
    fun opposite_account_opposite_sign_is_a_transfer_mirror() {
        // The Santander statement recorded the incoming 200.000 as income from
        // a category because it could not tell the payer was the user; the
        // Mercado Pago statement now brings the other half.
        val outcome = matchEvent(
            event(account = "mp", amount = -200_000_00, payee = "Fausto Fusse"),
            listOf(
                fact(
                    "t1",
                    payee = "Transferencia recibida",
                    legs = listOf(
                        own("santander", 200_000_00),
                        FactLeg("dangling", "otros-income", AccountType.Income, -200_000_00, "ARS"),
                    ),
                ),
            ),
        )
        val match = assertIs<MatchOutcome.Confident>(outcome).match
        assertEquals(MatchRelation.Mirror, match.relation)
        assertEquals("dangling", match.retargetPostingId)
        assertTrue(MatchReason.OppositeAccount in match.reasons)
    }

    @Test
    fun a_mirror_on_the_same_account_is_not_a_match() {
        // Money leaving and re-entering the same account on the same day is a
        // refund, not the other half of anything.
        val outcome = matchEvent(
            event(account = "mp", amount = -16_100_00),
            listOf(fact("t1", payee = "Passline", legs = listOf(own("mp", 16_100_00)))),
        )
        assertIs<MatchOutcome.None>(outcome)
    }

    @Test
    fun two_identical_charges_the_same_day_stay_ambiguous() {
        val legs = listOf(own("mp", -11_000_00), category("futbol", 11_000_00))
        val outcome = matchEvent(
            event(amount = -11_000_00, payee = "Transferencia recibida"),
            listOf(
                fact("t1", payee = "Transferencia recibida", legs = legs),
                fact("t2", payee = "Transferencia recibida", legs = legs),
            ),
        )
        // Both score identically, so neither is decisive: the user picks.
        assertEquals(2, assertIs<MatchOutcome.Ambiguous>(outcome).matches.size)
    }

    @Test
    fun a_different_commodity_never_matches() {
        val outcome = matchEvent(
            event(amount = -21_23).copy(commodity = "USD"),
            listOf(fact("t1", payee = "Anomaly", legs = listOf(own("mp", -21_23)))),
        )
        assertIs<MatchOutcome.None>(outcome)
    }

    @Test
    fun outside_the_date_window_never_matches() {
        val outcome = matchEvent(
            event(amount = -1_000_00, date = AUG14 + 10 * DAY),
            listOf(fact("t1", payee = "Transferencia", legs = listOf(own("mp", -1_000_00)))),
        )
        assertIs<MatchOutcome.None>(outcome)
    }

    @Test
    fun a_near_day_weak_payee_match_is_only_a_suggestion() {
        val outcome = matchEvent(
            event(amount = -1_000_00, date = AUG14 + 2 * DAY, payee = "Kiosco"),
            listOf(fact("t1", payee = "Libreria", legs = listOf(own("mp", -1_000_00)))),
        )
        assertIs<MatchOutcome.Ambiguous>(outcome)
    }

    @Test
    fun amount_drift_matches_only_within_the_configured_tolerance() {
        val incoming = event(amount = -12_500_00, payee = "Shell")
        val facts = listOf(fact("t1", payee = "Shell", legs = listOf(own("mp", -12_400_00))))
        assertIs<MatchOutcome.None>(matchEvent(incoming, facts))
        val lenient = matchEvent(incoming, facts, MatchPolicy(amountToleranceMinor = 200_00))
        assertTrue(lenient !is MatchOutcome.None)
    }

    @Test
    fun an_unknown_own_account_falls_back_to_the_sign() {
        val outcome = matchEvent(
            event(account = null, amount = -1_000_00, payee = "Kiosco"),
            listOf(fact("t1", payee = "Kiosco", legs = listOf(own("mp", -1_000_00)))),
        )
        val match = assertIs<MatchOutcome.Confident>(outcome).match
        assertEquals(MatchRelation.Duplicate, match.relation)
    }
}

class MatchAllTest {

    @Test
    fun one_stored_transaction_is_claimed_by_only_one_candidate() {
        // A statement that prints the same USD charge four times (the real
        // Santander case) used to auto-associate all four to the single stored
        // row, quietly collapsing four movements into one.
        val facts = listOf(
            fact(
                "t1",
                payee = "Anomaly",
                legs = listOf(own("card", -21_23), category("software", 21_23)),
            ),
        )
        val events = List(4) { event(account = "card", amount = -21_23, payee = "Anomaly") }
        val outcomes = matchAll(events, facts)
        assertEquals(1, outcomes.count { it is MatchOutcome.Confident })
        // The losers keep the evidence so the row can still say why.
        assertEquals(3, outcomes.count { it is MatchOutcome.Ambiguous })
    }

    @Test
    fun the_best_scoring_candidate_keeps_the_match() {
        val facts = listOf(
            fact("t1", payee = "Coto", legs = listOf(own("mp", -5_000_00))),
        )
        val weak = event(account = "mp", amount = -5_000_00, date = AUG14 + DAY, payee = "Otra cosa")
        val strong = event(account = "mp", amount = -5_000_00, payee = "Coto")
        val outcomes = matchAll(listOf(weak, strong), facts)
        assertIs<MatchOutcome.Ambiguous>(outcomes[0])
        assertIs<MatchOutcome.Confident>(outcomes[1])
    }

    @Test
    fun distinct_candidates_keep_their_own_matches() {
        val facts = listOf(
            fact("t1", payee = "Metrogas", legs = listOf(own("mp", -68_140_27))),
            fact("t2", payee = "Edenor", legs = listOf(own("mp", -37_588_31))),
        )
        val outcomes = matchAll(
            listOf(
                event(amount = -68_140_27, payee = "Metrogas"),
                event(amount = -37_588_31, payee = "Edenor"),
            ),
            facts,
        )
        assertTrue(outcomes.all { it is MatchOutcome.Confident })
    }
}

class CandidateEventMappingTest {

    @Test
    fun expenses_and_transfers_leave_the_account_negative() {
        val candidate = ImportCandidate(
            date = AUG14,
            payee = "Mercado Pago",
            note = null,
            commodity = "ARS",
            direction = ImportDirection.Transfer,
            accountId = "mp",
            splits = listOf(ImportSplit(200_000_00, "santander", "Santander")),
        )
        assertEquals(-200_000_00L, candidate.toEvent("doc1", null).amountMinor)
    }

    @Test
    fun income_arrives_positive_and_falls_back_to_the_default_account() {
        val candidate = ImportCandidate(
            date = AUG14,
            payee = "Nestor Dario Fusse",
            note = null,
            commodity = "ARS",
            direction = ImportDirection.Income,
            accountId = null,
            splits = listOf(ImportSplit(400_000_00, null, null)),
        )
        val mapped = candidate.toEvent("doc1", fallbackOwnAccountId = "mp")
        assertEquals(400_000_00L, mapped.amountMinor)
        assertEquals("mp", mapped.ownAccountId)
    }
}
