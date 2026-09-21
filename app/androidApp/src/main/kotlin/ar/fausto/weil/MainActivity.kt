package ar.fausto.weil

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import android.graphics.Color as AndroidColor

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Edge-to-edge with transparent bars. The icon tint is set from
        // composition by `SystemBarsEffect` in sharedUI (inside AppRoot,
        // where the active palette is readable): the default edge-to-edge
        // call picks the tint from the system's dark-mode setting, not from
        // ours, and the palette is a user preference independent of it.
        // `AppTheme.dark` is the flag that decides.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        val graph = (application as WeilApplication).graph
        setContent {
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
