package ar.fausto.weil

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MoneyTest {

    @Test
    fun parsesPlainAndDecimal() {
        assertEquals(123456L, Money.parse("1234,56", "ARS")?.minorUnits)
        assertEquals(123456L, Money.parse("1234.56", "ARS")?.minorUnits)
        assertEquals(123400L, Money.parse("1234", "ARS")?.minorUnits)
        assertEquals(123450L, Money.parse("1234.5", "ARS")?.minorUnits)
        assertEquals(100L, Money.parse("1", "ARS")?.minorUnits)
    }

    @Test
    fun treatsLoneSeparatorWithMoreThanTwoDigitsAsThousands() {
        assertEquals(123400L, Money.parse("1.234", "ARS")?.minorUnits)
        assertEquals(123400L, Money.parse("1,234", "ARS")?.minorUnits)
    }

    @Test
    fun bothSeparatorsRightmostIsDecimal() {
        assertEquals(123456L, Money.parse("1.234,56", "ARS")?.minorUnits)
        assertEquals(123456L, Money.parse("1,234.56", "ARS")?.minorUnits)
        assertEquals(123400000L, Money.parse("1.234.000", "ARS")?.minorUnits)
    }

    @Test
    fun negativesAndSigns() {
        assertEquals(-123456L, Money.parse("-1234,56", "ARS")?.minorUnits)
        assertEquals(-123456L, Money.parse("-1.234,56", "ARS")?.minorUnits)
    }

    @Test
    fun stripsSpaces() {
        assertEquals(123456L, Money.parse("1 234,56", "ARS")?.minorUnits)
        assertEquals(123456L, Money.parse("1\u00a0234,56", "ARS")?.minorUnits)
    }

    @Test
    fun rejectsBadInput() {
        assertNull(Money.parse("", "ARS"))
        assertNull(Money.parse("   ", "ARS"))
        assertNull(Money.parse("abc", "ARS"))
        assertNull(Money.parse("1.2,345", "ARS"))
        assertNull(Money.parse("1a2", "ARS"))
        assertNull(Money.parse("-", "ARS"))
    }

    @Test
    fun bareFractionalInputIsFractionNotThousands() {
        assertEquals(56L, Money.parse(",56", "ARS")?.minorUnits)
        assertEquals(50L, Money.parse(".5", "ARS")?.minorUnits)
    }

    @Test
    fun formatsEsAr() {
        assertEquals("1.234,56", formatMinorUnits(123456L))
        assertEquals("0,05", formatMinorUnits(5L))
        assertEquals("-1.234,56", formatMinorUnits(-123456L))
        assertEquals("1.000,00", formatMinorUnits(100000L))
    }

    @Test
    fun parsesQuantitiesAtTheirOwnScale() {
        // 0,4939 TTWO (scale 4), 13 MELI (scale 0), FCI units with grouping.
        assertEquals(4939L, Money.parse("0,4939", "NASDAQ:TTWO", 4)?.minorUnits)
        assertEquals(4939L, Money.parse("0.4939", "NASDAQ:TTWO", 4)?.minorUnits)
        assertEquals(5000L, Money.parse("0,5", "NASDAQ:TTWO", 4)?.minorUnits)
        assertEquals(1234L, Money.parse("0,1234", "FCI:X", 4)?.minorUnits)
        assertEquals(341922451400L, Money.parse("34.192.245,14", "FCI:X", 4)?.minorUnits)
        assertEquals(13L, Money.parse("13", "BCBA:MELI", 0)?.minorUnits)
        assertEquals(1923076L, Money.parse("1.923.076", "BCBA:S13N6", 0)?.minorUnits)
        assertEquals(1234L, Money.parse("1.234", "BCBA:X", 0)?.minorUnits)
        assertEquals(12340000L, Money.parse("1.234", "FCI:X", 4)?.minorUnits)
        assertEquals(1234L, Money.parse("0,1234", "FCI:X", 4)?.minorUnits)
        assertEquals(-4939L, Money.parse("-0,4939", "NASDAQ:TTWO", 4)?.minorUnits)
    }

    @Test
    fun rejectsMoreDecimalsThanTheScale() {
        assertNull(Money.parse("13,5", "BCBA:MELI", 0))
        assertNull(Money.parse("0,49391", "NASDAQ:TTWO", 4))
        assertNull(Money.parse("99999999999999999999", "ARS"))
    }

    @Test
    fun formatsAtAnyScaleAndRoundTrips() {
        assertEquals("1.234,56", formatMinorUnits(123456))
        assertEquals("0,05", formatMinorUnits(5))
        assertEquals("0,4939", formatMinorUnits(4939, 4))
        assertEquals("13", formatMinorUnits(13, 0))
        assertEquals("-1.923.076", formatMinorUnits(-1923076, 0))
        for ((minor, scale) in listOf(4939L to 4, 13L to 0, -341922451400L to 4, 123456L to 2, 1L to 6)) {
            assertEquals(minor, Money.parse(formatMinorUnits(minor, scale), "X", scale)?.minorUnits)
        }
    }
}
