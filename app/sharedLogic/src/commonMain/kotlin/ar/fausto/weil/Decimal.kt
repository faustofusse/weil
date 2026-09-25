package ar.fausto.weil

import com.ionspin.kotlin.bignum.integer.BigInteger

/**
 * Exact decimal number: `unscaled × 10^-scale`, with an arbitrary-precision
 * [unscaled] so that quantity × price never overflows on its way to cents.
 *
 * Why it exists (plans/inversiones-brokers.md, question 2): brokers state
 * quantities and prices with more decimals than money has (a fund unit worth
 * 159,226590, a bond priced per 100 face value = 1,06251 per unit), and the
 * product of a quantity in minor units by a 6-decimal price leaves `Long`
 * only ~×1.700 of headroom over a real portfolio. `Double` is out for the
 * same reason `Money` never uses it: a balance assertion that fails by a
 * rounding error reports a difference that does not exist.
 *
 * Built on the bignum library's `BigInteger` only. Its `BigDecimal` is avoided
 * on purpose: 0.3.10 has open rounding bugs in `ROUND_HALF_TO_EVEN` (#337) and
 * `divide` (#318, #331). Every operation here reduces to integer
 * multiplication and one integer division, and the rounding is our own.
 *
 * Storage never holds a [Decimal]: amounts stay `Long` minor units, prices
 * stay decimal strings. This is the arithmetic in between, converted back
 * with [toMinorUnits], which throws instead of truncating.
 */
class Decimal private constructor(
    val unscaled: BigInteger,
    /** Digits after the point; never negative (a whole number has scale 0). */
    val scale: Int,
) : Comparable<Decimal> {

    init {
        require(scale >= 0) { "negative scale: $scale" }
    }

    val signum: Int get() = unscaled.signum()
    val isZero: Boolean get() = unscaled.isZero()

    operator fun plus(other: Decimal): Decimal {
        val s = maxOf(scale, other.scale)
        return Decimal(upscaled(s).add(other.upscaled(s)), s)
    }

    operator fun minus(other: Decimal): Decimal = this + (-other)

    operator fun unaryMinus(): Decimal = Decimal(unscaled.negate(), scale)

    /** Exact: the scales add up, nothing is rounded. */
    operator fun times(other: Decimal): Decimal =
        Decimal(unscaled.multiply(other.unscaled), scale + other.scale)

    fun abs(): Decimal = if (signum < 0) -this else this

    /**
     * Exact division by 10^[n]: only the scale moves. This is how a bond's
     * price per 100 face value becomes a price per unit, without the rounding
     * a general division would need.
     */
    fun movePointLeft(n: Int): Decimal {
        require(n >= 0) { "negative shift: $n" }
        return Decimal(unscaled, scale + n)
    }

    /**
     * The same value with exactly [newScale] decimals, rounding half-even
     * ("banker's rounding": ties go to the even neighbour, so a column of
     * rounded amounts doesn't drift upward the way half-up does).
     */
    fun rescale(newScale: Int): Decimal {
        require(newScale >= 0) { "negative scale: $newScale" }
        if (newScale >= scale) return Decimal(upscaled(newScale), newScale)
        return Decimal(roundedQuotient(unscaled, TEN.pow(scale - newScale)), newScale)
    }

    /**
     * this / [other], rounded half-even to [resultScale] decimals. Used for
     * averages (the cost of part of a position); everything else in the
     * broker path is exact and shouldn't need it.
     */
    fun divide(other: Decimal, resultScale: Int): Decimal {
        require(!other.isZero) { "division by zero" }
        require(resultScale >= 0) { "negative scale: $resultScale" }
        // this/other = (u1·10^-s1)/(u2·10^-s2); scaled to 10^-r it is
        // u1·10^(r - s1 + s2) / u2. Both exponents are made non-negative by
        // moving the power to whichever side it belongs on.
        val shift = resultScale - scale + other.scale
        val numerator = if (shift >= 0) unscaled.multiply(TEN.pow(shift)) else unscaled
        val denominator = if (shift >= 0) other.unscaled else other.unscaled.multiply(TEN.pow(-shift))
        return Decimal(roundedQuotient(numerator, denominator), resultScale)
    }

    /**
     * Minor units at [scale] decimals as a `Long` — the way back into
     * storage. Rounds half-even to that scale, then throws [ArithmeticException]
     * when the result does not fit: an amount that overflows a Long is a bug
     * upstream, and silently wrapping it would book a random number.
     */
    fun toMinorUnits(scale: Int): Long {
        val rounded = rescale(scale).unscaled
        if (rounded.compareTo(LONG_MAX) > 0 || rounded.compareTo(LONG_MIN) < 0) {
            throw ArithmeticException("${toPlainString()} does not fit in a Long at scale $scale")
        }
        return rounded.longValue(exactRequired = true)
    }

    /** Plain notation, never scientific, trailing zeros kept: "-1234.5000". */
    fun toPlainString(): String {
        val negative = signum < 0
        val digits = unscaled.abs().toString(10)
        val body = if (scale == 0) {
            digits
        } else {
            val padded = digits.padStart(scale + 1, '0')
            padded.substring(0, padded.length - scale) + "." + padded.substring(padded.length - scale)
        }
        return if (negative) "-$body" else body
    }

    /** Same number with trailing fractional zeros dropped ("1.50" → "1.5"). */
    fun stripTrailingZeros(): Decimal {
        var u = unscaled
        var s = scale
        while (s > 0) {
            val (q, r) = u.divideAndRemainder(TEN)
            if (!r.isZero()) break
            u = q
            s--
        }
        return Decimal(u, s)
    }

    /** Numeric comparison: 1.0 and 1.00 compare equal. */
    override fun compareTo(other: Decimal): Int {
        val s = maxOf(scale, other.scale)
        return upscaled(s).compareTo(other.upscaled(s))
    }

    /** Numeric equality, like [compareTo] (unlike java.math.BigDecimal). */
    override fun equals(other: Any?): Boolean = other is Decimal && compareTo(other) == 0

    override fun hashCode(): Int = stripTrailingZeros().let { 31 * it.unscaled.hashCode() + it.scale }

    override fun toString(): String = toPlainString()

    private fun upscaled(target: Int): BigInteger =
        if (target == scale) unscaled else unscaled.multiply(TEN.pow(target - scale))

    companion object {
        private val TEN = BigInteger.TEN
        private val TWO = BigInteger.TWO
        private val LONG_MAX = BigInteger.fromLong(Long.MAX_VALUE)
        private val LONG_MIN = BigInteger.fromLong(Long.MIN_VALUE)

        val ZERO = Decimal(BigInteger.ZERO, 0)

        fun of(value: Long): Decimal = Decimal(BigInteger.fromLong(value), 0)

        /** [minorUnits] × 10^-[scale]: a stored amount back into a number. */
        fun ofMinorUnits(minorUnits: Long, scale: Int): Decimal =
            Decimal(BigInteger.fromLong(minorUnits), scale)

        /**
         * Parses machine-formatted numbers: what a broker API or report
         * sends, not what a person types (that is [Money.parse], with its
         * es-AR grouping rules). Accepts an optional sign, digits, one '.',
         * and an exponent ("1.5E-3", which JSON serializers emit for small
         * floats). No grouping separators, no ','.
         */
        fun parse(text: String): Decimal? {
            val t = text.trim()
            if (t.isEmpty()) return null
            val expAt = t.indexOfFirst { it == 'e' || it == 'E' }
            val mantissa = if (expAt >= 0) t.substring(0, expAt) else t
            val exponent = if (expAt >= 0) t.substring(expAt + 1).toIntOrNull() ?: return null else 0
            val negative = mantissa.startsWith('-')
            val body = mantissa.removePrefix("-").removePrefix("+")
            if (body.isEmpty() || body.count { it == '.' } > 1) return null
            val dot = body.indexOf('.')
            val intPart = if (dot >= 0) body.substring(0, dot) else body
            val fracPart = if (dot >= 0) body.substring(dot + 1) else ""
            if (intPart.isEmpty() && fracPart.isEmpty()) return null
            if (!(intPart + fracPart).all { it in '0'..'9' }) return null
            var unscaled = BigInteger.parseString((intPart + fracPart).ifEmpty { "0" }, 10)
            if (negative) unscaled = unscaled.negate()
            // 1.5E-3: scale = 1 - (-3) = 4 → 0.0015. A positive exponent that
            // exceeds the fractional digits multiplies instead.
            val scale = fracPart.length - exponent
            return if (scale >= 0) {
                Decimal(unscaled, scale)
            } else {
                Decimal(unscaled.multiply(TEN.pow(-scale)), 0)
            }
        }

        /**
         * n / d rounded half-even to an integer. Works on magnitudes and puts
         * the sign back, so it does not depend on the library's remainder
         * sign convention.
         */
        private fun roundedQuotient(n: BigInteger, d: BigInteger): BigInteger {
            val negative = (n.signum() < 0) != (d.signum() < 0)
            val (q, r) = n.abs().divideAndRemainder(d.abs())
            val twice = r.multiply(TWO)
            val cmp = twice.compareTo(d.abs())
            // Round up past the half, and at exactly half only when q is odd.
            val roundUp = cmp > 0 || (cmp == 0 && !q.divideAndRemainder(TWO).second.isZero())
            val magnitude = if (roundUp) q.add(BigInteger.ONE) else q
            return if (negative) magnitude.negate() else magnitude
        }
    }
}
