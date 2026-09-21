package ar.fausto.weil

import androidx.compose.ui.graphics.ImageBitmap

actual fun decodePdfPages(bytes: ByteArray): List<ImageBitmap> =
    IosBridges.pdfRenderer?.renderPages(bytes)?.mapNotNull { decodeIcon(it) }.orEmpty()
