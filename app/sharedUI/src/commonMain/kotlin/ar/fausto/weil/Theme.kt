package ar.fausto.weil

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import org.jetbrains.compose.resources.Font
import weil.app.sharedui.generated.resources.Res
import weil.app.sharedui.generated.resources.host_grotesk_bold
import weil.app.sharedui.generated.resources.host_grotesk_light
import weil.app.sharedui.generated.resources.host_grotesk_medium
import weil.app.sharedui.generated.resources.host_grotesk_regular
import weil.app.sharedui.generated.resources.host_grotesk_semibold

/** Holds the active theme so a future settings screen can swap it at runtime. */
class AppThemeState(initial: AppTheme = AppTheme.Menta) {
    var theme by mutableStateOf(initial)
}

val LocalAppThemeState = compositionLocalOf<AppThemeState> {
    error("AppThemeState not provided")
}

/** The active theme's [MoneyColors]; provided by [FinanceTheme], read via [MoneyColor]. */
val LocalMoneyColors = compositionLocalOf { MoneyColors() }

/**
 * The only place a screen gets a color for a signed amount. Every list row,
 * balance and hero figure goes through these three, so "is this green?" has
 * one answer app-wide instead of one per screen.
 */
object MoneyColor {
    /** Money in. */
    val positive: Color
        @Composable get() = LocalMoneyColors.current.positive

    /** Money out. */
    val negative: Color
        @Composable get() = LocalMoneyColors.current.negative

    /**
     * Neither: a transfer between the user's own accounts, or a figure whose
     * sign is bookkeeping convention. Text color, not a third hue — painting
     * these would make the palette say something that didn't happen.
     */
    val neutral: Color
        @Composable get() = MaterialTheme.colorScheme.onSurface
}

/**
 * Host Grotesk, the app's only typeface (static instances, not the variable
 * font: Compose resources hands the file to each platform's loader, and the
 * `wght` axis is not selectable through [FontWeight] on all of them — five
 * named weights are the same bytes with none of the ambiguity).
 *
 * Bundled weights stop at Bold: the display styles ask for the heaviest thing
 * available and would otherwise get a synthesized fake-bold, which on a
 * grotesk smears exactly the letterforms that make it one.
 */
@Composable
private fun hostGrotesk(): FontFamily = FontFamily(
    Font(Res.font.host_grotesk_light, FontWeight.Light),
    Font(Res.font.host_grotesk_regular, FontWeight.Normal),
    Font(Res.font.host_grotesk_medium, FontWeight.Medium),
    Font(Res.font.host_grotesk_semibold, FontWeight.SemiBold),
    Font(Res.font.host_grotesk_bold, FontWeight.Bold),
)

/**
 * The Material 3 type scale, re-pointed at [hostGrotesk] wholesale: every
 * style keeps its platform-tuned size/line-height/tracking and only swaps
 * family, so nothing has to be re-measured. The big money figures get a
 * tighter tracking — at display sizes the default letter spacing pushes
 * "1.137.155,00" wide enough to wrap on a narrow phone.
 */
private fun financeTypography(family: FontFamily): Typography {
    val base = Typography()
    fun TextStyle.on(weight: FontWeight? = null, tracking: Boolean = false): TextStyle = copy(
        fontFamily = family,
        fontWeight = weight ?: fontWeight,
        letterSpacing = if (tracking) (-0.5).sp else letterSpacing,
    )
    return Typography(
        displayLarge = base.displayLarge.on(FontWeight.Bold, tracking = true),
        displayMedium = base.displayMedium.on(FontWeight.Bold, tracking = true),
        displaySmall = base.displaySmall.on(FontWeight.Bold, tracking = true),
        headlineLarge = base.headlineLarge.on(FontWeight.SemiBold, tracking = true),
        headlineMedium = base.headlineMedium.on(FontWeight.Bold, tracking = true),
        headlineSmall = base.headlineSmall.on(FontWeight.SemiBold),
        titleLarge = base.titleLarge.on(FontWeight.SemiBold),
        titleMedium = base.titleMedium.on(FontWeight.SemiBold),
        titleSmall = base.titleSmall.on(FontWeight.Medium),
        bodyLarge = base.bodyLarge.on(),
        bodyMedium = base.bodyMedium.on(),
        bodySmall = base.bodySmall.on(),
        labelLarge = base.labelLarge.on(FontWeight.Medium),
        labelMedium = base.labelMedium.on(FontWeight.Medium),
        labelSmall = base.labelSmall.on(FontWeight.Medium),
    )
}

@Composable
fun FinanceTheme(theme: AppTheme, content: @Composable () -> Unit) {
    val family = hostGrotesk()
    // Rebuilt only when the family instance changes (once, on first
    // composition): the scale is 15 TextStyle copies and sits under every
    // recomposition of every screen.
    val typography = remember(family) { financeTypography(family) }
    CompositionLocalProvider(LocalMoneyColors provides theme.money) {
        MaterialTheme(
            colorScheme = theme.colorScheme,
            typography = typography,
            content = content,
        )
    }
}
