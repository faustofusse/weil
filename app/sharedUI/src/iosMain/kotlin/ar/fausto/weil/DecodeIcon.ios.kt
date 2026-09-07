package ar.fausto.weil

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image

actual fun decodeIcon(encoded: ByteArray): ImageBitmap? =
    runCatching { Image.makeFromEncoded(encoded).toComposeImageBitmap() }.getOrNull()
