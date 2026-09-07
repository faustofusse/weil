package ar.fausto.weil

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.Log
import java.io.ByteArrayOutputStream

/**
 * Package-manager enrichment for recorded notifications: label, system flag
 * and PNG icon captured on the first sighting of a package.
 */
fun lookupAppInfo(context: Context, packageName: String): AppInfo? {
    val pm = context.packageManager
    val appInfo = try {
        pm.getApplicationInfo(packageName, 0)
    } catch (_: PackageManager.NameNotFoundException) {
        return null
    }
    val name = pm.getApplicationLabel(appInfo).toString()
    val isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
    val icon = iconBytes(pm, appInfo)
    return AppInfo(id = packageName, name = name, icon = icon, isSystemApp = isSystemApp)
}

private fun iconBytes(pm: PackageManager, appInfo: ApplicationInfo): ByteArray? =
    try {
        drawableToPng(pm.getApplicationIcon(appInfo))
    } catch (e: Exception) {
        Log.w("AppLookup", "failed to capture icon for ${appInfo.packageName}: ${e.message}")
        null
    }

private fun drawableToPng(drawable: Drawable): ByteArray? {
    val bitmap = drawable.toBitmap() ?: return null
    val stream = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
    return stream.toByteArray()
}

private fun Drawable.toBitmap(): Bitmap? {
    return try {
        if (this is BitmapDrawable) {
            bitmap
        } else {
            val width = if (intrinsicWidth > 0) intrinsicWidth else 96
            val height = if (intrinsicHeight > 0) intrinsicHeight else 96
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
                val canvas = Canvas(bitmap)
                setBounds(0, 0, canvas.width, canvas.height)
                draw(canvas)
            }
        }
    } catch (e: Exception) {
        Log.w("AppLookup", "failed to convert drawable: ${e.message}")
        null
    }
}
