package ar.fausto.weil

import androidx.compose.runtime.remember
import androidx.compose.ui.window.ComposeUIViewController

fun MainViewController() = ComposeUIViewController {
    val graph = remember {
        AppGraph(
            store = requireNotNull(IosBridges.secureStore) { "SecureStore bridge not installed by Swift host" },
            passkeys = { bridgedPasskeys() },
            qrScanner = { IosBridges.qrScanner },
            documentPicker = { IosBridges.documentPicker },
            // No foreground activity to wait for, unlike Android: opening a
            // URL only needs the shared application.
            wallet = { IosWalletLauncher() },
            dbContext = DbDispatcher,
            dbFactory = { userId, url, token ->
                IosTursoDatabase(path = tursoDatabasePath(userId), url = url, authToken = token)
            },
        )
    }
    RootScreen(graph)
}
