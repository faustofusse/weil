package ar.fausto.weil

import platform.Foundation.NSURL
import platform.UIKit.UIApplication

/**
 * iOS half of the QR handoff: opens Mercado Pago on its payment confirmation
 * screen for an already scanned payload, over the same `mercadopago://qr_code`
 * deep link Android uses.
 *
 * Two honest differences from [AndroidWalletLauncher]:
 *
 * - iOS only ever tells you about the **scheme**. `canOpenURL` and the open
 *   completion handler both answer "some installed app claims `mercadopago://`",
 *   never "that app routes this host". So a true return means MP was opened,
 *   not that it understood the payload — if MP ever drops the `qr_code` route
 *   the user lands inside MP rather than on the confirm screen, and the row we
 *   record would be for a payment they may not finish. That is the same
 *   failure Android guards with `resolveActivity`, and it is unguardable here;
 *   the recorded row is a placeholder in the journal either way.
 * - `canOpenURL` needs `mercadopago` listed under `LSApplicationQueriesSchemes`
 *   in the app's Info.plist. Without it the call returns false with no error
 *   and the feature silently reports "no wallet installed".
 *
 * Must be called from the main thread (Compose event callbacks are).
 */
class IosWalletLauncher : WalletLauncher {
    override fun payWithMercadoPago(rawQr: String): Boolean {
        return open("mercadopago://qr_code?from=external_access&qr_data=${percentEncode(rawQr)}") ||
            // MP changed the route? At least land the user on its scanner,
            // which is exactly where they were before this feature existed.
            open("mercadopago://scan_qr")
    }

    private fun open(uri: String): Boolean {
        val url = NSURL.URLWithString(uri) ?: return false
        val app = UIApplication.sharedApplication
        if (!app.canOpenURL(url)) return false
        // Fire and forget: the completion handler lands after this function
        // has returned, and it carries no more information than canOpenURL
        // already gave us.
        app.openURL(url, options = emptyMap<Any?, Any>(), completionHandler = null)
        return true
    }
}

private const val UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.~"

/**
 * Percent-encodes everything outside RFC 3986's unreserved set.
 *
 * Hand-rolled rather than `stringByAddingPercentEncoding`, which needs an
 * `NSString` a Kotlin `String` cannot be cast to, and stricter than
 * `NSURLComponents`, which leaves `+` alone in a query value — legal per the
 * RFC and read as a space by half the servers and routers in existence. An
 * EMVCo payload is opaque text whose bytes must arrive unchanged, so nothing
 * outside the unreserved set is left to interpretation.
 */
internal fun percentEncode(value: String): String = buildString {
    for (byte in value.encodeToByteArray()) {
        val char = byte.toInt().toChar()
        if (char in UNRESERVED) {
            append(char)
        } else {
            append('%')
            append(((byte.toInt() and 0xFF) / 16).toString(16).uppercase())
            append(((byte.toInt() and 0x0F)).toString(16).uppercase())
        }
    }
}
