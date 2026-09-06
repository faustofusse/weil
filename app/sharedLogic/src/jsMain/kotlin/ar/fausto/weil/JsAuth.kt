package ar.fausto.weil

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.js.Js
import io.ktor.client.statement.HttpResponse

actual fun platformHttpClient(block: HttpClientConfig<*>.() -> Unit): HttpClient =
    HttpClient(Js) { block() }

actual fun platformUserAgent(): String? = null

actual suspend fun captureSessionCookie(response: HttpResponse, cookieName: String, store: SecureStore) {
}

actual fun storedCookieHeader(cookieName: String, store: SecureStore): String? = null

actual fun epochMillis(): Long = kotlin.js.Date.now().toLong()
