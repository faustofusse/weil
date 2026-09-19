package ar.fausto.weil

/**
 * Words that must never be shown in full in the UI.
 *
 * Censoring is display-only: it happens where dynamic text (payees, notes,
 * account names, notification/email bodies) is rendered, never on the way to
 * the database — what the user typed is what gets stored, searched and synced.
 * Text fields being edited are therefore left alone on purpose.
 */
private val CENSORED_WORDS = listOf("kuelgue")

/** How many leading characters survive: "kuelgue" -> "ku*****". */
private const val CENSOR_KEEP = 2

/**
 * Masks every [CENSORED_WORDS] occurrence, case-insensitively, keeping the
 * first [CENSOR_KEEP] characters as typed and replacing the rest with `*`.
 */
fun String.censored(): String {
    var out = this
    for (word in CENSORED_WORDS) {
        if (!out.contains(word, ignoreCase = true)) continue
        val mask = "*".repeat(word.length - CENSOR_KEEP)
        val builder = StringBuilder(out.length)
        var i = 0
        while (i < out.length) {
            if (out.regionMatches(i, word, 0, word.length, ignoreCase = true)) {
                builder.append(out, i, i + CENSOR_KEEP).append(mask)
                i += word.length
            } else {
                builder.append(out[i])
                i++
            }
        }
        out = builder.toString()
    }
    return out
}

/** Null-friendly [censored], for the many optional notes/subjects. */
fun String?.censoredOrNull(): String? = this?.censored()
