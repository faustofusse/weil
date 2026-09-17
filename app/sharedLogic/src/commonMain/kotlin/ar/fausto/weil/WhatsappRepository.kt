package ar.fausto.weil

import io.ktor.client.call.body
import io.ktor.client.plugins.UserAgent
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A pairing code the user sends *from* WhatsApp. Proving ownership of a number
 * that way is what lets the app skip verifying a number the user typed in:
 * the message itself is the proof.
 */
data class WhatsappLinkCode(
    val code: String,
    /** Wall clock deadline (epoch ms) — the code is single use and short lived. */
    val expiresAt: Long,
    /** The bot's number, when the worker is configured with one. */
    val number: String?,
    /** `wa.me` deep link with the message pre-filled; also what the QR encodes. */
    val link: String?,
)

/** A number already linked to this account. */
data class WhatsappNumber(val number: String, val linkedAt: Long)

@Serializable
private data class WireLinkCode(
    val code: String,
    val expiresAt: Long,
    val number: String? = null,
    val link: String? = null,
)

@Serializable
private data class WireNumber(val number: String, val linkedAt: Long)

@Serializable
private data class WireNumbers(val numbers: List<WireNumber> = emptyList())

/**
 * Client for the finance worker's `/whatsapp/link` endpoints, cookie-authed
 * like [ImportRepository]. Messages themselves never touch the app: they
 * arrive at the bridge, are interpreted by the worker and written straight to
 * the user's database, which this device picks up on the next sync.
 */
class WhatsappRepository(
    private val store: SecureStore,
    private val baseUrl: String = AuthConfig.API_BASE_URL,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val client = platformHttpClient {
        install(ContentNegotiation) { json(json) }
        platformUserAgent()?.let { ua -> install(UserAgent) { agent = ua } }
    }
    private val cookieName = "auth_${AuthConfig.SLUG}"

    suspend fun numbers(): List<WhatsappNumber> {
        val resp = client.get("$baseUrl/whatsapp/link") { authCookie() }
        throwOnStatus(resp)
        return resp.body<WireNumbers>().numbers.map { WhatsappNumber(it.number, it.linkedAt) }
    }

    /** Issues a fresh code, invalidating any previous pending one. */
    suspend fun createCode(): WhatsappLinkCode {
        val resp = client.post("$baseUrl/whatsapp/link") { authCookie() }
        throwOnStatus(resp)
        return resp.body<WireLinkCode>().let {
            WhatsappLinkCode(it.code, it.expiresAt, it.number, it.link)
        }
    }

    suspend fun unlink(number: String) {
        val resp = client.delete("$baseUrl/whatsapp/link") {
            authCookie()
            parameter("number", number)
        }
        throwOnStatus(resp)
    }

    private fun io.ktor.client.request.HttpRequestBuilder.authCookie() {
        storedCookieHeader(cookieName, store)?.let { header(HttpHeaders.Cookie, it) }
    }

    private suspend fun throwOnStatus(resp: HttpResponse) {
        if (resp.status.isSuccess()) return
        val body = runCatching { resp.bodyAsText() }.getOrDefault("")
        throw IllegalStateException("whatsapp ${resp.status.value}: $body")
    }
}
