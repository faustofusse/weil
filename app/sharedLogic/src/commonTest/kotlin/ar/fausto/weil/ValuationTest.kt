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

    @Test
    fun positionsCarryValueCostAndUnrealizedGain() {
        val lines = valuation.positions(
            mapOf(
                "ARS" to HeldPosition(54_257_383L, 0L, null),
                // 13 MELI that cost $ 319.256,83 (commission capitalized).
                "BCBA:MELI" to HeldPosition(13L, 31_925_683L, "ARS"),
                "BCBA:S13N6" to HeldPosition(1_923_076L, 200_000_000L, "ARS"),
                // Bought with pesos, quoted in dollars: no gain without a rate.
                "NASDAQ:TTWO" to HeldPosition(4939L, 15_000_000L, "ARS"),
                "BCBA:XYZ" to HeldPosition(5L, 1_000L, "ARS"),
                "FCI:IOLCAMA" to HeldPosition(0L, 0L, null),
            ),
        )
        // Cash is not a position; open by currency then value, unpriced, then closed.
        assertEquals(
            listOf("BCBA:S13N6", "BCBA:MELI", "NASDAQ:TTWO", "BCBA:XYZ", "FCI:IOLCAMA"),
            lines.map { it.commodity },
        )
        val meli = lines.first { it.commodity == "BCBA:MELI" }
        assertEquals("ARS", meli.valueCommodity)
        assertEquals(30_446_000L, meli.valueMinor)
        assertEquals(30_446_000L - 31_925_683L, meli.unrealizedMinor)
        assertEquals(-0.0463, meli.unrealizedRatio!!, 0.0001)
        val ttwo = lines.first { it.commodity == "NASDAQ:TTWO" }
        assertEquals("USD", ttwo.valueCommodity)
        assertNull(ttwo.unrealizedMinor)
        val xyz = lines.first { it.commodity == "BCBA:XYZ" }
        assertNull(xyz.valueMinor)
        assertNull(xyz.unrealizedMinor)
        val closed = lines.last()
        assertEquals(true, closed.closed)
        assertNull(closed.valueMinor)
        assertNull(closed.costMinor)
    }
}
