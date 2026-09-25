package ar.fausto.weil

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The real IolClient against the real api.invertironline.com: login form,
 * bearer token, and every read the sync does, decoded from the wire.
 * Opt-in — it needs an account — and harmless: IolClient can only read, and
 * the ledger it plans into is a throwaway SQLite file, never a user's.
 *
 *   IOL_USERNAME=… IOL_PASSWORD=… ./gradlew :app:desktopApp:test --tests '*IolLiveTest*'
 */
class IolLiveTest {

    @Test
    fun liveSyncLandsOnIolsSnapshot() {
        val username = System.getenv("IOL_USERNAME") ?: return println("IOL_USERNAME not set: skipped")
        val password = System.getenv("IOL_PASSWORD") ?: return println("IOL_PASSWORD not set: skipped")
        val sandbox = Files.createTempDirectory("weil-iol-live").toFile()
        try {
            runBlocking {
                val store = JvmSecureStore(File(sandbox, "store.properties"), seedDevSession = true)
                val graph = AppGraph(
                    store = store,
                    passkeys = { JvmDevPasskeys() },
                    qrScanner = { null },
                    dbContext = jvmDbDispatcher,
                    dbFactory = { _, _, _ -> FakeDatabase(File(sandbox, "ledger.db")) },
                )
                graph.iol.connect(username, password)
                val plan = graph.iol.preview()
                println("live IOL: ${plan.transactions.size} transactions, ${plan.issues.size} issues, ${plan.differences.size} differences")
                plan.issues.forEach { println("  issue: $it") }
                plan.differences.forEach { println("  difference: $it") }
                assertEquals(emptyList(), plan.differences)
                graph.iol.apply(plan)
                val again = graph.iol.preview()
                assertEquals(emptyList(), again.transactions)
                assertEquals(emptyList(), again.differences)
            }
        } finally {
            sandbox.deleteRecursively()
        }
    }
}
