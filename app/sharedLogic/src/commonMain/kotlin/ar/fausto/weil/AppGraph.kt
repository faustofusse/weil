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
    // Swapped by the desktop screenshot harness for a seeded fake; the app
    // always uses the worker-backed [ImportRepository].
    importAnalyzer: DocumentAnalyzer? = null,
    dbContext: CoroutineContext,
    dbFactory: (userId: String, url: String, token: String) -> Database,
    // Optional higher-priority context for pure reads; defaults to dbContext
    // (i.e. no reordering) on platforms that don't set one up. See
    // DatabaseProvider.readContext.
    dbReadContext: CoroutineContext = dbContext,
) {
    private val qrScannerProvider = qrScanner
    private val documentPickerProvider = documentPicker
    private val authApi = AuthApi(AuthConfig.BASE_URL, AuthConfig.SLUG, store)
    val auth = AuthRepository(authApi, store, passkeys)
    val chain = ChainRepository(authApi, auth)
    val db = DatabaseProvider(auth, dbContext, dbFactory, dbReadContext)
    val accounts = AccountsRepository(db)
    val ledger = TransactionsRepository(db)
    val notifications = NotificationsRepository(db)
    val emails = EmailsRepository(db)
    val imports: DocumentAnalyzer = importAnalyzer ?: ImportRepository(store)
    val scanner: QrScanner? get() = qrScannerProvider()

    /** Needs a foreground activity/controller, so it is resolved lazily. */
    val documents: DocumentPicker? get() = documentPickerProvider()

    init {
        auth.restore()
    }
}
