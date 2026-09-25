package ar.fausto.weil

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OfficialRateTest {

    private fun d(text: String) = Decimal.parse(text)!!

    /** Trimmed from a real answer of Cotizaciones/USD (2026-09-24..25). */
    private val body = """
        {"status":200,"metadata":{"resultset":{"count":3,"offset":0,"limit":1000}},"results":[
          {"fecha":"2026-09-25","detalle":[{"codigoMoneda":"USD","descripcion":"DOLAR E.E.U.U.","tipoPase":0.00000000,"tipoCotizacion":1525.50000000}]},
          {"fecha":"2026-09-24","detalle":[{"codigoMoneda":"USD","descripcion":"DOLAR E.E.U.U.","tipoPase":0.00000000,"tipoCotizacion":1519.50000000}]},
          {"fecha":"2026-09-23","detalle":[]}
        ]}
    """.trimIndent()

    @Test
    fun parsesOneQuotePerDayAtTheCloseInArgentina() {
        val rates = parseBcraUsd(body)
        assertEquals(listOf(d("1525.5"), d("1519.5")), rates.map { it.price })
        assertEquals(setOf("USD|ARS|bcra"), rates.map { "${it.commodity}|${it.quoteCommodity}|${it.source}" }.toSet())
        // 2026-09-25 15:00 ART = 18:00 UTC.
        assertEquals(1_790_359_200_000L, rates.first().at)
    }

    @Test
    fun garbageIsNothingNotAnError() {
        assertEquals(emptyList(), parseBcraUsd("""{"status":404,"errorMessages":["x"]}"""))
    }

    private val at = 1_790_359_200_000L
    private val valuation = Valuation(official = PriceQuote("USD", "ARS", at, d("1525.50"), OFFICIAL_SOURCE))

    @Test
    fun convertsPesosAndDollarsIntoOneCurrency() {
        // $ 1.525.500 + US$ 100 = US$ 1.100.
        val money = mapOf("ARS" to 152_550_000L, "USD" to 10_000L)
        assertEquals(110_000L, valuation.convert(money, "USD", at)?.minor)
        assertEquals(152_550_000L + 15_255_000L, valuation.convert(money, "ARS", at)?.minor)
        // Rounded to the cent: $ 1.000 / 1.525,50 = US$ 0,6555…
        assertEquals(66L, valuation.convert(mapOf("ARS" to 100_000L), "USD", at)?.minor)
        // A negative net worth converts with its sign.
        assertEquals(-66L, valuation.convert(mapOf("ARS" to -100_000L), "USD", at)?.minor)
    }

    @Test
    fun noLineWhenItWouldLieOrRepeat() {
        val pesos = mapOf("ARS" to 100_000L)
        assertNull(Valuation().convert(pesos, "USD", at), "no rate")
        assertNull(valuation.convert(pesos, "USD", at + OFFICIAL_RATE_MAX_AGE_MS + 1), "stale rate")
        assertNull(valuation.convert(pesos + ("EUR" to 100L), "USD", at), "a currency without a rate")
        assertNull(valuation.convert(mapOf("USD" to 100L), "USD", at), "nothing to convert")
        assertNull(valuation.convert(pesos, NET_WORTH_CONVERSION_OFF, at), "turned off")
    }
}
