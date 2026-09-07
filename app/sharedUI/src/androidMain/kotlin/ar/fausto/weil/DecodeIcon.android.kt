package ar.fausto.weil

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

actual fun decodeIcon(encoded: ByteArray): ImageBitmap? =
    BitmapFactory.decodeByteArray(encoded, 0, encoded.size)?.asImageBitmap()
