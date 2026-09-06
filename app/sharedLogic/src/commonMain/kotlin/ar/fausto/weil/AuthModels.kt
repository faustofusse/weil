package ar.fausto.weil

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class TokenInfo(
    val jwt: String,
    @SerialName("db_url") val dbUrl: String,
    @SerialName("db_hostname") val dbHostname: String? = null,
    @SerialName("expires_in") val expiresIn: Long = 0,
)

@Serializable
data class AuthStart(val options: JsonObject, val handle: String? = null)

@Serializable
data class AuthFinish(
    val verified: Boolean = false,
    @SerialName("user_id") val userId: String,
    @SerialName("credential_id") val credentialId: String? = null,
    val token: TokenInfo,
)

@Serializable
data class RefreshResponse(val token: TokenInfo)

sealed interface AuthState {
    data object Restoring : AuthState
    data object LoggedOut : AuthState
    data class LoggedIn(val userId: String, val token: TokenInfo) : AuthState
}

class PasskeyCancelled(message: String = "passkey ceremony cancelled") : Exception(message)

class PasskeyNotFound(message: String = "no passkey available") : Exception(message)

class ApiException(val code: Int, message: String) : Exception("HTTP $code: $message")

class SessionExpired(message: String = "session expired") : Exception(message)

data class Account(val id: String, val name: String)
