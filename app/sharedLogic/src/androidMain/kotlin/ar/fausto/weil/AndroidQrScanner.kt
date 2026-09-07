package ar.fausto.weil

import android.app.Activity
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
                if (cont.isActive) cont.resumeWithException(e)
            }
    }
}
