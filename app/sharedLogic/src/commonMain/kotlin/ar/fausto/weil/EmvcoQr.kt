package ar.fausto.weil

/**
 * Minimal EMVCo merchant-presented QR (MPM) reader.
 *
 * Pure, no I/O, no platform deps — same shape as [Ingest] / [Reconcile]. It
 * exists so the app can show *what* is about to be paid before handing the
 * untouched payload to a wallet app; the wallet still resolves the QR
 * server-side, so anything we fail to understand is not fatal.
 *
 * Merchant account templates (tags 26–51) are deliberately left unparsed:
 * their payloads are acquirer-specific and nothing in phase 1 needs them.
 */
data class QrPayment(
    /** The scanned string, byte-for-byte — this is what gets handed off. */
    val raw: String,
    /** Tag 59, merchant name. */
    val merchant: String?,
    /** Tag 54; null on static QRs, where the payer types the amount. */
    val amountMinor: Long?,
    /** Tag 53 (ISO 4217 numeric) mapped to the app's commodity codes. */
    val commodity: String?,
    /** Tag 52, merchant category code. Unused in phase 1, kept for phase 2. */
    val mcc: String?,
)

private const val TAG_MCC = "52"
private const val TAG_CURRENCY = "53"
private const val TAG_AMOUNT = "54"
private const val TAG_MERCHANT = "59"
private const val TAG_CRC = "63"

/** ISO 4217 numeric → commodity code. Only what the app can actually hold. */
private val CURRENCIES = mapOf("032" to "ARS", "840" to "USD", "986" to "BRL", "858" to "UYU")

/** Parses [raw] as an EMVCo MPM payload; null when it isn't one (or the CRC fails). */
fun parseEmvcoQr(raw: String): QrPayment? {
    if (raw.length < 12) return null
    val fields = tlv(raw) ?: return null
    // Payload format indicator must open the payload.
    if (fields.firstOrNull()?.first != "00") return null
    if (!crcValid(raw, fields)) return null

    val byTag = fields.associate { it }
    return QrPayment(
        raw = raw,
        merchant = byTag[TAG_MERCHANT]?.trim()?.takeIf { it.isNotEmpty() },
        amountMinor = byTag[TAG_AMOUNT]?.let(::decimalToMinor),
        commodity = byTag[TAG_CURRENCY]?.let { CURRENCIES[it] },
        mcc = byTag[TAG_MCC]?.takeIf { it.isNotEmpty() },
    )
}

/** Flat TLV walk (2-char tag, 2-char length, value). Null on malformed input. */
private fun tlv(raw: String): List<Pair<String, String>>? {
    val out = mutableListOf<Pair<String, String>>()
    var i = 0
    while (i < raw.length) {
        if (i + 4 > raw.length) return null
        val tag = raw.substring(i, i + 2)
        if (!tag.all { it.isDigit() }) return null
        val length = raw.substring(i + 2, i + 4).toIntOrNull() ?: return null
        val from = i + 4
        val to = from + length
        if (to > raw.length) return null
        out += tag to raw.substring(from, to)
        i = to
    }
    return out.takeIf { it.isNotEmpty() }
}

/**
 * CRC16-CCITT (poly 0x1021, init 0xFFFF) over everything up to and including
 * the CRC tag+length ("6304"), compared with tag 63's value.
 */
private fun crcValid(raw: String, fields: List<Pair<String, String>>): Boolean {
    val expected = fields.lastOrNull()?.takeIf { it.first == TAG_CRC }?.second ?: return false
    if (expected.length != 4) return false
    val covered = raw.dropLast(4)
    if (!covered.endsWith("6304")) return false
    return crc16(covered).equals(expected, ignoreCase = true)
}

private fun crc16(data: String): String {
    var crc = 0xFFFF
    for (ch in data) {
        crc = crc xor ((ch.code and 0xFF) shl 8)
        repeat(8) {
            crc = if (crc and 0x8000 != 0) ((crc shl 1) xor 0x1021) else (crc shl 1)
            crc = crc and 0xFFFF
        }
    }
    return crc.toString(16).uppercase().padStart(4, '0')
}

/** EMVCo amounts are plain decimals with '.' — never grouped, never signed. */
private fun decimalToMinor(text: String): Long? {
    val body = text.trim()
    if (body.isEmpty() || body.any { !it.isDigit() && it != '.' }) return null
    val dot = body.indexOf('.')
    val whole = (if (dot == -1) body else body.substring(0, dot)).ifEmpty { "0" }
    val frac = if (dot == -1) "" else body.substring(dot + 1)
    if (frac.contains('.')) return null
    val cents = frac.padEnd(2, '0').take(2)
    val units = whole.toLongOrNull() ?: return null
    return units * 100 + (cents.toLongOrNull() ?: return null)
}
