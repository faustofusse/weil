package ar.fausto.weil

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Holds the active theme so a future settings screen can swap it at runtime. */
class AppThemeState(initial: AppTheme = AppTheme.Weil) {
    var theme by mutableStateOf(initial)
}

val LocalAppThemeState = compositionLocalOf<AppThemeState> {
    error("AppThemeState not provided")
}

private val FinanceTypography = Typography(
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp,
    ),
)

@Composable
fun FinanceTheme(theme: AppTheme, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = theme.colorScheme,
        typography = FinanceTypography,
        content = content,
    )
}
