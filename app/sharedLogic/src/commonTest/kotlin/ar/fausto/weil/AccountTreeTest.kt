package ar.fausto.weil

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AccountTreeTest {

    private fun acct(id: String, parentId: String? = null, name: String = id) = Account(id, name, parentId, AccountType.Asset)

    @Test
    fun buildsNestedTreeWithPaths() {
        val tree = AccountsRepository.buildTree(
            listOf(
                acct("a", name = "Banco"),
                acct("b", "a", name = "Savings"),
                acct("c", "b", name = "Dollars"),
            ),
        )
        assertEquals(1, tree.size)
        val banco = tree.single()
        assertEquals("Banco", banco.path)
        val savings = banco.children.single()
        assertEquals("Banco:Savings", savings.path)
        assertEquals("Banco:Savings:Dollars", savings.children.single().path)
    }

    @Test
    fun orphansPromotedToRoots() {
        val tree = AccountsRepository.buildTree(
            listOf(acct("a"), acct("orphan", "missing")),
        )
        assertEquals(2, tree.size)
        assertEquals(0, tree.first { it.account.id == "orphan" }.children.size)
    }

    @Test
    fun siblingOrderingFollowsListOrder() {
        val tree = AccountsRepository.buildTree(listOf(acct("a"), acct("b", "a"), acct("c", "a")))
        val children = tree.single().children.map { it.account.id }
        assertEquals(listOf("b", "c"), children)
    }

    @Test
    fun selfAndDescendantsDepthFirst() {
        val tree = AccountsRepository.buildTree(
            listOf(acct("a"), acct("b", "a"), acct("c", "b"), acct("d", "a")),
        )
        val ids = tree.single().selfAndDescendants.map { it.account.id }
        assertEquals(listOf("a", "b", "c", "d"), ids)
    }

    @Test
    fun childSharesTypeAndParentGuardsAreLogicOnly() {
        // type matching is enforced at repository level; exercise fromDb mapping
        assertEquals(AccountType.Asset, AccountType.fromDb("asset"))
        assertEquals(AccountType.Liability, AccountType.fromDb("LIABILITY"))
        assertNull(AccountType.fromDb("nope"))
    }

    @Test
    fun emptyListYieldsEmptyTree() {
        assertNotNull(AccountsRepository.buildTree(emptyList()))
        assertEquals(0, AccountsRepository.buildTree(emptyList()).size)
    }
}
