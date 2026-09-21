package ar.fausto.weil

/**
 * Rasterizes PDF pages to PNG bytes. Kotlin/Native has no PDF renderer and
 * Skia only *writes* PDFs, so the Swift host implements this with PDFKit
 * (same pattern as the passkey/QR bridges in [IosBridges]).
 */
interface PdfPageRenderer {
    fun renderPages(pdf: ByteArray): List<ByteArray>
}
