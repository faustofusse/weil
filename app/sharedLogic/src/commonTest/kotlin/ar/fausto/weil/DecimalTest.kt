package ar.fausto.weil

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DecimalTest {

    private fun d(text: String) = Decimal.parse(text) ?: error("unparseable: $text")

    @Test
    fun parsesWhatBrokersSend() {
        assertEquals("1.586833", d("1.586833").toPlainString())
        // IOL's `titulosValorizados`: trailing zeros are kept as sent.
        assertEquals("2347747.480760000000", d("2347747.480760000000").toPlainString())
        assertEquals("-0.5", d("-0.5").toPlainString())
        assertEquals("0.5", d(".5").toPlainString())
        assertEquals("12", d("+12").toPlainString())
        // Exponents: what a JSON serializer emits for small floats.
        assertEquals("0.0015", d("1.5E-3").toPlainString())
        assertEquals("2000", d("2e3").toPlainString())
    }

    @Test
    fun rejectsWhatIsNotAMachineNumber() {
        // es-AR grouping is Money.parse's job, not this one's.
        assertNull(Decimal.parse("1,5"))
        assertNull(Decimal.parse("1.234,56"))
        assertNull(Decimal.parse(""))
        assertNull(Decimal.parse("."))
        assertNull(Decimal.parse("-"))
        assertNull(Decimal.parse("1.2.3"))
        assertNull(Decimal.parse("abc"))
        assertNull(Decimal.parse("1e"))
    }

    /** Real IOL numbers: the value IOL reported must come out to the cent. */
    @Test
    fun fundRedemptionMatchesIol() {
        // Rescate FCI IOLPORA: 341.922,4514 cuotapartes × 1,586833 = 542.573,83.
        val value = d("341922.4514") * d("1.586833")
        assertEquals(54257383L, value.toMinorUnits(2))
    }

    @Test
    fun bondPricedPer100FaceValue() {
        // S13N6: 1.923.076 VN × 106,251 / 100 = 2.043.287,48 (IOL: 2043287.480760).
        val value = d("1923076") * d("106.251").movePointLeft(2)
        assertEquals("2043287.48076", value.stripTrailingZeros().toPlainString())
        assertEquals(204328748L, value.toMinorUnits(2))
    }

    @Test
    fun fractionalShareBuy() {
        // 0,4939 TTWO at 202,43 → 99,98 (the fill IBKR reported).
        val quantity = Decimal.ofMinorUnits(4939, 4)
        assertEquals("0.4939", quantity.toPlainString())
        assertEquals(9998L, (quantity * d("202.43")).toMinorUnits(2))
    }

    /** bignum issue #337: its BigDecimal gives 53419.66 here. */
    @Test
    fun divisionRoundsHalfEvenCorrectly() {
        val result = (Decimal.of(145690) * Decimal.of(11)).divide(Decimal.of(30), 2)
        assertEquals("53419.67", result.toPlainString())
    }

    @Test
    fun halfEvenTiesGoToTheEvenNeighbour() {
        assertEquals("0.12", d("0.125").rescale(2).toPlainString())
        assertEquals("0.14", d("0.135").rescale(2).toPlainString())
        assertEquals("-0.12", d("-0.125").rescale(2).toPlainString())
        assertEquals("2", d("2.5").rescale(0).toPlainString())
        assertEquals("4", d("3.5").rescale(0).toPlainString())
        assertEquals("-2", d("-2.5").rescale(0).toPlainString())
        // Not a tie: plain rounding either way.
        assertEquals("0.13", d("0.1251").rescale(2).toPlainString())
        assertEquals("-0.13", d("-0.1251").rescale(2).toPlainString())
        // Growing the scale pads, never rounds.
        assertEquals("1.5000", d("1.5").rescale(4).toPlainString())
    }

    @Test
    fun divideHandlesSignsAndScales() {
        assertEquals("0.33", Decimal.of(1).divide(Decimal.of(3), 2).toPlainString())
        assertEquals("-0.67", Decimal.of(-2).divide(Decimal.of(3), 2).toPlainString())
        assertEquals("-0.67", Decimal.of(2).divide(Decimal.of(-3), 2).toPlainString())
        assertEquals("0.67", Decimal.of(-2).divide(Decimal.of(-3), 2).toPlainString())
        // Divisor with more decimals than the result (the shift goes negative).
        assertEquals("400", d("1").divide(d("0.0025"), 0).toPlainString())
        // Average cost: 100,98 for 0,4939 units → 204,454343 per unit.
        assertEquals("204.454343", d("100.98").divide(d("0.4939"), 6).toPlainString())
        assertFailsWith<IllegalArgumentException> { Decimal.of(1).divide(Decimal.ZERO, 2) }
    }

    @Test
    fun toMinorUnitsRefusesToWrap() {
        assertFailsWith<ArithmeticException> { d("100000000000000000000").toMinorUnits(2) }
        assertEquals(Long.MAX_VALUE, Decimal.of(Long.MAX_VALUE).toMinorUnits(0))
        assertEquals(Long.MIN_VALUE, Decimal.of(Long.MIN_VALUE).toMinorUnits(0))
    }

    /** The case that ruled out plain Long: a big position times a 6-decimal price. */
    @Test
    fun productsBeyondLongStayExact() {
        val quantity = Decimal.ofMinorUnits(3_419_224_514_000L, 4) // 341.922.451,4 units
        val price = d("1586833.123456")
        val value = quantity * price
        // Unscaled that is ~5,4·10^24: a Long product would have wrapped.
        assertEquals("542573871534794.3600384000", value.toPlainString())
        assertEquals(54257387153479436L, value.toMinorUnits(2))
    }

    @Test
    fun comparesNumerically() {
        assertEquals(d("1.0"), d("1.00"))
        assertEquals(d("1.0").hashCode(), d("1.00").hashCode())
        assertEquals(Decimal.ZERO, d("0.000"))
        assertTrue(d("0.5") < d("0.51"))
        assertTrue(d("-1") < Decimal.ZERO)
        assertEquals(d("1.5"), d("1.25") + d("0.25"))
        assertEquals(d("-0.75"), d("0.25") - d("1"))
        assertEquals("1.25", d("-1.25").abs().toPlainString())
    }
}
