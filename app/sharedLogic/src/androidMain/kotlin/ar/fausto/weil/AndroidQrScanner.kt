package ar.fausto.weil

import android.app.Activity
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Google Play services code scanner: Google's own full-screen scanning UI, no
 * camera permission in this app and no CameraX plumbing. Needs a foreground
 * activity, resolved lazily like the passkey ceremony.
 */
class AndroidQrScanner(private val activity: Activity) : QrScanner {
    override suspend fun scan(): String? = suspendCancellableCoroutine { cont ->
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
        GmsBarcodeScanning.getClient(activity, options)
            .startScan()
            .addOnSuccessListener { barcode ->
                if (cont.isActive) cont.resume(barcode.rawValue)
            }
            .addOnCanceledListener {
                if (cont.isActive) cont.resume(null)
            }
            .addOnFailureListener { e ->
                // The system back button dismisses the scanner without going
                // through addOnCanceledListener: GMS sends a generic error
                // code (13) instead of CODE_SCANNER_CANCELLED (201), so a
                // plain "back to leave the screen" looks like a real
                // failure unless we treat every scanner-side MlKitException
                // as a cancel too.
                if (e is MlKitException) {
                    if (cont.isActive) cont.resume(null)
                } else {
                    if (cont.isActive) cont.resumeWithException(e)
                }
            }
    }
}
