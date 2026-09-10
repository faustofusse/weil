package ar.fausto.weil

/** Routes of the shared back stack. Login lives in it too, so auth state
 * changes animate through the same NavDisplay transitions as navigation. */
object LoginRoute

object HomeRoute

object NotificationsRoute

object EmailsRoute

object ProfileRoute

object JournalRoute

/** [accountId] pre-seeds the first posting when adding from an account screen. */
data class TransactionNewRoute(val accountId: String? = null)

data class TransactionEditRoute(val id: String)

data class AccountDetailRoute(val id: String)
