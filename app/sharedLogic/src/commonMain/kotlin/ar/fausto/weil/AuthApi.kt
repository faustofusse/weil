package ar.fausto.weil

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.UserAgent
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class AuthApi(
    private val baseUrl: String,
    private val slug: String,
    private val store: SecureStore,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val client = platformHttpClient {
        install(ContentNegotiation) { json(json) }
        platformUserAgent()?.let { ua -> install(UserAgent) { agent = ua } }
    }
    private val cookieName = "auth_$slug"

    private fun url(path: String) = "$baseUrl/apps/$slug$path"

    suspend fun registerStart(): AuthStart = post("/register/start", "{}")

    suspend fun registerFinish(response: JsonObject, handle: String?): AuthFinish {
        val body = buildJsonObject {
            put("response", response)
            if (handle != null) put("handle", handle)
        }
        return postFinish("/register/finish", body)
    }

    suspend fun loginStart(): AuthStart = post("/login/start", "{}")

    suspend fun loginFinish(response: JsonObject): AuthFinish =
        postFinish("/login/finish", buildJsonObject { put("response", response) })

    suspend fun refresh(): TokenInfo {
        val resp = client.post(url("/session/refresh")) {
            storedCookieHeader(cookieName, store)?.let { header(HttpHeaders.Cookie, it) }
        }
        if (resp.status.value == 401) throw SessionExpired()
        throwOnStatus(resp)
        captureSessionCookie(resp, cookieName, store)
        return resp.body<RefreshResponse>().token
    }

    suspend fun logout() {
        try {
            client.post(url("/logout")) {
                storedCookieHeader(cookieName, store)?.let { header(HttpHeaders.Cookie, it) }
            }
        } finally {
            store.write(COOKIE_STORE_PREFIX + cookieName, null)
        }
    }

    // ---- sync chains (device pairing) ----

    suspend fun chainDevices(): List<ChainDevice> =
        authedGet<ChainDevicesResponse>("/chain").devices

    suspend fun chainRevoke(credId: String) {
        authedPost<JsonObject>("/chain/$credId/revoke", "{}")
    }

    suspend fun chainInvite(): ChainInvite = authedPost("/chain/invite", "{}")

    suspend fun chainApprove(requestId: String) {
        authedPost<JsonObject>("/chain/approve/$requestId", "{}")
    }

    suspend fun chainRequest(): ChainRequest = post("/chain/request", "{}")

    // ---- contact email (ingest routing) ----

    suspend fun getEmail(): String? {
        val v = authedGet<JsonObject>("/email")["email"] ?: return null
        val p = v as? kotlinx.serialization.json.JsonPrimitive ?: return null
        return if (p.isString) p.content else null
    }

    suspend fun setEmail(email: String): JsonObject =
        authedPost("/email", kotlinx.serialization.json.buildJsonObject { put("email", email) }.toString())

    // ---- display name (the app's greeting) ----

    /**
     * The name the user picked for themselves, or null when they never did.
     * It lives in the auth worker, not in the app's Turso database, because
     * it belongs to the account: a device that has just paired can greet its
     * owner before any app data has synced.
     */
    suspend fun getName(): String? {
        val v = authedGet<JsonObject>("/profile")["name"] ?: return null
        val p = v as? kotlinx.serialization.json.JsonPrimitive ?: return null
        return if (p.isString) p.content else null
    }

    /** Empty clears the name; the worker treats blank as "no name". */
    suspend fun setName(name: String): JsonObject =
        authedPost("/profile", kotlinx.serialization.json.buildJsonObject { put("name", name) }.toString())

    suspend fun chainRequestStatus(requestId: String): ChainRequestStatus {
        val resp = client.get(url("/chain/request/$requestId"))
        throwOnStatus(resp)
        return resp.body()
    }

    suspend fun chainJoinStart(chainId: String, token: String?): JsonObject =
        post(
            "/chain/join/$chainId",
            buildJsonObject { if (token != null) put("token", token) }.toString(),
        )

    suspend fun chainJoinFinish(chainId: String, token: String?, response: JsonObject): AuthFinish =
        postFinish(
            "/chain/join/$chainId",
            buildJsonObject {
                if (token != null) put("token", token)
                put("response", response)
            },
        )

    private suspend inline fun <reified T> authedGet(path: String): T {
        val resp = client.get(url(path)) {
            storedCookieHeader(cookieName, store)?.let { header(HttpHeaders.Cookie, it) }
        }
        throwOnStatus(resp)
        captureSessionCookie(resp, cookieName, store)
        return resp.body<T>()
    }

    private suspend inline fun <reified T> authedPost(path: String, body: String): T {
        val resp = client.post(url(path)) {
            contentType(ContentType.Application.Json)
            storedCookieHeader(cookieName, store)?.let { header(HttpHeaders.Cookie, it) }
            setBody(body)
        }
        throwOnStatus(resp)
        captureSessionCookie(resp, cookieName, store)
        return resp.body<T>()
    }

    @Serializable
private data class ChainDevicesResponse(val devices: List<ChainDevice> = emptyList())

private suspend inline fun <reified T> post(path: String, body: String): T {
        val resp = client.post(url(path)) {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        throwOnStatus(resp)
        captureSessionCookie(resp, cookieName, store)
        return resp.body<T>()
    }

    private suspend fun postFinish(path: String, body: JsonObject): AuthFinish {
        val resp = client.post(url(path)) {
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }
        throwOnStatus(resp)
        captureSessionCookie(resp, cookieName, store)
        return json.decodeFromString<AuthFinish>(resp.bodyAsText())
    }

    private suspend fun throwOnStatus(resp: HttpResponse) {
        if (resp.status.isSuccess()) return
        val text = resp.bodyAsText()
        val message = try {
            json.decodeFromString<JsonObject>(text)["error"]?.jsonPrimitive?.content ?: text
        } catch (_: Exception) {
            text
        }
        throw ApiException(resp.status.value, message)
    }
}
