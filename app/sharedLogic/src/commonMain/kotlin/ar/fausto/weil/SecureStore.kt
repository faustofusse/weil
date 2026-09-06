package ar.fausto.weil

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.statement.HttpResponse

interface SecureStore {
    fun read(key: String): String?
    fun write(key: String, value: String?)
}

internal const val COOKIE_STORE_PREFIX = "cookie:"

expect fun platformHttpClient(block: HttpClientConfig<*>.() -> Unit = {}): HttpClient

expect suspend fun captureSessionCookie(response: HttpResponse, cookieName: String, store: SecureStore)

expect fun storedCookieHeader(cookieName: String, store: SecureStore): String?

expect fun epochMillis(): Long
