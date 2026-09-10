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

@Serializable
data class ChainDevice(
    val id: String,
    val label: String? = null,
    @SerialName("display_name") val displayName: String? = null,
    val current: Boolean = false,
    @SerialName("device_type") val deviceType: String? = null,
    @SerialName("user_agent") val userAgent: String? = null,
    @SerialName("backed_up") val backedUp: Int? = null,
    @SerialName("created_at") val createdAt: Long? = null,
    @SerialName("last_used_at") val lastUsedAt: Long? = null,
    @SerialName("revoked_at") val revokedAt: Long? = null,
)

@Serializable
data class ChainInvite(
    val url: String,
    val id: String,
    val token: String,
    @SerialName("expires_in") val expiresIn: Long = 0,
)

@Serializable
data class ChainRequest(
    val url: String,
    val id: String,
    @SerialName("expires_in") val expiresIn: Long = 0,
)

@Serializable
data class ChainRequestStatus(
    val status: String,
    @SerialName("join_url") val joinUrl: String? = null,
)
