package ar.fausto.weil

import kotlin.test.Test
import kotlin.test.assertEquals

class HomeOrderTest {

    private fun roots(vararg ids: String) =
        AccountsRepository.buildTree(ids.map { Account(it, it, null, AccountType.Asset) })

    private fun ids(nodes: List<AccountNode>) = nodes.map { it.account.id }

    @Test
    fun emptyOrderKeepsTreeOrder() {
        val t = roots("a", "b", "c")
        assertEquals(ids(t), ids(applyHomeOrder(t, emptyList())))
    }

    @Test
    fun placedFirstThenRest() {
        val t = roots("a", "b", "c", "d")
        assertEquals(listOf("c", "a", "b", "d"), ids(applyHomeOrder(t, listOf("c", "a"))))
    }

    @Test
    fun deletedIdsAreSkippedAndNewAccountsGoLast() {
        val t = roots("a", "b", "new")
        assertEquals(listOf("b", "a", "new"), ids(applyHomeOrder(t, listOf("gone", "b", "a"))))
    }

    @Test
    fun parseToleratesJunk() {
        assertEquals(listOf("a", "b"), parseHomeOrder(" a,,b ,a"))
        assertEquals(emptyList(), parseHomeOrder(null))
    }
}
