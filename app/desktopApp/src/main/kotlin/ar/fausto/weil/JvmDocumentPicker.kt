package ar.fausto.weil

import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * AWT's native file dialog (desktop is the UI workbench for the import flow;
 * the real entry points are the Android share target and the iOS picker).
 */
class JvmDocumentPicker : DocumentPicker {
    override suspend fun pick(): PickedDocument? = withContext(Dispatchers.Main) {
        val dialog = FileDialog(null as Frame?, "Importar documento", FileDialog.LOAD)
        dialog.setFilenameFilter { _, name -> mimeOf(name) != null }
        dialog.isVisible = true
        val directory = dialog.directory ?: return@withContext null
        val file = dialog.file ?: return@withContext null
        val picked = File(directory, file)
        val mime = mimeOf(picked.name) ?: return@withContext null
        PickedDocument(bytes = picked.readBytes(), mimeType = mime, name = picked.name)
    }

    private fun mimeOf(name: String): String? =
        when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
            "pdf" -> "application/pdf"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "heic" -> "image/heic"
            "heif" -> "image/heif"
            else -> null
        }
}
