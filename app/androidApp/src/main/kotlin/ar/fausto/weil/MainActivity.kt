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
        // Transparent bars with **dark** icons: the app's palette is the pale
        // Menta one, and the default edge-to-edge call picks the icon tint
        // from the system's dark-mode setting, not from ours — on a phone in
        // dark mode that painted white clock and battery on our near-white
        // page, i.e. an invisible status bar. `detectDarkMode = { false }`
        // says "this app is light, always"; it is the one place that has to
        // change if a dark theme becomes selectable again.
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
