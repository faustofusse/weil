package ar.fausto.weil

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970
import platform.UIKit.UIDevice

object IosBridges {
    var secureStore: SecureStore? = null
    var passkeyCeremony: PasskeyCeremony? = null
    var qrScanner: QrScanner? = null
    var documentPicker: DocumentPicker? = null
}

private const val PASSKEY_CANCELLED_MARKER = "passkey cancelled"
private const val PASSKEY_NOT_FOUND_MARKER = "passkey not found"

fun bridgedPasskeys(): PasskeyCeremony {
    val raw = requireNotNull(IosBridges.passkeyCeremony) { "PasskeyCeremony bridge not installed by Swift host" }
    return object : PasskeyCeremony {
        override suspend fun create(optionsJson: String): String = adapt { raw.create(optionsJson) }
        override suspend fun assert(optionsJson: String): String = adapt { raw.assert(optionsJson) }
    }
}

private inline fun <T> adapt(block: () -> T): T =
    try {
        block()
    } catch (e: Throwable) {
        throw translatePasskeyError(e)
    }

private fun translatePasskeyError(e: Throwable): Throwable {
    val message = e.message ?: return e
    return when {
        message.contains(PASSKEY_CANCELLED_MARKER) -> PasskeyCancelled()
        message.contains(PASSKEY_NOT_FOUND_MARKER) -> PasskeyNotFound()
        else -> e
    }
}

actual fun platformHttpClient(block: HttpClientConfig<*>.() -> Unit): HttpClient =
    HttpClient(Darwin) { block() }

actual fun platformUserAgent(): String? {
    val device = UIDevice.currentDevice
    return "weil (${device.systemName} ${device.systemVersion}; ${device.model})"
}

/**
 * Keeps the session cookie in the Keychain instead of trusting
 * NSHTTPCookieStorage: the worker sets `auth_finance` for `.fausto.ar` as
 * HttpOnly/Secure, and NSURLSession's shared jar did not hand it back to our
 * requests (Perfil and document import both answered "HTTP 401: missing
 * session cookie"). Same manual capture/replay Android does, so the cookie
 * also survives reinstalls of the URLSession stack and app restarts.
 */
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

actual fun epochMillis(): Long = (NSDate().timeIntervalSince1970 * 1000).toLong()
