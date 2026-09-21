package ar.fausto.weil

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * The direction of money, as one pair of colors for the whole app.
 *
 * It lives outside [ColorScheme] on purpose: `tertiary`/`error` are Material
 * roles that components pull for their own reasons (an error banner, a tonal
 * chip) and they differ per theme, which is how the same debit ended up in
 * four different reds depending on the screen. "Green means money in" is a
 * fact about this app, not a Material role, so it gets its own token —
 * [LocalMoneyColors], read through [MoneyColor].
 *
 * The values are the transaction rows' pair, which is the reference the rest
 * of the screens were unified onto. A theme may override them; both current
 * ones don't, because a movement should read the same after a theme swap.
 */
data class MoneyColors(
    /** Money arriving in an account the user owns. */
    val positive: Color = Color(0xFF55A345),
    /** Money leaving one. */
    val negative: Color = Color(0xFFDB1616),
)

/**
 * A complete, swappable look for the app. Adding a theme is one new entry here —
 * no screen changes — and each entry is a full ColorScheme (dark or light).
 * The UI reads colors only via MaterialTheme.colorScheme (plus [MoneyColor]
 * for signed amounts); nothing else holds colors.
 */
enum class AppTheme(
    val displayName: String,
    val colorScheme: ColorScheme,
    val money: MoneyColors = MoneyColors(),
    /**
     * Whether this palette paints a dark page. The system bars read it: the
     * status/navigation icon tint is not a Material role, so the only
     * trustworthy source is the theme itself (the system's dark-mode setting
     * says nothing about which palette the user picked in-app).
     */
    val dark: Boolean = false,
) {
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

    /**
     * The pink sibling of [Menta], built the same way: one pale page, one
     * pastel hero, one deep plum for the tiles, and the same green/red pair
     * for money — which is the reason the pink stops at the hero and the
     * chips. A saturated pink as `primary` would sit in the same family as
     * the debit red, and "this is tappable" would start reading like "this
     * went out"; the accent is therefore the plum ink, and the pink is a
     * surface color.
     */
    Rosa(
        displayName = "Rosa",
        colorScheme = lightColorScheme(
            // Plum ink: the FAB, links, the selected-tab mark.
            primary = Color(0xFF4A2B33),
            onPrimary = Color(0xFFFDF4F6),
            // The balance hero: the pastel pink the palette is named after.
            primaryContainer = Color(0xFFF2C6D2),
            onPrimaryContainer = Color(0xFF4A2B33),
            // Mid mauve, Menta's mid slate rotated onto this hue.
            secondary = Color(0xFF8A6570),
            onSecondary = Color(0xFFFDF4F6),
            secondaryContainer = Color(0xFFF2C6D2),
            onSecondaryContainer = Color(0xFF5B3A44),
            // Identical to Menta's: direction of money must not change
            // meaning when the page does.
            tertiary = Color(0xFF2F6B4F),
            onTertiary = Color(0xFFFDF4F6),
            tertiaryContainer = Color(0xFFC6E2D2),
            onTertiaryContainer = Color(0xFF14301F),
            error = Color(0xFF9E3B32),
            onError = Color(0xFFFDF4F6),
            errorContainer = Color(0xFFF3DCD9),
            onErrorContainer = Color(0xFF3B100C),
            background = Color(0xFFFDF4F6),
            onBackground = Color(0xFF4A2B33),
            surface = Color(0xFFFDF4F6),
            onSurface = Color(0xFF4A2B33),
            surfaceVariant = Color(0xFFEFE2E6),
            onSurfaceVariant = Color(0xFF8A6570),
            surfaceContainerLowest = Color(0xFFFDF4F6),
            surfaceContainerLow = Color(0xFFFFFAFB),
            surfaceContainer = Color(0xFFF8EBEF),
            surfaceContainerHigh = Color(0xFFF3E4E9),
            surfaceContainerHighest = Color(0xFFEDDDE3),
            outline = Color(0xFF8A6570),
            outlineVariant = Color(0xFFE0CBD2),
            // The account tiles: deep plum on the pale page.
            inverseSurface = Color(0xFF5B3A44),
            inverseOnSurface = Color(0xFFFDF4F6),
            inversePrimary = Color(0xFFF2C6D2),
            surfaceTint = Color(0xFF8A6570),
            scrim = Color(0xFF4A2B33),
        ),
    ),

    /**
     * [Menta] after dark: the same five colors, re-assigned rather than
     * re-picked. The page becomes the dark slate the account tiles used to
     * be, the mint stops being a large filled hero (at that size on a dark
     * page it glares) and becomes the accent that carries text and outlines,
     * and the tiles flip to `inverseSurface` = mint with dark ink on it — so
     * the pale/dark contrast of the light theme survives the inversion
     * instead of every surface collapsing into one grey.
     */
    Noche(
        displayName = "Noche",
        colorScheme = darkColorScheme(
            // Mint: on a dark page it is the legible accent, which is the
            // role the near-black ink plays in Menta.
            primary = Color(0xFFC0D9D7),
            onPrimary = Color(0xFF1B2226),
            // The balance hero: the mint taken down to where a display-size
            // figure can sit on it without the card becoming the brightest
            // thing on screen.
            primaryContainer = Color(0xFF33504E),
            onPrimaryContainer = Color(0xFFD9EDEB),
            // Mid slate, lightened just enough to stay readable on the page.
            secondary = Color(0xFF9FB3BF),
            onSecondary = Color(0xFF1B2226),
            secondaryContainer = Color(0xFF394751),
            onSecondaryContainer = Color(0xFFD5E2E4),
            // Same single deliberate green as Menta, one step lighter: the
            // light theme's 2F6B4F is below the page here, not above it.
            tertiary = Color(0xFF7FC79E),
            onTertiary = Color(0xFF0C2C1A),
            tertiaryContainer = Color(0xFF2F5C43),
            onTertiaryContainer = Color(0xFFC6E2D2),
            error = Color(0xFFE98C82),
            onError = Color(0xFF3B100C),
            errorContainer = Color(0xFF6B241D),
            onErrorContainer = Color(0xFFF3DCD9),
            background = Color(0xFF1B2226),
            onBackground = Color(0xFFE6EBEC),
            surface = Color(0xFF1B2226),
            onSurface = Color(0xFFE6EBEC),
            surfaceVariant = Color(0xFF2A3339),
            // Secondary text (routes, captions): the mid slate again, the
            // way Menta uses 5F6F7B.
            onSurfaceVariant = Color(0xFFA9BBC4),
            surfaceContainerLowest = Color(0xFF11171A),
            surfaceContainerLow = Color(0xFF1F272B),
            surfaceContainer = Color(0xFF232C31),
            surfaceContainerHigh = Color(0xFF2A353B),
            surfaceContainerHighest = Color(0xFF334046),
            outline = Color(0xFF7D8F9A),
            outlineVariant = Color(0xFF3A464D),
            // The account tiles, inverted: mint plate, dark ink.
            inverseSurface = Color(0xFFC0D9D7),
            inverseOnSurface = Color(0xFF1B2226),
            inversePrimary = Color(0xFF394751),
            surfaceTint = Color(0xFFC0D9D7),
            scrim = Color(0xFF000000),
        ),
        // The one place this theme does not keep Menta's values: the shared
        // pair is tuned for a pale page and both are too dark to read on
        // 1B2226. Same two hues, lifted — a debit must stay the debit red.
        money = MoneyColors(
            positive = Color(0xFF74C562),
            negative = Color(0xFFFF6B5E),
        ),
        dark = true,
    ),
    Weil(
        displayName = "Weil",
        dark = true,
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
    ;

    companion object {
        /**
         * The stored form of a theme is its enum name, not its ordinal: the
         * preference is synced to every paired device, and an ordinal would
         * silently re-point at a different palette the moment an entry is
         * inserted above it. An unknown name (a theme this build doesn't
         * have yet) resolves to null and the caller keeps its default.
         */
        fun byId(id: String?): AppTheme? = entries.firstOrNull { it.name == id }
    }
}

/** One Material role as it is inspected: its name and the color it holds. */
data class ThemeRole(val name: String, val color: Color)

/**
 * Every role this app assigns, in the order a palette is authored rather
 * than alphabetically: the page first, then what sits on it, then the
 * containers, then the inverses. [ColorScheme] has no way to enumerate
 * itself, so the list is written out — a role missing here is a role nobody
 * can inspect, which is the failure mode to watch when a new one starts
 * being used.
 */
fun ColorScheme.roles(): List<ThemeRole> = listOf(
    ThemeRole("background", background),
    ThemeRole("onBackground", onBackground),
    ThemeRole("surface", surface),
    ThemeRole("onSurface", onSurface),
    ThemeRole("surfaceVariant", surfaceVariant),
    ThemeRole("onSurfaceVariant", onSurfaceVariant),
    ThemeRole("primary", primary),
    ThemeRole("onPrimary", onPrimary),
    ThemeRole("primaryContainer", primaryContainer),
    ThemeRole("onPrimaryContainer", onPrimaryContainer),
    ThemeRole("secondary", secondary),
    ThemeRole("onSecondary", onSecondary),
    ThemeRole("secondaryContainer", secondaryContainer),
    ThemeRole("onSecondaryContainer", onSecondaryContainer),
    ThemeRole("tertiary", tertiary),
    ThemeRole("onTertiary", onTertiary),
    ThemeRole("tertiaryContainer", tertiaryContainer),
    ThemeRole("onTertiaryContainer", onTertiaryContainer),
    ThemeRole("error", error),
    ThemeRole("onError", onError),
    ThemeRole("errorContainer", errorContainer),
    ThemeRole("onErrorContainer", onErrorContainer),
    ThemeRole("surfaceContainerLowest", surfaceContainerLowest),
    ThemeRole("surfaceContainerLow", surfaceContainerLow),
    ThemeRole("surfaceContainer", surfaceContainer),
    ThemeRole("surfaceContainerHigh", surfaceContainerHigh),
    ThemeRole("surfaceContainerHighest", surfaceContainerHighest),
    ThemeRole("outline", outline),
    ThemeRole("outlineVariant", outlineVariant),
    ThemeRole("inverseSurface", inverseSurface),
    ThemeRole("inverseOnSurface", inverseOnSurface),
    ThemeRole("inversePrimary", inversePrimary),
    ThemeRole("surfaceTint", surfaceTint),
    ThemeRole("scrim", scrim),
)

/** The two money colors, listed beside the Material roles they sit outside of. */
fun MoneyColors.roles(): List<ThemeRole> = listOf(
    ThemeRole("money.positive", positive),
    ThemeRole("money.negative", negative),
)

/**
 * `#RRGGBB`, or `#AARRGGBB` when the color isn't opaque — the point of
 * showing the code is that it can be copied back into a `Color(0x…)`
 * literal, and an alpha dropped on the way would be a different color.
 * Computed from the float channels rather than `toArgb()`, which keeps this
 * usable from any target without a graphics-layer conversion.
 */
fun Color.hex(): String {
    fun channel(v: Float): String {
        val i = (v * 255f + 0.5f).toInt().coerceIn(0, 255)
        return i.toString(16).padStart(2, '0').uppercase()
    }
    val rgb = "#${channel(red)}${channel(green)}${channel(blue)}"
    return if (alpha >= 1f) rgb else "#${channel(alpha)}${rgb.drop(1)}"
}
