package ar.fausto.weil

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import android.graphics.Color as AndroidColor

actual @Composable fun SystemBarsEffect(dark: Boolean) {
    val activity = LocalContext.current.let { remember(it) { it.findActivity() } }
        as? ComponentActivity
        ?: return
    // Transparent bars either way; `dark` only flips the icon tint — light
    // icons over a dark page, dark icons over a pale one. Re-calling
    // `enableEdgeToEdge` is the supported way to update the tint.
    DisposableEffect(dark) {
        val style = if (dark) SystemBarStyle.dark(AndroidColor.TRANSPARENT)
        else SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
        activity.enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
        onDispose { }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
