package ar.fausto.weil

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File

actual fun decodePdfPages(bytes: ByteArray): List<ImageBitmap> = runCatching {
    // PdfRenderer needs a seekable fd, so the bytes go through a temp file.
    val tmp = File.createTempFile("weil-doc", ".pdf")
    try {
        tmp.writeBytes(bytes)
        ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                (0 until renderer.pageCount).map { index ->
                    renderer.openPage(index).use { page ->
                        // Page units are 1/72in; 3x lands near print density,
                        // so a statement's small print stays legible.
                        val bitmap = Bitmap.createBitmap(
                            page.width * 3,
                            page.height * 3,
                            Bitmap.Config.ARGB_8888,
                        )
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bitmap.asImageBitmap()
                    }
                }
            }
        }
    } finally {
        tmp.delete()
    }
}.getOrDefault(emptyList())
