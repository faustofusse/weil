package ar.fausto.weil

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import java.awt.EventQueue
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.swing.Swing
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

/**
 * Headless UI check: renders the app into a PNG with no window, no dock icon
 * and no focus stealing, so a screenshot run can't interrupt whatever else is
 * on screen. Same composables as the real app — this is [ImageComposeScene],
 * not a preview harness.
 *
 *   ./gradlew :app:desktopApp:shot
 *   ./gradlew :app:desktopApp:shot -Pshot.out=/tmp/home.png -Pshot.seconds=6
 *   ./gradlew :app:desktopApp:shot -Pshot.route=import   (AI import review)
 *   ./gradlew :app:desktopApp:shot -Pshot.route=inbox    (movements detected in
 *     the seeded notifications/emails)
 *   ./gradlew :app:desktopApp:shot -Pshot.route=account  (register of the seeded
 *       bank account, which holds both ARS and USD postings)
 *
 * Session and database are sandboxed under the temp dir: the harness must
 * never touch `~/.weil` (a real desktop session lives there) and always starts
 * from the seeded demo ledger.
 */
private const val WIDTH_DP = 420
private const val HEIGHT_DP = 900
private const val DENSITY = 2f

fun main(args: Array<String>) {
    System.setProperty("java.awt.headless", "true")
    val out = File(args.getOrNull(0) ?: "build/shots/home.png")
    val seconds = args.getOrNull(1)?.toDoubleOrNull() ?: 5.0
    val route = args.getOrNull(2).orEmpty()
    out.parentFile?.mkdirs()

    val sandbox = File(System.getProperty("java.io.tmpdir"), "weil-shot").apply { mkdirs() }
    val dbFile = File(sandbox, "shot.db").apply { delete() }
    val storeFile = File(sandbox, "store.properties").apply { delete() }
    val graph = AppGraph(
        store = JvmSecureStore(storeFile, seedDevSession = true),
        passkeys = { JvmDevPasskeys() },
        qrScanner = { null },
        importAnalyzer = FakeImportAnalyzer(),
        dbContext = jvmDbDispatcher,
        dbFactory = { _, _, _ -> FakeDatabase(dbFile) },
    )

    // Same path a real Android share takes: drop a document in the inbox and
    // RootScreen navigates to the review screen once it subscribes.
    if (route == "import") {
        SharedImportInbox.offer(
            PickedDocument(bytes = ByteArray(1), mimeType = "application/pdf", name = "resumen.pdf"),
        )
    }

    // Everything Compose and every app coroutine (Dispatchers.Main = Swing)
    // runs on the EDT, so composition stays single-threaded; only the DB runs
    // elsewhere (jvmDbDispatcher). Sleeping *off* the EDT between frames is
    // what gives those async loads room to land before the last frame is kept.
    lateinit var scene: ImageComposeScene
    EventQueue.invokeAndWait {
        scene = ImageComposeScene(
            width = (WIDTH_DP * DENSITY).toInt(),
            height = (HEIGHT_DP * DENSITY).toInt(),
            density = Density(DENSITY),
            coroutineContext = Dispatchers.Swing,
        ) {
            RootScreen(
                graph,
                initialRoute = when (route) {
                    "quick" -> TransactionQuickRoute(TxnKind.Expense)
                    "account-add" -> AccountAddRoute(AccountType.Asset)
                    "journal" -> JournalRoute
                    "tx-new" -> TransactionNewRoute()
                    "account" -> AccountDetailRoute("seed-asset-bank")
                    "inbox" -> InboxReviewRoute
                    "tx" -> TransactionDetailRoute("seed-tx-2")
                    else -> null
                },
            )
        }
    }

    val start = System.nanoTime()
    var image: Image? = null
    while (System.nanoTime() - start < (seconds * 1_000_000_000L).toLong()) {
        EventQueue.invokeAndWait { image = scene.render(System.nanoTime() - start) }
        Thread.sleep(16)
    }
    val data = image?.encodeToData(EncodedImageFormat.PNG) ?: error("nothing rendered")
    out.writeBytes(data.bytes)
    EventQueue.invokeAndWait { scene.close() }
    println("shot: ${out.absolutePath}")
    kotlin.system.exitProcess(0)
}
