package ar.fausto.weil

import kotlin.coroutines.CoroutineContext

object AuthConfig {
    const val BASE_URL = "https://auth.fausto.ar"
    const val SLUG = "finance"

    /** The app's own worker (document import, email ingest). Shares the
     * `.fausto.ar` session cookie with [BASE_URL]. */
    const val API_BASE_URL = "https://api.finance.fausto.ar"

    /** The app's WebAuthn rp_id host. Also serves the native passkey bridge
     * page (`/desktop-pair`) used by the desktop target. */
    const val RP_DOMAIN = "finance.fausto.ar"
}

class AppGraph(
    store: SecureStore,
    passkeys: () -> PasskeyCeremony,
    qrScanner: () -> QrScanner? = { null },
    documentPicker: () -> DocumentPicker? = { null },
    wallet: () -> WalletLauncher? = { null },
    // Swapped by the desktop screenshot harness for a seeded fake; the app
    // always uses the worker-backed [ImportRepository].
    importAnalyzer: DocumentAnalyzer? = null,
    // Same reason as [importAnalyzer]: the shot harness has no session, so it
    // substitutes a local deterministic embedder.
    embedder: TextEmbedder? = null,
    // Same again: the live category guess in the quick-entry screen needs the
    // worker (and the TypeSafe key it holds).
    categorySuggester: CategorySuggester? = null,
    // And again for the notification→transaction bench: two worker calls.
    suggester: SuggestTracer? = null,
    dbContext: CoroutineContext,
    dbFactory: (userId: String, url: String, token: String) -> Database,
    // Optional higher-priority context for pure reads; defaults to dbContext
    // (i.e. no reordering) on platforms that don't set one up. See
    // DatabaseProvider.readContext.
    dbReadContext: CoroutineContext = dbContext,
) {
    private val qrScannerProvider = qrScanner
    private val documentPickerProvider = documentPicker

    /** Hands a scanned QR to a wallet app; null where no wallet is wired. */
    val wallet: WalletLauncher? = wallet()
    private val authApi = AuthApi(AuthConfig.BASE_URL, AuthConfig.SLUG, store)
    val auth = AuthRepository(authApi, store, passkeys)
    val chain = ChainRepository(authApi, auth)
    val db = DatabaseProvider(auth, dbContext, dbFactory, dbReadContext)
    val accounts = AccountsRepository(db)
    val ledger = TransactionsRepository(db)
    val settings = SettingsRepository(db)
    val notifications = NotificationsRepository(db)
    val emails = EmailsRepository(db)
    val imports: DocumentAnalyzer = importAnalyzer ?: ImportRepository(store)

    /** Live "which category is this" guess while the description is typed. */
    val categories: CategorySuggester = categorySuggester ?: CategorySuggestRepository(store)

    /** Vectors for "parecidos a este"; search itself is offline SQL. */
    val embeddings = EmbeddingsRepository(db, embedder ?: WorkerEmbedder(store))

    /** Linking a phone number so messages to the bot become transactions. */
    val whatsapp = WhatsappRepository(store)

    /** Placeholder ledger entries for QRs paid in a wallet app. */
    val qrPayments = QrPayments(accounts, settings, ledger)

    /** Movements recognized in captured notifications and email receipts. */
    val ingest = IngestRepository(notifications, emails, accounts, ledger)

    /** One captured message → a proposed transaction (Gemini reads, Jev picks). */
    val suggestions: SuggestTracer =
        suggester ?: SuggestRepository(notifications, accounts, ledger, embeddings, store)
    val scanner: QrScanner? get() = qrScannerProvider()

    /** Needs a foreground activity/controller, so it is resolved lazily. */
    val documents: DocumentPicker? get() = documentPickerProvider()

    init {
        auth.restore()
    }
}
