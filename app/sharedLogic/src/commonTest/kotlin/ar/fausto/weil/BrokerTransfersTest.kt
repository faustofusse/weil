package ar.fausto.weil

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BrokerTransfersTest {
    private val day = 86_400_000L
    private val t0 = 1_787_000_000_000L
    private val ibkrUsd = "ibkr-usd"
    private val broker = setOf(ibkrUsd, "ibkr-cartera")
    /** The IBKR deposit of 1.895,07 dollars. */
    private val deposit = TransferLeg(ibkrUsd, 189_507L, "USD")

    private fun leg(id: String, account: String, type: AccountType, amount: Long, commodity: String = "USD") =
        FactLeg(id, account, type, amount, commodity)

    private fun fact(id: String, date: Long, payee: String, vararg legs: FactLeg, refs: Set<String> = emptySet()) =
        LedgerFact(id, date, payee, legs.toList(), sourceRefs = refs)

    /** The bank push, filed under a category: the usual case. */
    private val bankPush = fact(
        "push", t0 - 2 * day, "Transferencia a Interactive Brokers LLC",
        leg("p1", "bank-usd", AccountType.Asset, -189_507L),
        leg("p2", "otros", AccountType.Expense, 189_507L),
    )

    @Test
    fun theBankHalfIsLinkedByRepointingItsCategoryLeg() {
        val links = transferLinks(deposit, t0, broker, IBKR_PROVIDER, listOf(bankPush))
        val link = links.single()
        assertEquals(TransferLink.Kind.Mirror, link.kind)
        assertEquals("bank-usd", link.ownAccountId)
        assertEquals("p2", link.retargetPostingId)
        val op = transferAssociation(link, "ibkr:42", ibkrUsd)
        assertEquals("push", op.transactionId)
        assertEquals(ibkrUsd, op.retargetAccountId)
        assertEquals(listOf(TransactionSource(EventSource.Broker, "ibkr:42")), op.sources)
    }

    @Test
    fun aTransferTypedByHandIsOnlyTagged() {
        val typed = fact(
            "typed", t0, "IBKR",
            leg("t1", "bank-usd", AccountType.Asset, -189_507L),
            leg("t2", ibkrUsd, AccountType.Asset, 189_507L),
        )
        val link = transferLinks(deposit, t0, broker, IBKR_PROVIDER, listOf(typed)).single()
        assertEquals(TransferLink.Kind.Recorded, link.kind)
        assertNull(link.retargetPostingId)
        assertNull(transferAssociation(link, "ibkr:42", ibkrUsd).retargetAccountId)
    }

    @Test
    fun onlyTheSameMoneyNearbyAndNotAlreadyLinked() {
        fun push(id: String, date: Long, amount: Long, commodity: String = "USD", refs: Set<String> = emptySet(), other: AccountType = AccountType.Expense) =
            fact(id, date, "Transferencia", leg("$id-1", "bank", AccountType.Asset, -amount, commodity), leg("$id-2", "x", other, amount, commodity), refs = refs)
        val facts = listOf(
            push("off-by-a-cent", t0, 189_506L),
            push("pesos", t0, 189_507L, "ARS"),
            push("too-old", t0 - 9 * day, 189_507L),
            push("linked", t0, 189_507L, refs = setOf("ibkr:7")),
            // A transfer between own accounts is no dangling half.
            push("own", t0, 189_507L, other = AccountType.Asset),
            // Money *into* the bank is not where a deposit came from.
            fact("wrong-way", t0, "x", leg("w1", "bank", AccountType.Asset, 189_507L), leg("w2", "x", AccountType.Income, -189_507L)),
        )
        assertEquals(emptyList(), transferLinks(deposit, t0, broker, IBKR_PROVIDER, facts))
    }

    @Test
    fun closerAndNamedWinsAndNoMovementServesTwoDeposits() {
        val unnamed = fact(
            "unnamed", t0, "Transferencia saliente",
            leg("u1", "bank-usd", AccountType.Asset, -189_507L),
            leg("u2", "otros", AccountType.Expense, 189_507L),
        )
        val links = transferLinks(deposit, t0, broker, IBKR_PROVIDER, listOf(unnamed, bankPush))
        // Two days away but naming the broker beats the same day unnamed.
        assertEquals(listOf("push", "unnamed"), links.map { it.transactionId })

        val assigned = assignTransferLinks(mapOf("ibkr:1" to links, "ibkr:2" to links))
        assertEquals("push", assigned["ibkr:1"]?.transactionId)
        assertEquals("unnamed", assigned["ibkr:2"]?.transactionId)
        val one = assignTransferLinks(mapOf("ibkr:1" to links.take(1), "ibkr:2" to links.take(1)))
        assertEquals(1, one.size)
    }

    @Test
    fun brokerNamesAreWholeWords() {
        assertTrue(namesBroker("TRANSF. A INVERTIRONLINE S.A.U.", IOL_PROVIDER))
        assertTrue(namesBroker("Pago IOL", IOL_PROVIDER))
        assertFalse(namesBroker("Violeta", IOL_PROVIDER))
        assertTrue(namesBroker("Interactive Brokers LLC", IBKR_PROVIDER))
    }

    @Test
    fun theCashLegOfAPlannedTransfer() {
        val planned = PlannedTransaction(
            PlannedKind.Transfer,
            NewTransaction(t0, "Depósito IBKR", null, listOf(DraftPosting(ibkrUsd, "1895.07", "USD"), DraftPosting("saldo-inicial", "", "USD"))),
            "ibkr:42",
            needsCounterpart = true,
        )
        assertEquals(deposit, transferLeg(planned, listOf(ibkrUsd)))
        assertNull(transferLeg(planned.copy(kind = PlannedKind.Trade), listOf(ibkrUsd)))
    }

    @Test
    fun anIolDifferenceSearchesEveryDateAndTheScreenDemandsTheName() {
        // IOL:Pesos is short by $ 500.000: a transfer recorded only at the bank.
        val short = TransferLeg("iol-ars", 50_000_000L, "ARS")
        val months = fact(
            "old", t0 - 60 * day, "Transferencia a INVERTIRONLINE SAU",
            leg("o1", "bank", AccountType.Asset, -50_000_000L, "ARS"),
            leg("o2", "otros", AccountType.Expense, 50_000_000L, "ARS"),
        )
        val links = transferLinks(short, null, setOf("iol-ars"), IOL_PROVIDER, listOf(months))
        assertEquals("old", links.single { namesBroker(it.payee, IOL_PROVIDER) }.transactionId)
    }
}
