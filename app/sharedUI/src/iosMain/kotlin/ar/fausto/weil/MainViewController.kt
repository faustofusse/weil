package ar.fausto.weil

import androidx.compose.runtime.remember
import androidx.compose.ui.window.ComposeUIViewController

fun MainViewController() = ComposeUIViewController {
    val graph = remember {
        AppGraph(
            store = requireNotNull(IosBridges.secureStore) { "SecureStore bridge not installed by Swift host" },
            passkeys = bridgedPasskeys(),
            dbContext = DbDispatcher,
            dbFactory = { userId, url, token ->
                IOSDatabase(path = defaultDatabasePath(userId), url = url, authToken = token)
            },
        )
    }
    RootScreen(graph)
}
