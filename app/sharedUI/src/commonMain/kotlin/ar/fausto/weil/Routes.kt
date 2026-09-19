package ar.fausto.weil

/** Routes of the shared back stack. Login lives in it too, so auth state
 * changes animate through the same NavDisplay transitions as navigation. */
object LoginRoute

object HomeRoute

object NotificationsRoute

/** Texto completo de una notificación capturada, más sus parecidas. */
data class NotificationDetailRoute(val id: String)

object EmailsRoute

data class EmailDetailRoute(val id: String)

object ProfileRoute

object JournalRoute

/** The full account tree, reachable from the Home top bar. */
object AccountsTreeRoute

/** Simplified transaction kinds shown on the Home FAB menu. */
enum class TxnKind { Expense, Income, Transfer }

/** [TxnKind]-driven simplified transaction entry screen. */
data class TransactionQuickRoute(val kind: TxnKind)

/** [accountId] pre-seeds the first posting when adding from an account screen. */
data class TransactionNewRoute(val accountId: String? = null)

/** Vista de solo lectura de una transacción; el editor está a un tap. */
data class TransactionDetailRoute(val id: String)

data class TransactionEditRoute(val id: String)

data class AccountDetailRoute(val id: String)

/** [initialType] preselects the type picker (Home suggests Activo); the user can still change it. */
data class AccountAddRoute(val initialType: AccountType? = null)

/**
 * Review of an image/PDF the user picked or shared into the app. The document
 * itself rides in the route: the back stack is in-memory, and holding the
 * bytes here keeps them alive across the analyze round trip.
 */
data class ImportReviewRoute(val document: PickedDocument)

/**
 * Review of the movements recognized in captured notifications and email
 * receipts — the same screen as [ImportReviewRoute], reading the device's own
 * inbox instead of a document.
 */
object InboxReviewRoute
