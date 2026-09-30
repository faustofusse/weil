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

    private val mepCalls = mutableListOf<Unit>()

    /** Daily closes from 2026-08-01 to 2026-09-26, 1.500 before September and 1.550 after. */
    private val mepSource = MepHistorySource {
        mepCalls += Unit
        (1..57).map { i ->
            val at = iolTime("2026-08-01T15:00:00")!! + (i - 1) * DAY
            PriceQuote("USD", "ARS", at, Decimal.parse(if (i <= 31) "1500" else "1550")!!, MEP_CLOSE_SOURCE)
        }
    }

    private val graph = AppGraph(
        store = JvmSecureStore(File(sandbox, "store.properties"), seedDevSession = true),
        passkeys = { JvmDevPasskeys() },
        officialRates = source,
        mepHistory = mepSource,
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
        val broken = OfficialRatesRepository(graph.brokers, OfficialRateSource { _, _ -> error("offline") }, MepHistorySource { error("offline") })
        assertFalse(broken.refresh(iolTime("2026-09-25T19:00:00")!!))
    }

    @Test
    fun mepClosesCoverTheBrokersHoldings() = runBlocking {
        val now = iolTime("2026-09-26T12:00:00")!!
        // No broker: nothing to convert, nothing fetched.
        graph.officialRates.refresh(now)
        assertTrue(mepCalls.isEmpty())

        val broker = graph.brokers.connect(IOL_PROVIDER, "IOL", listOf("ARS", "USD"))
        graph.ledger.add(
            date = iolTime("2026-08-20T12:00:00")!!,
            payee = "Compra",
            note = null,
            drafts = listOf(
                DraftPosting(broker.holdings, "1", "BCBA:X", costText = "1000", costCommodity = "ARS"),
                DraftPosting(broker.cash.getValue("ARS"), "-1000", "ARS"),
            ),
        )
        assertTrue(OfficialRatesRepository(graph.brokers, source, mepSource).refresh(now))
        assertEquals(1, mepCalls.size)
        val valuation = graph.brokers.valuation()
        // From a week before the purchase to yesterday; today is the live quote's.
        assertEquals(iolTime("2026-08-13T15:00:00"), valuation.mepHistory.first().at)
        assertEquals(iolTime("2026-09-25T15:00:00"), valuation.mepHistory.last().at)
        assertEquals(Decimal.parse("1500"), valuation.mepAt(iolTime("2026-08-20T12:00:00")!!))
        // Without a live quote, today's MEP is the newest close.
        assertEquals(Decimal.parse("1550"), valuation.mep?.price)

        // Covered and fresh: no second fetch.
        assertFalse(OfficialRatesRepository(graph.brokers, source, mepSource).refresh(now + 2 * 60 * 60 * 1000))
        assertEquals(1, mepCalls.size)
    }

    private companion object {
        const val DAY = 24L * 60 * 60 * 1000
    }
}
