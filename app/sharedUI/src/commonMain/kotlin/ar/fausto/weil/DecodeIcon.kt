package ar.fausto.weil

import androidx.compose.ui.graphics.ImageBitmap

/** Decodes a PNG (application icon) into a composable image; null on failure. */
expect fun decodeIcon(encoded: ByteArray): ImageBitmap?
