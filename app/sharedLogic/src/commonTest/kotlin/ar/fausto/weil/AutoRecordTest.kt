package ar.fausto.weil

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The auto-record rule: what a finished suggestion run writes to the ledger
 * and what dies where it fell. Pure, because the database behind it is the
 * part that cannot be tested without a device.
 */
class AutoRecordTest {

    private val at = 1_755_172_800_000L

    private fun node(id: String, name: String, type: AccountType) = AccountNode(
        account = Account(id = id, name = name, parentId = null, type = type),
        path = name,
        children = emptyList(),
    )

    private val tree = listOf(
        node("bank", "Banco", AccountType.Asset),
        node("food", "Comida", AccountType.Expense),
        node("salary", "Sueldo", AccountType.Income),
    )

    private val paths = mapOf("bank" to "Banco", "food" to "Comida", "salary" to "Sueldo")

    private fun read(
        movement: Boolean = true,
        direction: String = "expense",
        amount: String = "21389.00",
        account: String? = "Banco",
    ) = ReadResponse(
        isMovement = movement,
        direction = direction,
        payee = "Rappi",
        amount = amount,
        commodity = "ARS",
        account = account,
    )

    /** A run as the pipeline hands it over: a reading, Jev's picks, a match. */
    private fun trace(
        read: ReadResponse? = read(),
        match: MatchOutcome? = MatchOutcome.None,
        category: String? = "Comida",
    ) = SuggestTrace(
        message = TracedMessage(
            source = EventSource.Notification,
            ref = "notif-1",
            origin = "Mercado Pago",
            title = "Pagaste $ 21.389",
            text = "Pagaste $ 21.389 a Rappi",
            at = at,
        ),
        read = read,
        match = match,
        decision = AccountsResponse(
            expenseCategory = category?.let { PickedAccount(path = it, confidence = 0.9) },
            incomeCategory = category?.let { PickedAccount(path = it, confidence = 0.9) },
        ),
        accountPaths = paths,
    )

    private fun match(relation: MatchRelation, postingId: String? = null) = ScoredMatch(
        fact = LedgerFact("tx-1", at, "Rappi", emptyList(), emptySet()),
        relation = relation,
        score = 100,
        reasons = emptyList(),
        retargetPostingId = postingId,
    )

    @Test
    fun aMovementWithNothingLikeItIsWritten() {
        val created = assertIs<AutoRecordPlan.Create>(
            planAutoRecord(EventSource.Notification, "notif-1", trace(), tree, emptyMap()),
        )
        assertEquals("Rappi", created.entry.payee)
        assertEquals(listOf("bank", "food"), created.entry.drafts.map { it.accountId })
        // Expense: the account pays, the category receives.
        assertEquals("-21.389,00", created.entry.drafts[0].amountText)
        assertEquals("21.389,00", created.entry.drafts[1].amountText)
        // Provenance: the message, plus the door-independent fingerprint that
        // stops a statement from importing the same purchase again.
        val source = created.entry.sources.single()
        assertEquals(EventSource.Notification, source.kind)
        assertEquals("notif-1", source.ref)
        assertTrue(source.eventKey!!.startsWith("bank|"))
    }

    @Test
    fun anIncomeFlipsBothLegs() {
        val created = assertIs<AutoRecordPlan.Create>(
            planAutoRecord(
                EventSource.Notification,
                "notif-1",
                trace(read = read(direction = "income"), category = "Sueldo"),
                tree,
                emptyMap(),
            ),
        )
        assertEquals(listOf("bank", "salary"), created.entry.drafts.map { it.accountId })
        assertEquals("21.389,00", created.entry.drafts[0].amountText)
        assertEquals("-21.389,00", created.entry.drafts[1].amountText)
    }

    @Test
    fun anUnnamedAccountFallsBackToTheUsersDefault() {
        val created = assertIs<AutoRecordPlan.Create>(
            planAutoRecord(
                EventSource.Notification,
                "notif-1",
                trace(read = read(account = null)),
                tree,
                emptyMap(),
            ),
        )
        assertEquals("bank", created.entry.drafts[0].accountId)
    }

    @Test
    fun anUncategorizedExpenseStillLandsOnTheDefaultCategory() {
        // The balance of the account is the part that must be right; the
        // category is one tap to fix and visible in the journal.
        val created = assertIs<AutoRecordPlan.Create>(
            planAutoRecord(EventSource.Notification, "notif-1", trace(category = null), tree, emptyMap()),
        )
        assertEquals("food", created.entry.drafts[1].accountId)
    }

    @Test
    fun anEmailIsRecordedTheSameWayWithItsOwnDoorOnTheProvenance() {
        // One pipeline, two doors: the receipt writes the same transaction
        // the push would have, and the `email` source is what later lets the
        // matcher see them as one movement.
        val created = assertIs<AutoRecordPlan.Create>(
            planAutoRecord(EventSource.Email, "mail-1", trace(), tree, emptyMap()),
        )
        val source = created.entry.sources.single()
        assertEquals(EventSource.Email, source.kind)
        assertEquals("mail-1", source.ref)
        // Same fingerprint shape as the notification's: that is the point.
        assertTrue(source.eventKey!!.startsWith("bank|"))
    }

    @Test
    fun aPromotionWritesNothing() {
        val skip = assertIs<AutoRecordPlan.Skip>(
            planAutoRecord(EventSource.Notification, "notif-1", trace(read = read(movement = false)), tree, emptyMap()),
        )
        assertEquals(AutoRecordSkip.NotAMovement, skip.reason)
    }

    @Test
    fun anUnreadableAmountWritesNothing() {
        val skip = assertIs<AutoRecordPlan.Skip>(
            planAutoRecord(EventSource.Notification, "notif-1", trace(read = read(amount = "")), tree, emptyMap()),
        )
        assertEquals(AutoRecordSkip.NoAmount, skip.reason)
    }

    @Test
    fun anAlreadyImportedMessageWritesNothing() {
        val skip = assertIs<AutoRecordPlan.Skip>(
            planAutoRecord(
                EventSource.Notification,
                "notif-1",
                trace(match = MatchOutcome.Confident(match(MatchRelation.AlreadyImported))),
                tree,
                emptyMap(),
            ),
        )
        assertEquals(AutoRecordSkip.AlreadyRecorded, skip.reason)
    }

    @Test
    fun aConfidentDuplicateIsAttachedInsteadOfWrittenTwice() {
        val attach = assertIs<AutoRecordPlan.Attach>(
            planAutoRecord(
                EventSource.Notification,
                "notif-1",
                trace(match = MatchOutcome.Confident(match(MatchRelation.Duplicate))),
                tree,
                emptyMap(),
            ),
        )
        assertEquals("tx-1", attach.op.transactionId)
        assertEquals("notif-1", attach.op.sources.single().ref)
        // A duplicate attaches provenance only; nothing is repointed.
        assertNull(attach.op.retargetPostingId)
    }

    @Test
    fun aConfidentMirrorAlsoRepointsTheDanglingLeg() {
        val attach = assertIs<AutoRecordPlan.Attach>(
            planAutoRecord(
                EventSource.Notification,
                "notif-1",
                trace(match = MatchOutcome.Confident(match(MatchRelation.Mirror, "p-9"))),
                tree,
                emptyMap(),
            ),
        )
        assertEquals("p-9", attach.op.retargetPostingId)
        assertEquals("bank", attach.op.retargetAccountId)
    }

    @Test
    fun anAmbiguousMatchIsWrittenRatherThanMerged() {
        // Two coffees of the same price on the same day are two coffees. A
        // visible duplicate can be deleted; a silent merge takes money out of
        // an account balance and nobody notices.
        assertIs<AutoRecordPlan.Create>(
            planAutoRecord(
                EventSource.Notification,
                "notif-1",
                trace(match = MatchOutcome.Ambiguous(listOf(match(MatchRelation.Duplicate)))),
                tree,
                emptyMap(),
            ),
        )
    }

    @Test
    fun withoutAnyAccountOfTheUsersNothingCanBeWritten() {
        val skip = assertIs<AutoRecordPlan.Skip>(
            planAutoRecord(
                EventSource.Notification,
                "notif-1",
                trace(read = read(account = null)),
                emptyList(),
                emptyMap(),
            ),
        )
        assertEquals(AutoRecordSkip.NoOwnAccount, skip.reason)
    }

    @Test
    fun theGateItself() {
        val movement = read()
        val candidate = trace().candidate()
        assertTrue(worthRecording(movement, MatchOutcome.None, candidate))
        assertFalse(worthRecording(movement.copy(isMovement = false), MatchOutcome.None, candidate))
        assertFalse(worthRecording(movement, MatchOutcome.None, null))
        assertFalse(worthRecording(null, null, null))
    }
}
