package ar.fausto.weil

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * A complete, swappable look for the app. Adding a theme is one new entry here —
 * no screen changes — and each entry is a full ColorScheme (dark or light).
 * The UI reads colors only via MaterialTheme.colorScheme; nothing else holds colors.
 */
enum class AppTheme(val displayName: String, val colorScheme: ColorScheme) {
    /**
     * The dashboard palette: a pale lavender page, one mint hero card and
     * slate tiles. Three roles carry the whole look, which is why the screens
     * can stay color-free: `primaryContainer` is the hero, `inverseSurface`
     * the account tiles, `background` the page.
     */
    Menta(
        displayName = "Menta",
        // Five colors carry the whole app: F3F3F8 page, C0D9D7 mint hero,
        // 5F6F7B mid slate, 394751 dark slate, 363636 ink. Everything else
        // below is one of those five, an alpha of one, or a neutral derived
        // from the page — no sixth hue gets introduced by accident.
        colorScheme = lightColorScheme(
            // Ink: the FAB outline, links, the selected-tab mark.
            primary = Color(0xFF363636),
            onPrimary = Color(0xFFF3F3F8),
            // The mint of the balance hero; ink on it clears contrast at
            // display size without needing a darker mint.
            primaryContainer = Color(0xFFC0D9D7),
            onPrimaryContainer = Color(0xFF363636),
            // Mid slate: the second account tile, and any tonal control that
            // must read as "filled" without going as dark as a tile.
            secondary = Color(0xFF5F6F7B),
            onSecondary = Color(0xFFF3F3F8),
            secondaryContainer = Color(0xFFC0D9D7),
            onSecondaryContainer = Color(0xFF394751),
            // Money arriving in an asset account. The palette has no green,
            // and direction of money is the one thing color must say, so
            // this is the single deliberate addition — desaturated to sit
            // beside the slates instead of shouting over them.
            tertiary = Color(0xFF2F6B4F),
            onTertiary = Color(0xFFF3F3F8),
            tertiaryContainer = Color(0xFFC6E2D2),
            onTertiaryContainer = Color(0xFF14301F),
            // Same reasoning as tertiary, in the other direction.
            error = Color(0xFF9E3B32),
            onError = Color(0xFFF3F3F8),
            errorContainer = Color(0xFFF3DCD9),
            onErrorContainer = Color(0xFF3B100C),
            background = Color(0xFFF3F3F8),
            onBackground = Color(0xFF363636),
            surface = Color(0xFFF3F3F8),
            onSurface = Color(0xFF363636),
            surfaceVariant = Color(0xFFE4E5EC),
            // Secondary text (routes, captions): the mid slate, which is
            // exactly what it's for.
            onSurfaceVariant = Color(0xFF5F6F7B),
            // Not literal white: F3F3F8 is the app's one "page" color, and
            // this role is a surface *on* the page, not a QR-style backing
            // that needs true white contrast.
            surfaceContainerLowest = Color(0xFFF3F3F8),
            surfaceContainerLow = Color(0xFFFAFAFD),
            surfaceContainer = Color(0xFFEDEDF3),
            surfaceContainerHigh = Color(0xFFE7E7EF),
            surfaceContainerHighest = Color(0xFFE1E1EA),
            outline = Color(0xFF5F6F7B),
            outlineVariant = Color(0xFFCBCDD6),
            // The account tiles: dark slate on the pale page, page-colored text.
            inverseSurface = Color(0xFF394751),
            inverseOnSurface = Color(0xFFF3F3F8),
            inversePrimary = Color(0xFFC0D9D7),
            surfaceTint = Color(0xFF5F6F7B),
            scrim = Color(0xFF363636),
        ),
    ),
    Weil(
        displayName = "Weil",
        // `error` was left to the darkColorScheme() default, which is
        // 0xFFF2B8B5 — pixel-identical to this theme's own `primary`. Every
        // negative amount and every error banner rendered in the exact same
        // coral as the FAB and links, so "this is a debit" and "this is
        // tappable" were the same color. Every role below is now explicit so
        // the next theme entry doesn't inherit that collision by accident.
        colorScheme = darkColorScheme(
            primary = Color(0xFFF2B8B5),
            onPrimary = Color(0xFF601410),
            primaryContainer = Color(0xFF7D2E29),
            onPrimaryContainer = Color(0xFFFFDAD4),
            secondary = Color(0xFFCCC2DC),
            onSecondary = Color(0xFF332D41),
            secondaryContainer = Color(0xFF4A4458),
            onSecondaryContainer = Color(0xFFE8DEF8),
            // Repurposed as the app's only green: `tertiary` sat unused (no
            // component here pulls it as a default), which made it the one
            // role free to carry a hue the rest of the warm pink/coral/purple
            // palette doesn't have — money arriving in an asset account reads
            // green against that backdrop instead of borrowing red's family.
            tertiary = Color(0xFF8FD6A2),
            onTertiary = Color(0xFF0F3D1E),
            tertiaryContainer = Color(0xFF2E5C3C),
            onTertiaryContainer = Color(0xFFC2EACB),
            // A saturated, darker coral-red: same warm family as primary but
            // unmistakably not it — primary is a pale brand pink, error reads
            // as an alarm next to it.
            error = Color(0xFFFF6F61),
            onError = Color(0xFF3A0805),
            errorContainer = Color(0xFF5C1A14),
            onErrorContainer = Color(0xFFFFDAD4),
            background = Color(0xFF141218),
            onBackground = Color(0xFFE6E0E9),
            surface = Color(0xFF141218),
            onSurface = Color(0xFFE6E0E9),
            surfaceVariant = Color(0xFF49454E),
            onSurfaceVariant = Color(0xFFCAC4CF),
            surfaceContainerLowest = Color(0xFF0F0D13),
            surfaceContainerLow = Color(0xFF1D1B20),
            surfaceContainer = Color(0xFF211F26),
            surfaceContainerHigh = Color(0xFF2B2930),
            surfaceContainerHighest = Color(0xFF36343B),
            outline = Color(0xFF938F99),
            outlineVariant = Color(0xFF49454F),
            inverseSurface = Color(0xFFE6E0E9),
            inverseOnSurface = Color(0xFF322F35),
            inversePrimary = Color(0xFF7D2E29),
            surfaceTint = Color(0xFFF2B8B5),
            scrim = Color(0xFF000000),
        ),
    ),
}
