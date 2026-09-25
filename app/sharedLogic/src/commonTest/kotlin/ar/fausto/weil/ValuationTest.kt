package ar.fausto.weil

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Balances as money: the real IOL account's positions at IOL's last prices. */
class ValuationTest {

    private fun d(text: String) = Decimal.parse(text)!!

    private val valuation = Valuation(
        commodities = listOf(
            InstrumentInfo("BCBA:MELI", "MELI", kind = "cedear", scale = 0, quoteCommodity = "ARS"),
            InstrumentInfo("BCBA:S13N6", "S13N6", kind = "letra", scale = 0, pricePer = 100, quoteCommodity = "ARS"),
            InstrumentInfo("FCI:IOLCAMA", "IOLCAMA", kind = "fci", scale = 4, quoteCommodity = "ARS"),
            InstrumentInfo("NASDAQ:TTWO", "TTWO", kind = "stock", scale = 4, quoteCommodity = "USD"),
            InstrumentInfo("BCBA:XYZ", "XYZ", kind = "stock", scale = 0, quoteCommodity = "ARS"),
        ).associateBy { it.id },
        prices = listOf(
            PriceQuote("BCBA:MELI", "ARS", 0, d("23420"), "iol"),
            PriceQuote("BCBA:S13N6", "ARS", 0, d("106.251"), "iol"),
            PriceQuote("FCI:IOLCAMA", "ARS", 0, d("12.23127"), "iol"),
            PriceQuote("NASDAQ:TTWO", "USD", 0, d("202.84"), "ibkr"),
        ).associateBy { it.commodity },
    )

    @Test
    fun instrumentsJoinTheirQuoteCurrency() {
        val iol = mapOf(
            "ARS" to 54_257_383L,
            "USD" to 304_966L,
            "BCBA:MELI" to 13L,
            "BCBA:S13N6" to 1_923_076L,
        )
        // $ 542.573,83 + 13 × 23.420 + 1.923.076 × 106,251 / 100.
        assertEquals(
            mapOf("ARS" to 54_257_383L + 30_446_000L + 204_328_748L, "USD" to 304_966L),
            valuation.value(iol),
        )
    }

    @Test
    fun zeroLinesAndClosedPositionsDisappear() {
        val closed = mapOf("ARS" to 100L, "USD" to 0L, "FCI:IOLCAMA" to 0L, "BCBA:MELI" to 0L)
        assertEquals(mapOf("ARS" to 100L), valuation.value(closed))
        // A holdings account whose positions all closed shows nothing at all.
        assertEquals(emptyMap(), valuation.value(mapOf("BCBA:MELI" to 0L)))
    }

    @Test
    fun scaleAndQuoteCurrencyAreRespected() {
        // 0,4939 TTWO (scale 4) at US$ 202,84.
        assertEquals("USD" to 10_018L, valuation.valueOf("NASDAQ:TTWO", 4939L))
        // 83.937,128 fund units (scale 4) at 12,23127.
        assertEquals("ARS" to 102_665_768L, valuation.valueOf("FCI:IOLCAMA", 839_371_280L))
    }

    @Test
    fun anInstrumentWithoutAPriceHasNoMoneyValue() {
        assertNull(valuation.valueOf("BCBA:XYZ", 5L))
        assertEquals(mapOf("ARS" to 100L), valuation.value(mapOf("ARS" to 100L, "BCBA:XYZ" to 5L)))
        // A code nobody described is a currency as far as this knows.
        assertEquals(mapOf("EUR" to 100L), valuation.value(mapOf("EUR" to 100L)))
    }
}
