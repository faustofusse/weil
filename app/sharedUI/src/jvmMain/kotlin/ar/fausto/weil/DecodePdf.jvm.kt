package ar.fausto.weil

import androidx.compose.ui.graphics.ImageBitmap

// Skia rasterizes nothing PDF-shaped and the desktop target is the dev
// harness, so there is no renderer here: the screen shows its fallback.
actual fun decodePdfPages(bytes: ByteArray): List<ImageBitmap> = emptyList()
