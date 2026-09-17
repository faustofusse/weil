package ar.fausto.weil

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DefaultAccountTest {

    private fun tree(vararg accounts: Account) = AccountsRepository.buildTree(accounts.toList())

    private fun asset(id: String, name: String = id, parentId: String? = null) =
        Account(id, name, parentId, AccountType.Asset)

    private fun expense(id: String, name: String = id) =
        Account(id, name, null, AccountType.Expense)

    @Test
    fun storedDefaultWins() {
        val t = tree(asset("bank", "Banco"), asset("cash", "Efectivo"))
        assertEquals("cash", resolveDefault(t, AccountType.Asset, "cash"))
    }

    @Test
    fun storedDefaultCanBeANestedAccount() {
        val t = tree(asset("bank", "Banco"), asset("usd", "Dolares", parentId = "bank"))
        assertEquals("usd", resolveDefault(t, AccountType.Asset, "usd"))
    }

    @Test
    fun deletedDefaultFallsBackInsteadOfDangling() {
        val t = tree(asset("bank", "Banco"))
        assertEquals("bank", resolveDefault(t, AccountType.Asset, "gone"))
    }

    @Test
    fun defaultOfAnotherTypeIsIgnored() {
        val t = tree(asset("bank", "Banco"), expense("food", "Comida"))
        assertEquals("bank", resolveDefault(t, AccountType.Asset, "food"))
    }

    @Test
    fun categoriesFallBackToTheSeededOtros() {
        val t = tree(expense("food", "Comida"), expense(EXTERNAL_EXPENSE_ID, EXTERNAL_ACCOUNT_NAME))
        assertEquals(EXTERNAL_EXPENSE_ID, resolveDefault(t, AccountType.Expense, null))
    }

    @Test
    fun categoriesNeverGuessAnArbitraryAccount() {
        val t = tree(expense("food", "Comida"), expense("rent", "Alquiler"))
        assertNull(resolveDefault(t, AccountType.Expense, null))
    }

    @Test
    fun aLoneCategoryIsUnambiguous() {
        val t = tree(expense("food", "Comida"))
        assertEquals("food", resolveDefault(t, AccountType.Expense, null))
    }

    @Test
    fun emptyTreeHasNoDefault() {
        assertNull(resolveDefault(emptyList(), AccountType.Asset, "bank"))
    }

    @Test
    fun keysAreStableAcrossDevices() {
        assertEquals("default_account.asset", defaultAccountKey(AccountType.Asset))
        assertEquals("default_account.expense", defaultAccountKey(AccountType.Expense))
    }
}
