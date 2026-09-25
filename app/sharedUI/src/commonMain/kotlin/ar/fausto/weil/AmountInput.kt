package ar.fausto.weil

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

/**
 * Money input plumbing shared by every amount field (quick entry, ledger
 * editor, import review).
 *
 * The state holds a *raw* es-AR string — `-?digits[,digits{0,scale}]`, never
 * thousands separators — so [Money.parse] keeps working unchanged, while
 * [AmountVisualTransformation] draws the grouped form ("1.234,56") as the user
 * types. Grouping as a visual transformation instead of rewriting the state is
 * what keeps the caret where the user put it: rewriting a `String`-valued
 * TextField to insert a dot mid-string throws the cursor to the end.
 */
fun sanitizeAmountInput(input: String, allowNegative: Boolean = true, maxDecimals: Int = 2): String {
    val negative = allowNegative && input.contains('-')
    // A pasted "1.234,56" has both separators: the rightmost kind is the
    // decimal one, the other is grouping noise to drop. A lone '.' or ',' is
    // always the decimal separator here — grouping is never typed by hand.
    val bothSeparators = input.contains('.') && input.contains(',')
    val decimalChar = if (bothSeparators) {
        if (input.lastIndexOf(',') > input.lastIndexOf('.')) ',' else '.'
    } else {
        null
    }
    val whole = StringBuilder()
    val frac = StringBuilder()
    var inFraction = false
    for (c in input) {
        when {
            c.isDigit() -> {
                if (inFraction) {
                    if (frac.length < maxDecimals) frac.append(c)
                } else {
                    whole.append(c)
                }
            }
            c == '.' || c == ',' -> {
                val isDecimal = if (decimalChar != null) c == decimalChar else true
                // A whole-unit quantity (scale 0) has no decimal point to type.
                if (isDecimal && !inFraction && maxDecimals > 0) inFraction = true
            }
        }
    }
    // "007" → "7", but keep a single leading zero so "0,5" can be typed.
    var wholeText = whole.toString().trimStart('0')
    if (wholeText.isEmpty() && whole.isNotEmpty()) wholeText = "0"
    val body = when {
        inFraction -> (wholeText.ifEmpty { "0" }) + "," + frac
        else -> wholeText
    }
    if (body.isEmpty()) return if (negative) "-" else ""
    return (if (negative) "-" else "") + body
}

/**
 * Raw editable text for a stored minor-unit amount (drops the grouping dots).
 * At a quantity's scale the trailing zeros go too: "13" MELI and "0,5" TTWO,
 * not "0,5000"; money keeps its two decimals.
 */
fun rawAmountText(minorUnits: Long, scale: Int = 2): String {
    val text = formatMinorUnits(minorUnits, scale)
    val trimmed = if (scale != 2 && text.contains(',')) text.trimEnd('0').removeSuffix(",") else text
    return sanitizeAmountInput(trimmed, maxDecimals = scale)
}

/** Flips the sign of a raw amount string; the way to type a minus on iOS. */
fun toggleAmountSign(raw: String): String =
    if (raw.startsWith("-")) raw.removePrefix("-") else "-$raw"

/** Draws raw digits with es-AR thousands separators, caret mapping included. */
object AmountVisualTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        val signLen = if (raw.startsWith("-")) 1 else 0
        val body = raw.substring(signLen)
        val commaAt = body.indexOf(',')
        val intDigits = if (commaAt >= 0) body.substring(0, commaAt) else body
        val rest = if (commaAt >= 0) body.substring(commaAt) else ""
        val grouped = if (intDigits.isEmpty()) {
            ""
        } else {
            intDigits.reversed().chunked(3).joinToString(".").reversed()
        }
        val out = raw.take(signLen) + grouped + rest
        val added = grouped.length - intDigits.length

        val mapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int {
                if (added == 0) return offset.coerceIn(0, out.length)
                val digits = offset - signLen
                if (digits <= 0) return offset.coerceIn(0, out.length)
                if (digits >= intDigits.length) return (offset + added).coerceAtMost(out.length)
                // Separators still to come after `digits` digits.
                val pending = (intDigits.length - digits - 1) / 3
                return signLen + digits + (added - pending)
            }

            override fun transformedToOriginal(offset: Int): Int {
                val clamped = offset.coerceIn(0, out.length)
                val dots = out.take(clamped).count { it == '.' }
                return (clamped - dots).coerceIn(0, raw.length)
            }
        }
        return TransformedText(AnnotatedString(out), mapping)
    }
}
