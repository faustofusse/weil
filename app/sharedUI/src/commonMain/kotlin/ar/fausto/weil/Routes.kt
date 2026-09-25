package ar.fausto.weil

/** Routes of the shared back stack. Login lives in it too, so auth state
 * changes animate through the same NavDisplay transitions as navigation. */
object LoginRoute

object HomeRoute

/**
 * The tab shell: one entry on the outer back stack standing for "the four
 * tabs". The bar lives here and the tabs swap *inside* it, so switching one
 * animates the content only — the bar doesn't fade out and back in with it.
 */
object TabsRoute

object NotificationsRoute

/** Texto completo de una notificación capturada, más sus parecidas. */
data class NotificationDetailRoute(val id: String)

/**
 * The test bench for the message→transaction path: runs the two model
 * calls and shows everything they were given and answered. Writes nothing.
 */
data class SuggestDebugRoute(
    val id: String,
    /**
     * Which door the message came through. The same bench serves a push alert
     * and an email receipt, because one pipeline reads both.
     */
    val source: EventSource = EventSource.Notification,
)

/** The suggestion's sources: the raw vector neighbours of one captured
 * message, with the button that runs the pipeline and opens the trace. */
data class SuggestSourcesRoute(
    val id: String,
    val source: EventSource = EventSource.Notification,
)

/** The end of that path: the proposed row, ready to review and record. */
data class SuggestedReviewRoute(
    val candidate: ImportCandidate,
    val ref: String,
    val title: String,
    val source: EventSource = EventSource.Notification,
)

object EmailsRoute

data class EmailDetailRoute(val id: String)

/** El original (foto o PDF) del que se leyó un movimiento; solo lectura. */
data class DocumentRoute(val docId: String)

/**
 * Not a tab any more: pushed from the account icon in Inicio's top bar. The
 * bar slot went to [InvestmentsRoute].
 */
object ProfileRoute

/** The investments tab: brokers, consolidated positions, their movements. */
object InvestmentsRoute

/**
 * Review of a broker plan before it is written. Carries the plan itself
 * (the stack holds plain objects, never serialized) plus what the screen
 * needs to show it: the broker's accounts and the commodity scales.
 */
data class BrokerImportRoute(
    val brokerName: String,
    val provider: String,
    val plan: BrokerPlan,
    val accounts: BrokerAccounts,
    val scales: Map<String, Int>,
)

object JournalRoute

/** The full account tree, reachable from the Home top bar. */
object AccountsTreeRoute

/** Expense categories only — a bottom-bar root; the full plan is [AccountsTreeRoute]. */
object CategoriesRoute

/** Simplified transaction kinds shown on the Home FAB menu. */
enum class TxnKind { Expense, Income, Transfer }

/** [TxnKind]-driven simplified transaction entry screen. */
data class TransactionQuickRoute(val kind: TxnKind)

/** [accountId] pre-seeds the first posting when adding from an account screen. */
data class TransactionNewRoute(val accountId: String? = null)

/** Vista de solo lectura de una transacción; el editor está a un tap. */
data class TransactionDetailRoute(val id: String)

data class TransactionEditRoute(val id: String)

/** The lab bench for a transaction: its vector neighbours across the three
 * embedded tables, with the same link buttons [SuggestSourcesRoute] shows
 * for a captured message — reachable from the flask icon, not shown inline
 * on the read-only detail. */
data class TransactionSimilarRoute(val id: String)

data class AccountDetailRoute(val id: String)

/**
 * One expense category: its subcategories as chips, its movements below.
 * Separate from [AccountDetailRoute] because the two are read differently —
 * an asset wants a register with a running balance, a category wants the
 * list of what was bought.
 */
data class CategoryDetailRoute(val id: String)

/** [initialType] preselects the type picker (Home suggests Activo); the user can still change it. */
data class AccountAddRoute(val initialType: AccountType? = null)

/**
 * Review of an image/PDF the user picked or shared into the app. The document
 * itself rides in the route: the back stack is in-memory, and holding the
 * bytes here keeps them alive across the analyze round trip.
 */
data class ImportReviewRoute(val document: PickedDocument)
