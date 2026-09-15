package ar.fausto.weil

import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

/**
 * Desktop entry point. Two modes:
 *
 * - default: **real auth**. Passkey ceremonies run in the system browser via
 *   [BrowserPasskeys]; every API call is made by the app itself, so the
 *   refresh cookie lands here and `session/refresh` + the sync-chain
 *   endpoints work. Data is live Turso over HTTP ([HttpDatabase]).
 * - `-Dweil.mode=fake`: offline UI workbench \u2014 pre-seeded fake session and a
 *   local SQLite [FakeDatabase]. No network, no passkeys.
 *
 * See docs/desktop-target-plan.md.
 */
fun main() {
    val fakeMode = System.getProperty("weil.mode") == "fake"
    val store = JvmSecureStore(seedDevSession = fakeMode)
    val graph = AppGraph(
        store = store,
        passkeys = { if (fakeMode) JvmDevPasskeys() else BrowserPasskeys() },
        // No camera/QR scanner on desktop: ChainState already degrades to
        // "scanner unavailable", and desktop can still show its own invite /
        // pairing-request QR for a phone to scan.
        qrScanner = { null },
        documentPicker = { JvmDocumentPicker() },
        dbContext = jvmDbDispatcher,
        dbFactory = { _, url, token ->
            if (fakeMode) FakeDatabase() else HttpDatabase(url, token)
        },
    )

    application {
        val windowState = rememberWindowState(
            position = WindowPosition(alignment = Alignment.Center),
            size = DpSize(420.dp, 900.dp),
        )
        Window(
            onCloseRequest = ::exitApplication,
            state = windowState,
            title = if (fakeMode) "Weil (offline)" else "Weil",
        ) {
            RootScreen(graph)
        }
    }
}
