package ar.fausto.weil

import kotlin.coroutines.CoroutineContext

object AuthConfig {
    const val BASE_URL = "https://auth.fausto.ar"
    const val SLUG = "finance"
}

class AppGraph(
    store: SecureStore,
    passkeys: PasskeyCeremony,
    dbContext: CoroutineContext,
    dbFactory: (userId: String, url: String, token: String) -> Database,
) {
    val auth = AuthRepository(AuthApi(AuthConfig.BASE_URL, AuthConfig.SLUG, store), store, passkeys)
    val db = DatabaseProvider(auth, dbContext, dbFactory)
    val accounts = AccountsRepository(db)

    init {
        auth.restore()
    }
}
