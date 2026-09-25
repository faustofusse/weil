package ar.fausto.weil

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

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

    /**
     * One button, no decisions: sign in with the Weil passkey this device
     * already has, and create one (a new account) only when it has none.
     *
     * Registering is deliberately *not* the answer to a failed login. A
     * network error or a passkey the server no longer knows used to fall
     * through to `register()`, and every such fall-through minted another
     * account plus another passkey in the password manager — which is how a
     * phone ends up offering three "finance user" entries, only one of which
     * holds the user's data. A passkey the server rejects is reported to the
     * password manager instead (it disappears from the picker) and the login
     * runs again over what is left.
     */
    suspend fun signIn() {
        val ceremony = passkeys()
        repeat(MAX_REJECTED_PASSKEYS) {
            val start = api.loginStart()
            val assertion = try {
                json.decodeFromString<JsonObject>(ceremony.assert(json.encodeToString(start.options)))
            } catch (_: PasskeyNotFound) {
                register(ceremony)
                return
            }
            val result = try {
                api.loginFinish(assertion)
            } catch (e: ApiException) {
                if (e.code != 401 || e.message?.contains(UNKNOWN_CREDENTIAL) != true) throw e
                assertion.string("id")?.let { id ->
                    quietly { ceremony.signalUnknownCredential(AuthConfig.RP_DOMAIN, id) }
                }
                return@repeat
            }
            finish(result)
            val handle = assertion["response"]?.jsonObject?.string("userHandle")
            if (handle != null) {
                store.write(KEY_USER_HANDLE, handle)
                tidyPasskeys(ceremony, handle, result)
            }
            return
        }
        throw PasskeyRejected()
    }

    private suspend fun register(ceremony: PasskeyCeremony) {
        val start = api.registerStart()
        val responseJson = ceremony.create(json.encodeToString(start.options))
        finish(api.registerFinish(json.decodeFromString<JsonObject>(responseJson), start.handle))
        start.options["user"]?.jsonObject?.string("id")?.let { store.write(KEY_USER_HANDLE, it) }
    }

    /**
     * After a login, align the password manager with the server: passkeys of
     * this account that were revoked go away, and the rest take the account's
     * name instead of the generated id older registrations used.
     */
    private suspend fun tidyPasskeys(ceremony: PasskeyCeremony, handle: String, result: AuthFinish) {
        result.credentialIds?.takeIf { it.isNotEmpty() }?.let { ids ->
            quietly { ceremony.signalAcceptedCredentials(AuthConfig.RP_DOMAIN, handle, ids) }
        }
        result.passkeyName?.let { name ->
            quietly { ceremony.signalUserDetails(AuthConfig.RP_DOMAIN, handle, name) }
        }
    }

    /** Follows a profile rename into the password manager. */
    suspend fun renamePasskeys(name: String) {
        val handle = store.read(KEY_USER_HANDLE) ?: return
        quietly {
            passkeys().signalUserDetails(AuthConfig.RP_DOMAIN, handle, name.trim().ifEmpty { AuthConfig.APP_NAME })
        }
    }

    private inline fun quietly(block: () -> Unit) {
        try {
            block()
        } catch (_: Throwable) {
            // Signals are hints to the password manager; never fail a sign-in on one.
        }
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    /**
     * Join an existing account's sync chain: two-phase chain/join ceremony that
     * registers a new passkey on the linked user and signs this device in.
     */
    suspend fun joinChain(chainId: String, token: String?) {
        val start = api.chainJoinStart(chainId, token)
        val options = start.getValue("options").jsonObject
        val responseJson = passkeys().create(json.encodeToString(options))
        finish(api.chainJoinFinish(chainId, token, json.decodeFromString<JsonObject>(responseJson)))
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
        store.write(KEY_JWT, token.jwt)
        store.write(KEY_DB_URL, token.dbUrl)
        store.write(KEY_DB_HOSTNAME, token.dbHostname)
        store.write(KEY_EXPIRES_AT, (epochMillis() + token.expiresIn * 1000).toString())
        _state.value = AuthState.LoggedIn(userId, token)
    }

    private fun listAllKeys(): List<String> =
        listOf(KEY_USER_ID, KEY_JWT, KEY_DB_URL, KEY_DB_HOSTNAME, KEY_EXPIRES_AT, KEY_USER_HANDLE)

    private companion object {
        const val KEY_USER_ID = "user_id"
        const val KEY_JWT = "jwt"
        const val KEY_DB_URL = "db_url"
        const val KEY_DB_HOSTNAME = "db_hostname"
        const val KEY_EXPIRES_AT = "expires_at"
        const val KEY_USER_HANDLE = "user_handle"

        /** The auth worker's answer to a passkey it has no record of (or revoked). */
        const val UNKNOWN_CREDENTIAL = "credential revoked or unknown"

        /** Stale passkeys skipped in one sign-in before giving up. */
        const val MAX_REJECTED_PASSKEYS = 4
        const val EXPIRY_MARGIN_MILLIS = 60_000L
    }
}
