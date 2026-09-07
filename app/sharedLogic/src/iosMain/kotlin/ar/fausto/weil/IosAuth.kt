package ar.fausto.weil

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.statement.HttpResponse
import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970
import platform.UIKit.UIDevice

object IosBridges {
    var secureStore: SecureStore? = null
    var passkeyCeremony: PasskeyCeremony? = null
    var qrScanner: QrScanner? = null
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

actual suspend fun captureSessionCookie(response: HttpResponse, cookieName: String, store: SecureStore) {
}

actual fun storedCookieHeader(cookieName: String, store: SecureStore): String? = null

actual fun epochMillis(): Long = (NSDate().timeIntervalSince1970 * 1000).toLong()
