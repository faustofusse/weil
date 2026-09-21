package ar.fausto.weil

import androidx.compose.ui.graphics.ImageBitmap

/**
 * Rasterizes every page of a PDF, in order. Plain images decode through
 * [decodeIcon]; a PDF needs a platform renderer (PdfRenderer on Android,
 * PDFKit via the Swift bridge on iOS). Empty where no renderer is wired
 * (desktop, or a corrupt file) — the screen shows its fallback then.
 */
expect fun decodePdfPages(bytes: ByteArray): List<ImageBitmap>
