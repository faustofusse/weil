package ar.fausto.weil

import kotlin.test.Test
import kotlin.test.assertEquals

class SuggestCandidateTest {
    @Test
    fun expenseStaysExpense() {
        val trace = SuggestTrace(
            read = ReadResponse(
                isMovement = true,
                direction = "expense",
                payee = "Rappi",
                amount = "21389.00",
                commodity = "ARS",
                account = "Mercado Pago",
                normalized = "compra en Rappi",
            ),
            decision = AccountsResponse(
                direction = PickedAccount("expense", 0.91),
                myAccount = PickedAccount("Mercado Pago", 0.95),
                expenseCategory = PickedAccount("Comida:Pedidos", 0.8),
                incomeCategory = PickedAccount(null, 0.9),
            ),
            accountPaths = mapOf("mp" to "Mercado Pago", "food" to "Comida:Pedidos"),
        )
        val candidate = trace.candidate()!!
        assertEquals(ImportDirection.Expense, candidate.direction)
        assertEquals("mp", candidate.accountId)
        assertEquals("food", candidate.splits.single().categoryAccountId)
        assertEquals(2138900L, candidate.splits.single().amountMinor)
    }

    /**
     * The reader read the sentence; the Choice is only a cross-check. Jev
     * answered "income" on "Pagaste ... a Rappi" once, and taking its vote
     * turned a delivery order into a salary.
     */
    @Test
    fun readerWinsTheDirection() {
        val trace = SuggestTrace(
            read = ReadResponse(
                isMovement = true,
                direction = "expense",
                payee = "Rappi",
                amount = "21389.00",
                commodity = "ARS",
                normalized = "compra en Rappi",
            ),
            decision = AccountsResponse(
                direction = PickedAccount("income", 0.71),
                expenseCategory = PickedAccount("Comida:Pedidos", 0.92),
                incomeCategory = PickedAccount(null, 0.94),
            ),
            accountPaths = mapOf("food" to "Comida:Pedidos"),
        )
        val candidate = trace.candidate()!!
        assertEquals(ImportDirection.Expense, candidate.direction)
        assertEquals("food", candidate.splits.single().categoryAccountId)
    }
}
