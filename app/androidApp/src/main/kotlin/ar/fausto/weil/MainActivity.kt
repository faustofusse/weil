package ar.fausto.weil

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import android.graphics.Color as AndroidColor

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Edge-to-edge with transparent bars; the icon tint is set from
        // composition below, once the user's palette is known — the default
        // edge-to-edge call picks the tint from the system's dark-mode
        // setting, not from ours, and a palette the user picked in-app is
        // independent of it. `AppTheme.dark` is the flag that decides.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        val graph = (application as WeilApplication).graph
        setContent {
            // The theme loads asynchronously (settings row, once there is a
            // session), so this re-runs on every dark ⇄ light swap.
            val themeState = LocalAppThemeState.current
            DisposableEffect(themeState.theme.dark) {
                val style = if (themeState.theme.dark) SystemBarStyle.dark(AndroidColor.TRANSPARENT)
                else SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose { }
            }
            RootScreen(graph)
        }
        // A receipt/statement shared from another app. The document is parked
        // in SharedImportInbox until RootScreen subscribes (it replays), so
        // this works from a cold start too.
        handleSharedDocument(intent)
    }

    // singleTask: a share while the app is already running arrives here.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSharedDocument(intent)
    }
}
