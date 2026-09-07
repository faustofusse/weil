package ar.fausto.weil

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color

/**
 * A complete, swappable look for the app. Adding a theme is one new entry here —
 * no screen changes — and each entry is a full ColorScheme (dark or light).
 * The UI reads colors only via MaterialTheme.colorScheme; nothing else holds colors.
 */
enum class AppTheme(val displayName: String, val colorScheme: ColorScheme) {
    Weil(
        displayName = "Weil",
        colorScheme = darkColorScheme(
            primary = Color(0xFFD0BCFF),
            onPrimary = Color(0xFF311B92),
            secondary = Color(0xFFCCC2DC),
            tertiary = Color(0xFFEFB8C8),
            background = Color(0xFF141218),
            surface = Color(0xFF141218),
        ),
    ),
}
