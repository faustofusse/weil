package ar.fausto.weil

import kotlin.coroutines.CoroutineContext

object AuthConfig {
    const val BASE_URL = "https://auth.fausto.ar"
    const val SLUG = "finance"

    /** The app's WebAuthn rp_id host. Also serves the native passkey bridge
     * page (`/desktop-pair`) used by the desktop target. */
    const val RP_DOMAIN = "finance.fausto.ar"
}

class AppGraph(
    store: SecureStore,
    passkeys: () -> PasskeyCeremony,
    qrScanner: () -> QrScanner? = { null },
    dbContext: CoroutineContext,
    dbFactory: (userId: String, url: String, token: String) -> Database,
) {
    private val qrScannerProvider = qrScanner
    private val authApi = AuthApi(AuthConfig.BASE_URL, AuthConfig.SLUG, store)
    val auth = AuthRepository(authApi, store, passkeys)
    val chain = ChainRepository(authApi, auth)
    val db = DatabaseProvider(auth, dbContext, dbFactory)
    val accounts = AccountsRepository(db)
    val ledger = TransactionsRepository(db)
    val notifications = NotificationsRepository(db)
    val emails = EmailsRepository(db)
    val scanner: QrScanner? get() = qrScannerProvider()

    init {
        auth.restore()
    }
}
