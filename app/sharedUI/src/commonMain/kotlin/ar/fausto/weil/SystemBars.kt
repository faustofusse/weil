package ar.fausto.weil

import androidx.compose.runtime.Composable

/**
 * Platform hook that makes the system chrome (Android's status/navigation
 * bars) follow the active palette instead of the system's dark-mode setting.
 * Called once inside [AppRoot]'s `CompositionLocalProvider`, where the theme
 * is readable. Platforms without system chrome do nothing.
 */
@Composable
expect fun SystemBarsEffect(dark: Boolean)
