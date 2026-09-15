package ar.fausto.weil

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
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
