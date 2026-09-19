package ar.fausto.weil

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

private const val MP_PACKAGE = "com.mercadopago.wallet"

/**
 * Opens Mercado Pago on its payment confirmation screen for an already
 * scanned QR, via the exported `mercadopago://qr_code` deep link (the same
 * code path MP's own camera callback takes, so interoperable EMVCo QRs from
 * other acquirers work too).
 *
 * Built with the **application** context: no foreground activity is needed,
 * hence FLAG_ACTIVITY_NEW_TASK. Every step is guarded — a handoff that fails
 * must degrade to "scan it in MP as before", never to a crash.
 */
class AndroidWalletLauncher(context: Context) : WalletLauncher {
    private val appContext = context.applicationContext

    override fun payWithMercadoPago(rawQr: String): Boolean {
        val encoded = Uri.encode(rawQr)
        val deepLink = "mercadopago://qr_code?from=external_access&qr_data=$encoded"
        return start(viewIntent(deepLink)) ||
            // MP changed the link? At least land the user on its scanner.
            start(viewIntent("mercadopago://scan_qr")) ||
            start(runCatching { appContext.packageManager.getLaunchIntentForPackage(MP_PACKAGE) }.getOrNull())
    }

    private fun viewIntent(uri: String): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage(MP_PACKAGE)

    private fun start(intent: Intent?): Boolean {
        val target = intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) ?: return false
        if (target.resolveActivity(appContext.packageManager) == null) return false
        return try {
            appContext.startActivity(target)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }
}
