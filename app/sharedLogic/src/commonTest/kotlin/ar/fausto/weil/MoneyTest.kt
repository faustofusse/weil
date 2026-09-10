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
}
