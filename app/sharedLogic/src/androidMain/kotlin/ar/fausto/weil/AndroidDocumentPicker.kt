package ar.fausto.weil

import android.app.Activity
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/** Storage Access Framework picker limited to the types the worker accepts. */
class AndroidDocumentPicker(private val activity: Activity) : DocumentPicker {

    override suspend fun pick(): PickedDocument? {
        val host = activity as? ComponentActivity
            ?: throw IllegalStateException("document picker needs a ComponentActivity")
        val uri = suspendCancellableCoroutine<Uri?> { cont ->
            // Registering straight on the result registry (instead of during
            // onCreate) keeps this out of the activity's lifecycle wiring;
            // the launcher is unregistered as soon as the result lands.
            val key = "document-pick-${System.nanoTime()}"
            var launcher: androidx.activity.result.ActivityResultLauncher<Array<String>>? = null
            launcher = host.activityResultRegistry.register(
                key,
                ActivityResultContracts.OpenDocument(),
            ) { result ->
                launcher?.unregister()
                if (cont.isActive) cont.resume(result)
            }
            cont.invokeOnCancellation { launcher.unregister() }
            // The aliases are only for the *filter*: a CSV written by a
            // spreadsheet app is often advertised under a legacy type, and a
            // filter of exactly "text/csv" greys it out in the picker.
            launcher.launch((IMPORTABLE_MIME_TYPES + CSV_MIME_ALIASES).distinct().toTypedArray())
        } ?: return null
        return activity.readDocument(uri)
    }
}

/**
 * Reads a `content://` document into memory. Only content URIs are accepted:
 * a shared `file://` URI could point anywhere in this app's own storage.
 */
internal fun Context.readDocument(uri: Uri): PickedDocument? {
    if (uri.scheme != ContentResolver.SCHEME_CONTENT) return null
    val resolver = contentResolver
    val reported = resolver.getType(uri)?.lowercase()?.substringBefore(';')?.trim() ?: return null
    val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
    if (bytes.isEmpty()) return null
    val name = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()
    // Providers describe a CSV in half a dozen ways (and generic file managers
    // fall back to text/plain or octet-stream); the extension is the reliable
    // signal, and the worker only knows one spelling.
    val looksLikeCsv = name?.endsWith(".csv", ignoreCase = true) == true
    val mime = when {
        reported in CSV_MIME_ALIASES -> "text/csv"
        looksLikeCsv && (reported == "text/plain" || reported == "application/octet-stream") -> "text/csv"
        else -> reported
    }
    return PickedDocument(bytes = bytes, mimeType = mime, name = name)
}

/**
 * Pulls the image/PDF/CSV out of an ACTION_SEND intent and offers it to the UI.
 * Returns true when the intent carried an importable document.
 */
fun Context.handleSharedDocument(intent: Intent?): Boolean {
    if (intent == null) return false
    if (intent.action != Intent.ACTION_SEND) return false
    @Suppress("DEPRECATION")
    val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) ?: return false
    val document = readDocument(uri) ?: return false
    if (document.mimeType !in IMPORTABLE_MIME_TYPES) return false
    SharedImportInbox.offer(document)
    return true
}
