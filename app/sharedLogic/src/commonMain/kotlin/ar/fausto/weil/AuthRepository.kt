package ar.fausto.weil

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

class AuthRepository(
    private val api: AuthApi,
    private val store: SecureStore,
    private val passkeys: () -> PasskeyCeremony,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val _state = MutableStateFlow<AuthState>(AuthState.Restoring)
    val state: StateFlow<AuthState> = _state.asStateFlow()

    fun restore() {
        val userId = store.read(KEY_USER_ID)
        val jwt = store.read(KEY_JWT)
        val dbUrl = store.read(KEY_DB_URL)
        _state.value = if (userId != null && jwt != null && dbUrl != null) {
            AuthState.LoggedIn(userId, TokenInfo(jwt, dbUrl, store.read(KEY_DB_HOSTNAME)))
        } else {
            AuthState.LoggedOut
        }
    }

    suspend fun signIn() {
        // A device that has never held an account has no passkey to log in with;
        // going straight to registration avoids a doomed biometric prompt first.
        if (store.read(KEY_KNOWN_USER) == null) {
            register()
            return
        }
        try {
            val start = api.loginStart()
            val responseJson = passkeys().assert(json.encodeToString(start.options))
            finish(api.loginFinish(json.decodeFromString<JsonObject>(responseJson)))
        } catch (e: PasskeyCancelled) {
            throw e
        } catch (e: Throwable) {
            // login failure usually means no passkey yet — fall through to registration
            register()
        }
    }

    private suspend fun register() {
        val start = api.registerStart()
        val responseJson = passkeys().create(json.encodeToString(start.options))
        finish(api.registerFinish(json.decodeFromString<JsonObject>(responseJson), start.handle))
    }

    suspend fun ensureFreshToken(): TokenInfo {
        val current = _state.value
        val info = when (current) {
            is AuthState.LoggedIn -> current.token
            else -> throw SessionExpired()
        }
        val expiresAt = store.read(KEY_EXPIRES_AT)?.toLongOrNull() ?: 0L
        if (epochMillis() < expiresAt - EXPIRY_MARGIN_MILLIS) return info
        return refresh()
    }

    suspend fun refresh(): TokenInfo {
        return try {
            val token = api.refresh()
            val current = _state.value
            val userId = (current as? AuthState.LoggedIn)?.userId
                ?: store.read(KEY_USER_ID)
                ?: throw SessionExpired()
            writeToken(userId, token)
            token
        } catch (e: SessionExpired) {
            signOutLocal()
            throw e
        } catch (e: ApiException) {
            if (e.code == 401) {
                signOutLocal()
                throw SessionExpired()
            }
            throw e
        }
    }

    fun signOutLocal() {
        // keep KEY_KNOWN_USER: after sign-out the next sign-in must try login
        // first (the passkey still exists on this device) rather than register
        // a fresh account and orphan the user's database.
        listAllKeys().forEach { store.write(it, null) }
        _state.value = AuthState.LoggedOut
    }

    suspend fun signOut() {
        try {
            api.logout()
        } catch (_: Exception) {
        }
        signOutLocal()
    }

    private suspend fun finish(result: AuthFinish) {
        writeToken(result.userId, result.token)
    }

    private fun writeToken(userId: String, token: TokenInfo) {
        store.write(KEY_USER_ID, userId)
        store.write(KEY_KNOWN_USER, userId)
        store.write(KEY_JWT, token.jwt)
        store.write(KEY_DB_URL, token.dbUrl)
        store.write(KEY_DB_HOSTNAME, token.dbHostname)
        store.write(KEY_EXPIRES_AT, (epochMillis() + token.expiresIn * 1000).toString())
        _state.value = AuthState.LoggedIn(userId, token)
    }

    private fun listAllKeys(): List<String> =
        listOf(KEY_USER_ID, KEY_JWT, KEY_DB_URL, KEY_DB_HOSTNAME, KEY_EXPIRES_AT)

    private companion object {
        const val KEY_USER_ID = "user_id"
        const val KEY_KNOWN_USER = "known_user"
        const val KEY_JWT = "jwt"
        const val KEY_DB_URL = "db_url"
        const val KEY_DB_HOSTNAME = "db_hostname"
        const val KEY_EXPIRES_AT = "expires_at"
        const val EXPIRY_MARGIN_MILLIS = 60_000L
    }
}
