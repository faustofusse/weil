package ar.fausto.weil

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class InstrumentDetailTest {

    private fun d(text: String) = Decimal.parse(text)!!

    private val valuation = Valuation(
        commodities = listOf(
            InstrumentInfo("BCBA:MELI", "MELI", kind = "cedear", scale = 0, quoteCommodity = "ARS"),
            InstrumentInfo("BCBA:AAPL", "AAPL", kind = "cedear", scale = 0, quoteCommodity = "ARS"),
            InstrumentInfo("BCBA:AAPLD", "AAPLD", kind = "cedear", scale = 0, quoteCommodity = "USD"),
        ).associateBy { it.id },
        prices = listOf(
            PriceQuote("BCBA:MELI", "ARS", 0, d("24000"), "iol"),
            PriceQuote("BCBA:AAPL", "ARS", 0, d("15000"), "iol"),
            PriceQuote("BCBA:AAPLD", "USD", 0, d("10"), "iol"),
        ).associateBy { it.commodity },
    )

    @Test
    fun oneSecurityAcrossBrokersWithEachBrokersShare() {
        val view = valuation.instrumentView(
            "BCBA:MELI",
            mapOf(
                "iol-cartera" to mapOf("BCBA:MELI" to HeldPosition(13L, 30_000_000L, "ARS"), "ARS" to HeldPosition(5L, 0L, null)),
                "galicia-cartera" to mapOf("BCBA:MELI" to HeldPosition(2L, 5_000_000L, "ARS")),
                "ibkr-cartera" to mapOf("NASDAQ:TTWO" to HeldPosition(4939L, 10_098L, "USD")),
            ),
            InvestmentsDisplay.Original,
            emptyList(),
        )
        assertEquals("BCBA:MELI", view.security)
        // IBKR never held it; IOL holds more, so it comes first.
        assertEquals(listOf("iol-cartera", "galicia-cartera"), view.perAccount.map { it.first })
        val total = view.total.single()
        assertEquals(15L, total.quantityMinor)
        assertEquals(36_000_000L, total.valueMinor)
        assertEquals(36_000_000L - 35_000_000L, total.unrealizedMinor)
        assertEquals(31_200_000L, view.perAccount.first().second.single().valueMinor)
    }

    @Test
    fun aDollarLineBelongsToItsSecurity() {
        val view = valuation.instrumentView(
            "BCBA:AAPLD",
            mapOf("iol" to mapOf("BCBA:AAPL" to HeldPosition(3L, 0L, null), "BCBA:AAPLD" to HeldPosition(8L, 0L, null))),
            InvestmentsDisplay.Original,
            emptyList(),
        )
        assertEquals("BCBA:AAPL", view.security)
        assertEquals(setOf("BCBA:AAPL", "BCBA:AAPLD"), view.lines)
        // In «Original» each line keeps its row and currency.
        assertEquals(setOf("BCBA:AAPL", "BCBA:AAPLD"), view.total.map { it.commodity }.toSet())
    }

    @Test
    fun theChartHasOnePointPerDayInOneCurrency() {
        val day = 24L * 60 * 60 * 1000
        val t = iolTime("2026-09-21T12:00:00")!!
        val quotes = listOf(
            PriceQuote("BCBA:MELI", "ARS", t, d("100"), "iol"),
            PriceQuote("BCBA:MELI", "ARS", t + 3_600_000, d("110"), "iol"),
            PriceQuote("BCBA:MELI", "USD", t + 3_600_000, d("0.07"), "iol"),
            PriceQuote("BCBA:MELI", "ARS", t + day, d("121"), "iol"),
        )
        val series = dailySeries(quotes, "ARS")
        assertEquals(listOf(d("110"), d("121")), series.map { it.price })
        assertEquals(0.1, seriesChange(series)!!, 1e-9)
        assertNull(seriesChange(series.take(1)))
        assertEquals("ARS", chartCurrency(valuation.commodities["BCBA:MELI"], quotes))
        assertEquals("USD", chartCurrency(null, quotes.filter { it.quoteCommodity == "USD" }))
    }
}
