package ar.fausto.weil

import kotlin.coroutines.CoroutineContext

object AuthConfig {
    const val BASE_URL = "https://auth.fausto.ar"
    const val SLUG = "finance"
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
    val notifications = NotificationsRepository(db)
    val scanner: QrScanner? get() = qrScannerProvider()

    init {
        auth.restore()
    }
}
