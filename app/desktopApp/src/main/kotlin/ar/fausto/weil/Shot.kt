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
 *   ./gradlew :app:desktopApp:shot -Pshot.route=document (a stored import
 *                                                        original)
 *   ./gradlew :app:desktopApp:shot -Pshot.route=inbox    (movements detected in
 *     the seeded notifications/emails)
 *   ./gradlew :app:desktopApp:shot -Pshot.route=profile  (profile, incl. the
 *     WhatsApp linking card)
 *   ./gradlew :app:desktopApp:shot -Pshot.route=account  (register of the seeded
 *       bank account, which holds both ARS and USD postings)
 *   ./gradlew :app:desktopApp:shot -Pshot.route=tree     (full account tree)
 *   ./gradlew :app:desktopApp:shot -Pshot.route=category (one category: its
 *                                                       subcategory chips and
 *                                                       its movements)
 *   ./gradlew :app:desktopApp:shot -Pshot.route=categories (expense categories
 *     with their icons)
 *   ./gradlew :app:desktopApp:shot -Pshot.route=suggest  (the suggestion's
 *     sources: vector neighbours + the run button)
 *   ./gradlew :app:desktopApp:shot -Pshot.route=suggest-trace (the pipeline
 *     trace for that same notification)
 *   ./gradlew :app:desktopApp:shot -Pshot.route=notification (captured
 *     notification + its related transactions)
 *   ./gradlew :app:desktopApp:shot -Pshot.route=notifications (capture list)
 *   ./gradlew :app:desktopApp:shot -Pshot.route=email    (email detail)
 *   ./gradlew :app:desktopApp:shot -Pshot.route=emails   (mail list)
 *   ./gradlew :app:desktopApp:shot -Pshot.route=new      (the create panel,
 *     risen over Home)
 *   ./gradlew :app:desktopApp:shot -Pshot.theme=Noche   (any route in another
 *     palette: the real app reads it from synced settings, which the sandbox
 *     never wrote)
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
    // The palette normally comes from the user's synced settings, which the
    // sandboxed session never reads; naming one here is how a theme gets
    // checked on a real screen without touching the default.
    val theme = AppTheme.byId(args.getOrNull(3))
    out.parentFile?.mkdirs()

    val sandbox = File(System.getProperty("java.io.tmpdir"), "weil-shot").apply { mkdirs() }
    val dbFile = File(sandbox, "shot.db").apply { delete() }
    val storeFile = File(sandbox, "store.properties").apply { delete() }
    val graph = AppGraph(
        store = JvmSecureStore(storeFile, seedDevSession = true),
        passkeys = { JvmDevPasskeys() },
        qrScanner = { null },
        importAnalyzer = FakeImportAnalyzer(),
        // A drawn receipt, not a fetch: the sandboxed session has no cookie
        // for the R2 read the real repository does.
        documentFetcher = FakeDocumentFetcher(),
        // Hashed bag-of-words, not a model: no key, no network, and the
        // vector functions it feeds are the ones FakeDatabase registers.
        embedder = FakeEmbedder(),
        // Substring matching, not a model: the sandboxed session cannot call
        // the worker that holds the TypeSafe key.
        categorySuggester = FakeCategorySuggester(),
        dbContext = jvmDbDispatcher,
        dbFactory = { _, _, _ -> FakeDatabase(dbFile) },
    )
    // The bench's two model calls are canned; its retrieval half is real and
    // runs against the seeded database, so it needs the graph's repositories.
    val benched = AppGraph(
        store = JvmSecureStore(storeFile, seedDevSession = true),
        passkeys = { JvmDevPasskeys() },
        qrScanner = { null },
        importAnalyzer = FakeImportAnalyzer(),
        documentFetcher = FakeDocumentFetcher(),
        embedder = FakeEmbedder(),
        categorySuggester = FakeCategorySuggester(),
        suggester = FakeSuggestTracer(graph.notifications, graph.ledger, graph.embeddings),
        dbContext = jvmDbDispatcher,
        dbFactory = { _, _, _ -> FakeDatabase(dbFile) },
    )

    // The similarity sections render nothing until vectors exist, and the
    // harness has no one to tap "revectorizar": sweep up front.
    kotlinx.coroutines.runBlocking { runCatching { graph.embeddings.embedPending() } }

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
                if (route == "suggest") benched else graph,
                // Not a route: the create panel is an overlay over whatever
                // root is showing.
                openCreate = route == "new",
                // The four tabs render inside the shell, so they are asked
                // for by name rather than pushed as routes.
                initialTheme = theme,
                startTab = when (route) {
                    "movements" -> AppTab.Movements
                    "categories" -> AppTab.Categories
                    "profile-tab" -> AppTab.Profile
                    else -> AppTab.Home
                },
                initialRoute = when (route) {
                    "quick" -> TransactionQuickRoute(TxnKind.Expense)
                    "account-add" -> AccountAddRoute(AccountType.Asset)
                    "journal" -> JournalRoute
                    "tx-new" -> TransactionNewRoute()
                    "account" -> AccountDetailRoute("seed-asset-bank")
                    "tree" -> AccountsTreeRoute
                    "category" -> CategoryDetailRoute("seed-expense-food")
                    "tx" -> TransactionDetailRoute("seed-tx-2")
                    "document" -> DocumentRoute("seed-doc-x")
                    "notification" -> NotificationDetailRoute("seed-notif-1")
                    "suggest" -> SuggestSourcesRoute("seed-notif-1")
                    "suggest-trace" -> SuggestDebugRoute("seed-notif-1")
                    // The same bench over the other door.
                    "suggest-email" -> SuggestSourcesRoute("seed-email-1", EventSource.Email)
                    "suggest-email-trace" -> SuggestDebugRoute("seed-email-1", EventSource.Email)
                    "notifications" -> NotificationsRoute
                    "email" -> EmailDetailRoute("seed-email-1")
                    "emails" -> EmailsRoute
                    // Chain and WhatsApp sections call the worker, which the
                    // sandboxed session cannot reach: both render their error
                    // state here, the layout is still what ships.
                    "profile" -> ProfileRoute
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
