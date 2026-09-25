package ar.fausto.weil

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The official dollar through `prices` and back into the valuation. */
class OfficialRatesTest {

    private val sandbox: File = Files.createTempDirectory("weil-bcra-test").toFile()
    private val calls = mutableListOf<Pair<String, String>>()

    /** Answers every business day asked for at a fixed rate. */
    private val source = OfficialRateSource { from, to ->
        calls += from to to
        listOf(PriceQuote("USD", "ARS", iolTime("${to}T15:00:00")!!, Decimal.parse("1525.50")!!, OFFICIAL_SOURCE))
    }

    private val graph = AppGraph(
        store = JvmSecureStore(File(sandbox, "store.properties"), seedDevSession = true),
        passkeys = { JvmDevPasskeys() },
        officialRates = source,
        dbContext = jvmDbDispatcher,
        dbFactory = { _, _, _ -> FakeDatabase(File(sandbox, "ledger.db")) },
    )

    @AfterTest
    fun cleanup() {
        sandbox.deleteRecursively()
    }

    @Test
    fun fetchesOncePerDayAndFeedsTheValuation() = runBlocking {
        val now = iolTime("2026-09-25T19:00:00")!!
        assertTrue(graph.officialRates.refresh(now))
        // A fresh ledger asks for the last ten days.
        assertEquals(listOf("2026-09-15" to "2026-09-25"), calls)

        val valuation = graph.brokers.valuation()
        assertEquals(Decimal.parse("1525.5"), valuation.official?.price)
        assertEquals(110_000L, valuation.convert(mapOf("ARS" to 152_550_000L, "USD" to 10_000L), "USD", now)?.minor)

        // Today's rate is stored: no second call, even past the hourly throttle
        // (a new repository has no memory of the first attempt).
        val again = OfficialRatesRepository(graph.brokers, source)
        assertFalse(again.refresh(now + 2 * 60 * 60 * 1000))
        assertEquals(1, calls.size)

        // Next day: only the missing day is asked for.
        assertTrue(again.refresh(iolTime("2026-09-26T12:00:00")!!))
        assertEquals("2026-09-26" to "2026-09-26", calls.last())
    }

    @Test
    fun aFailingSourceIsSwallowed() = runBlocking {
        val broken = OfficialRatesRepository(graph.brokers) { _, _ -> error("offline") }
        assertFalse(broken.refresh(iolTime("2026-09-25T19:00:00")!!))
    }
}
