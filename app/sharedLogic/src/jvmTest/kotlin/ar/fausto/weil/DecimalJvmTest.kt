package ar.fausto.weil

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [Decimal] checked against java.math.BigDecimal on random inputs — the
 * reference the bignum library itself is not (see Decimal's doc on its open
 * rounding bugs). Runs on the JVM only; the arithmetic under test is the
 * common code every platform shares.
 */
class DecimalJvmTest {

    private val random = Random(20260925)

    private fun randomPair(): Pair<Decimal, BigDecimal> {
        val unscaled = when (random.nextInt(3)) {
            0 -> random.nextLong(-1_000_000, 1_000_000)
            1 -> random.nextLong()
            else -> random.nextLong(-100, 100)
        }
        val scale = random.nextInt(0, 9)
        return Decimal.ofMinorUnits(unscaled, scale) to BigDecimal.valueOf(unscaled, scale)
    }

    private fun assertSame(expected: BigDecimal, actual: Decimal, what: String) {
        assertEquals(expected.toPlainString(), actual.toPlainString(), what)
    }

    @Test
    fun arithmeticMatchesJavaBigDecimal() {
        repeat(5_000) {
            val (a, ja) = randomPair()
            val (b, jb) = randomPair()
            assertSame(ja.multiply(jb), a * b, "$ja * $jb")
            // plus/minus: compared numerically, since java keeps max(scale).
            assertEquals(0, ja.add(jb).compareTo(BigDecimal(( a + b).toPlainString())), "$ja + $jb")
            assertEquals(0, ja.subtract(jb).compareTo(BigDecimal((a - b).toPlainString())), "$ja - $jb")
            assertEquals(ja.compareTo(jb), a.compareTo(b), "compare $ja $jb")
        }
    }

    @Test
    fun rescaleMatchesHalfEven() {
        repeat(5_000) {
            val (a, ja) = randomPair()
            val target = random.nextInt(0, 9)
            assertSame(ja.setScale(target, RoundingMode.HALF_EVEN), a.rescale(target), "$ja → $target")
        }
    }

    @Test
    fun divideMatchesHalfEven() {
        repeat(5_000) {
            val (a, ja) = randomPair()
            val (b, jb) = randomPair()
            if (jb.signum() == 0) return@repeat
            val target = random.nextInt(0, 9)
            assertSame(ja.divide(jb, target, RoundingMode.HALF_EVEN), a.divide(b, target), "$ja / $jb @ $target")
        }
    }

    @Test
    fun parseRoundTripsJavaOutput() {
        repeat(2_000) {
            val (_, ja) = randomPair()
            val text = ja.toPlainString()
            assertEquals(text, Decimal.parse(text)?.toPlainString(), text)
            // Scientific notation, which is what Double.toString and some
            // serializers produce.
            val sci = ja.toString()
            assertEquals(0, ja.compareTo(BigDecimal(Decimal.parse(sci)!!.toPlainString())), sci)
        }
    }
}
