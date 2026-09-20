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
}
