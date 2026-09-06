package ar.fausto.weil

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.cio.CIO
import io.ktor.client.statement.HttpResponse

actual fun platformHttpClient(block: HttpClientConfig<*>.() -> Unit): HttpClient =
    HttpClient(CIO) { block() }

actual fun platformUserAgent(): String? =
    "weil (JVM; ${System.getProperty("os.name")} ${System.getProperty("os.version")})"

actual suspend fun captureSessionCookie(response: HttpResponse, cookieName: String, store: SecureStore) {
}

actual fun storedCookieHeader(cookieName: String, store: SecureStore): String? = null

actual fun epochMillis(): Long = System.currentTimeMillis()
