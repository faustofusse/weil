package ar.fausto.weil

import org.jetbrains.skia.Canvas
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Surface

/**
 * Draws a fake receipt instead of fetching one: the shot harness has no
 * session cookie, so the R2 read the real [ImportRepository] does would 401.
 */
class FakeDocumentFetcher : DocumentFetcher {
    override suspend fun fetch(docId: String): StoredDocument {
        val surface = Surface.makeRasterN32Premul(620, 860)
        val canvas: Canvas = surface.canvas
        canvas.clear(0xFFFFFFFF.toInt())
        // Bars, not text: the headless harness has no fonts, so drawString
        // renders nothing and the "receipt" would come out blank.
        val ink = Paint().apply { color = 0xFF1B1B1F.toInt() }
        val faint = Paint().apply { color = 0xFF9A9AA2.toInt() }
        canvas.drawRect(org.jetbrains.skia.Rect.makeLTRB(40f, 48f, 420f, 76f), ink)
        var y = 120f
        listOf(380f, 340f, 360f, 300f).forEach { width ->
            canvas.drawRect(org.jetbrains.skia.Rect.makeLTRB(40f, y, 40f + width, y + 16f), faint)
            y += 40f
        }
        canvas.drawRect(org.jetbrains.skia.Rect.makeLTRB(40f, y + 8f, 580f, y + 10f), faint)
        canvas.drawRect(org.jetbrains.skia.Rect.makeLTRB(40f, y + 32f, 360f, y + 56f), ink)
        val png = surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)!!.bytes
        return StoredDocument(png, "image/png")
    }
}
