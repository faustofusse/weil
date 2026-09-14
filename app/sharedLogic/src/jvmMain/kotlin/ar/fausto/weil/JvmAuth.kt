package ar.fausto.weil

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.cio.CIO
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders

actual fun platformHttpClient(block: HttpClientConfig<*>.() -> Unit): HttpClient =
    HttpClient(CIO) { block() }

actual fun platformUserAgent(): String? =
    "weil (JVM; ${System.getProperty("os.name")} ${System.getProperty("os.version")})"

// Mirrors AndroidAuth.kt's implementation — the JVM target has no cookie jar
// of its own, so the auth_finance refresh cookie is persisted through
// SecureStore and replayed manually as a header on every request.
actual suspend fun captureSessionCookie(response: HttpResponse, cookieName: String, store: SecureStore) {
    val cookies = response.headers.getAll(HttpHeaders.SetCookie) ?: return
    for (raw in cookies) {
        val first = raw.substringBefore(';').trim()
        val name = first.substringBefore('=').trim()
        if (name == cookieName) {
            store.write(COOKIE_STORE_PREFIX + cookieName, "$name=${first.substringAfter('=', "")}")
            return
        }
    }
}

actual fun storedCookieHeader(cookieName: String, store: SecureStore): String? =
    store.read(COOKIE_STORE_PREFIX + cookieName)

actual fun epochMillis(): Long = System.currentTimeMillis()
