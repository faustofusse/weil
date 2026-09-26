package ar.fausto.weil

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.Test

/**
 * The real Flex Web Service into a throwaway database. Opt-in:
 * `IBKR_TOKEN=… IBKR_QUERY=… ./gradlew :app:desktopApp:test --tests '*IbkrLiveTest*'`.
 * Prints the plan's shape, never amounts.
 */
class IbkrLiveTest {
    @Test
    fun fetchesAndPlansTheRealAccount() = runBlocking {
        val token = System.getenv("IBKR_TOKEN") ?: return@runBlocking
        val query = System.getenv("IBKR_QUERY") ?: return@runBlocking
        val sandbox = Files.createTempDirectory("weil-ibkr-live").toFile()
        try {
            val store = JvmSecureStore(File(sandbox, "store.properties"), seedDevSession = true)
            val graph = AppGraph(
                store = store,
                passkeys = { JvmDevPasskeys() },
                dbContext = jvmDbDispatcher,
                dbFactory = { _, _, _ -> FakeDatabase(File(sandbox, "ledger.db")) },
            )
            val preview = graph.ibkr.connect(token, query)
            println("IBKR ${preview.fromDate}..${preview.toDate}: ${preview.plan.transactions.groupingBy { it.kind }.eachCount()}")
            println("issues: ${preview.plan.issues.map { it.message }}")
            println("differences: ${preview.plan.differences.size}")
        } finally {
            sandbox.deleteRecursively()
        }
    }
}
