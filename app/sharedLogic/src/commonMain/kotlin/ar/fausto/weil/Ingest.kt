package ar.fausto.weil

import kotlinx.serialization.Serializable

/**
 * Turns push notifications and email receipts into [CandidateEvent]s, the same
 * shape statement rows take, so the reconciliation engine matches all three
 * doors with one set of rules. See docs/reconciliation.md.
 *
 * Everything here is pure and deterministic — no model call, no network. The
 * notification listener runs in a background service with no connectivity
 * guarantee, and a bank alert is a fixed template anyway: "Pagaste a Spotify /
 * Debitamos $ 5.895,57 de tu cuenta" says exactly as much as a paragraph of
 * inference would.
 *
 * Recognition is an **allowlist of templates**, never a blocklist of
 * marketing. On real data (21.383 captured notifications) the ones containing
 * a "$" are overwhelmingly promotions — "¡15% OFF en Carrefour! Compra mínima:
 * $15.000" has an amount, a merchant and a verb, and differs from a real
 * charge only in its phrasing. So a row becomes a movement when it matches a
 * known sentence, and is ignored otherwise.
 */

/** An amount as written by a bank: "$ 5.895,57", "U$S100,00", "$ 19200". */
@Serializable
data class ParsedMoney(val amountMinor: Long, val commodity: String)

/**
 * A movement recognized in a notification or an email, before it knows which
 * of the user's accounts it belongs to.
 *
 * [accountHints] are the words that point at the account — the app's own name,
 * the card brand, the last digits printed in the receipt. Resolution against
 * the user's tree is [resolveAccountHint], kept separate because it needs the
 * account list and can legitimately fail (then the review screen's default
 * account applies).
 */
@Serializable
data class IngestedMovement(
    val ruleId: String,
    val source: EventSource,
    val sourceRef: String,
    val date: Long,
    val amountMinor: Long,
    val commodity: String,
    val payee: String,
    val direction: ImportDirection,
    val accountHints: List<String>,
)

/** [IngestedMovement] plus the resolved account, as the matcher wants it. */
fun IngestedMovement.toEvent(ownAccountId: String?): CandidateEvent {
    val signed = when (direction) {
        ImportDirection.Expense, ImportDirection.Transfer -> -amountMinor
        ImportDirection.Income -> amountMinor
    }
    return CandidateEvent(
        source = source,
        sourceRef = sourceRef,
        ownAccountId = ownAccountId,
        amountMinor = signed,
        commodity = commodity,
        date = date,
        rawPayee = payee,
        direction = direction,
    )
}

/** Where a rule reads a field from. */
enum class IngestField { Title, Body }

private data class Extract(val field: IngestField, val group: Int)

/**
 * One recognized sentence. [title] and [body] must both match (a rule with a
 * null [body] only reads the headline); the amount and the counterparty are
 * pulled from whichever of the two carries them, which differs per bank —
 * Mercado Pago puts the merchant in the title and the amount in the body,
 * Santander does the opposite.
 */
private data class IngestRule(
    val id: String,
    val sender: Regex,
    val title: Regex,
    val body: Regex? = null,
    val direction: ImportDirection,
    val amount: Extract,
    val payee: Extract? = null,
    val hints: List<String> = emptyList(),
    /** Extra words taken from the message itself (card brand, account kind). */
    val hintFrom: Extract? = null,
)

private const val MONEY = "((?:U\\\$S|US\\\$|USD|\\\$)?\\s*[0-9][0-9.,]*)"

/**
 * "Does this message mention money at all?" — the cheap prefilter that decides
 * which rows are worth embedding or running the rules over.
 *
 * Anchored on **both** sides on purpose. The prefix-only spelling misses real
 * movements measured on the dev device: "Recibiste 30.000 ARS de Fausto Fusse"
 * (Lemon Cash) and "You received 500,000 ARS" (DolarApp) write the currency
 * after the number, which is the normal order in English and common in
 * crypto-adjacent wallets.
 */
val MONEY_TEXT = Regex(
    "(?:U\\\$S|US\\\$|USD|ARS|\\\$)\\s*[0-9][0-9.,]*" +
        "|[0-9][0-9.,]*\\s*(?:U\\\$S|US\\\$|USD|ARS|pesos?|d[oó]lares?)\\b",
    RegexOption.IGNORE_CASE,
)

/** True when [text] carries a currency-anchored amount ([MONEY_TEXT]). */
fun hasAmount(text: String): Boolean = MONEY_TEXT.containsMatchIn(text)

/**
 * Mercado Pago and Santander, the two apps that actually post movement alerts
 * on this device. Adding a bank is adding rows here — no other file changes.
 */
private val NOTIFICATION_RULES = listOf(
    // "Pagaste a Spotify" / "Debitamos $ 5.895,57 de tu cuenta."
    IngestRule(
        id = "mp.paid.to",
        sender = Regex("^com\\.mercadopago\\."),
        title = Regex("^Pagaste a (.+)$", RegexOption.IGNORE_CASE),
        body = Regex("Debitamos\\s+$MONEY\\s+de tu cuenta", RegexOption.IGNORE_CASE),
        direction = ImportDirection.Expense,
        amount = Extract(IngestField.Body, 1),
        payee = Extract(IngestField.Title, 1),
        hints = listOf("mercado pago"),
    ),
    // "Pagaste $ 46.210 a Rappi"
    IngestRule(
        id = "mp.paid.amount.to",
        sender = Regex("^com\\.mercadopago\\."),
        title = Regex("^Pagaste\\s+$MONEY\\s+a (.+)$", RegexOption.IGNORE_CASE),
        direction = ImportDirection.Expense,
        amount = Extract(IngestField.Title, 1),
        payee = Extract(IngestField.Title, 2),
        hints = listOf("mercado pago"),
    ),
    // "Tu pago fue aprobado" / "Hiciste un pago por  $ 19200."
    IngestRule(
        id = "mp.paid.approved",
        sender = Regex("^com\\.mercadopago\\."),
        title = Regex("^Tu pago fue aprobado$", RegexOption.IGNORE_CASE),
        body = Regex("Hiciste un pago por\\s+$MONEY", RegexOption.IGNORE_CASE),
        direction = ImportDirection.Expense,
        amount = Extract(IngestField.Body, 1),
        hints = listOf("mercado pago"),
    ),
    // "Recibiste $ 15.000" / "Luciano Ramiro Veiga te envió dinero…"
    IngestRule(
        id = "mp.received.sender",
        sender = Regex("^com\\.mercadopago\\."),
        title = Regex("^Recibiste\\s+$MONEY$", RegexOption.IGNORE_CASE),
        body = Regex("^(.+?) te envió dinero", RegexOption.IGNORE_CASE),
        direction = ImportDirection.Income,
        amount = Extract(IngestField.Title, 1),
        payee = Extract(IngestField.Body, 1),
        hints = listOf("mercado pago"),
    ),
    // "Recibiste $ 10.000" / "De Mariano Nicolas Romero desde su cuenta…"
    IngestRule(
        id = "mp.received.from",
        sender = Regex("^com\\.mercadopago\\."),
        title = Regex("^Recibiste\\s+$MONEY$", RegexOption.IGNORE_CASE),
        body = Regex("^De (.+?) desde su cuenta", RegexOption.IGNORE_CASE),
        direction = ImportDirection.Income,
        amount = Extract(IngestField.Title, 1),
        payee = Extract(IngestField.Body, 1),
        hints = listOf("mercado pago"),
    ),
    // "Tu dinero ya está disponible" / "Ingresaste $ 200.000 y ya están…".
    // Money entering from somewhere else the user owns: almost always the far
    // half of a transfer, which is exactly what MatchRelation.Mirror is for.
    IngestRule(
        id = "mp.money.in",
        sender = Regex("^com\\.mercadopago\\."),
        title = Regex("^Tu dinero ya está disponible$", RegexOption.IGNORE_CASE),
        body = Regex("Ingresaste\\s+$MONEY", RegexOption.IGNORE_CASE),
        direction = ImportDirection.Income,
        amount = Extract(IngestField.Body, 1),
        hints = listOf("mercado pago"),
    ),
    // "Transferiste con éxito" / "Enviaste $ 200.000,00 a Fausto Fusse."
    IngestRule(
        id = "santander.transfer.out",
        sender = Regex("santander", RegexOption.IGNORE_CASE),
        title = Regex("^Transferiste con éxito$", RegexOption.IGNORE_CASE),
        body = Regex("Enviaste\\s+$MONEY\\s+a\\s+(.+?)\\.?$", RegexOption.IGNORE_CASE),
        direction = ImportDirection.Expense,
        amount = Extract(IngestField.Body, 1),
        payee = Extract(IngestField.Body, 2),
        hints = listOf("santander"),
    ),
)

/**
 * Email receipts. The bank mails the same event the push announced, with more
 * detail: the Santander "Aviso de consumo" carries `Monto`, `Comercio` and the
 * card's last four digits, so the merchant name is the real one rather than
 * the truncated push title.
 */
private val EMAIL_RULES = listOf(
    // "Monto $20.000,00 Cuotas 1 Comercio WWWBOCAJUNIORSCOMAR Fecha …",
    // with "Tarjeta Santander Visa Crédito terminada en 1500" above it.
    IngestRule(
        id = "santander.card.charge",
        sender = Regex("santander", RegexOption.IGNORE_CASE),
        // "Aviso de consumo", "Pagaste U$S21,23", "Aviso de débito automático":
        // three headlines over one receipt body.
        title = Regex("(consumo|pagaste|compra|débito|debito)", RegexOption.IGNORE_CASE),
        body = Regex("Monto\\s+$MONEY.*?Comercio\\s+(.+?)\\s+Fecha", RegexOption.IGNORE_CASE),
        direction = ImportDirection.Expense,
        amount = Extract(IngestField.Body, 1),
        payee = Extract(IngestField.Body, 2),
        hints = listOf("santander"),
        hintFrom = Extract(IngestField.Body, 3),
    ),
)

/** Card/account words the receipt itself names, to sharpen the hint list. */
private val CARD_HINT = Regex(
    "Tarjeta\\s+([A-Za-zÁÉÍÓÚáéíóúñ ]{3,40}?)\\s+terminada",
    RegexOption.IGNORE_CASE,
)

/**
 * A captured notification as a movement, or null when it is not one (the
 * overwhelming majority: promotions, chat messages, delivery updates).
 */
fun parseNotification(
    id: String,
    packageName: String,
    title: String,
    text: String,
    postTime: Long,
): IngestedMovement? = apply(
    rules = NOTIFICATION_RULES,
    source = EventSource.Notification,
    sourceRef = id,
    sender = packageName,
    title = title.trim(),
    body = text.trim(),
    date = postTime,
)

/**
 * An email receipt as a movement.
 *
 * Both bodies are offered because neither is reliable alone: the stored text
 * part is frequently the raw nested MIME (boundaries, headers, quoted-
 * printable) truncated at 10 kB, which on a bank mail is all stylesheet and
 * no receipt, while the sanitized HTML keeps the table that actually says
 * "Monto … Comercio …". Whichever yields a movement wins, HTML first.
 *
 * [subject] is RFC 2047-decoded here ("=?UTF-8?Q?Aviso_de_d=C3=A9bito?=").
 */
fun parseEmail(
    id: String,
    fromEmail: String,
    subject: String?,
    bodyText: String?,
    bodyHtml: String? = null,
    receivedAt: Long,
): IngestedMovement? {
    val title = decodeMimeHeader(subject.orEmpty())
    for (raw in listOfNotNull(bodyHtml, bodyText)) {
        val found = apply(
            rules = EMAIL_RULES,
            source = EventSource.Email,
            sourceRef = id,
            sender = fromEmail,
            title = title,
            body = emailPlainText(raw),
            date = receivedAt,
        )
        if (found != null) return found
    }
    return null
}

private fun apply(
    rules: List<IngestRule>,
    source: EventSource,
    sourceRef: String,
    sender: String,
    title: String,
    body: String,
    date: Long,
): IngestedMovement? {
    for (rule in rules) {
        if (rule.sender.find(sender) == null) continue
        val titleMatch = rule.title.find(title) ?: continue
        val bodyMatch = rule.body?.find(body)
        if (rule.body != null && bodyMatch == null) continue
        fun group(extract: Extract): String? {
            val match = if (extract.field == IngestField.Title) titleMatch else bodyMatch
            return match?.groupValues?.getOrNull(extract.group)?.trim()?.takeIf { it.isNotEmpty() }
        }
        val money = group(rule.amount)?.let { parseMoney(it) } ?: continue
        val card = CARD_HINT.find(body)?.groupValues?.getOrNull(1)?.trim()
        return IngestedMovement(
            ruleId = rule.id,
            source = source,
            sourceRef = sourceRef,
            date = date,
            amountMinor = money.amountMinor,
            commodity = money.commodity,
            payee = rule.payee?.let { group(it) }.orEmpty(),
            direction = rule.direction,
            accountHints = rule.hints + listOfNotNull(card),
        )
    }
    return null
}

/**
 * Argentine money text → minor units. Thousands are dots and decimals commas,
 * but banks are inconsistent ("$ 19200", "$8.000", "$ 200.000,00"), so the
 * separator closest to the end decides: a trailing group of exactly three
 * digits after a lone dot is thousands, anything else is a fraction.
 */
fun parseMoney(raw: String): ParsedMoney? {
    val commodity = if (
        raw.contains("U\$S", ignoreCase = true) ||
        raw.contains("US\$", ignoreCase = true) ||
        raw.contains("USD", ignoreCase = true)
    ) {
        "USD"
    } else {
        "ARS"
    }
    val digits = raw.filter { it.isDigit() || it == '.' || it == ',' }
    if (digits.none { it.isDigit() }) return null

    val lastDot = digits.lastIndexOf('.')
    val lastComma = digits.lastIndexOf(',')
    val decimalAt = when {
        lastComma > lastDot -> lastComma
        lastDot > lastComma -> {
            val tail = digits.length - lastDot - 1
            // "8.000" is eight thousand; "20.00" would be a fraction, but no
            // Argentine bank writes that, so only a 3-digit tail is grouping.
            if (tail == 3) -1 else lastDot
        }
        else -> -1
    }
    val whole = (if (decimalAt < 0) digits else digits.substring(0, decimalAt)).filter { it.isDigit() }
    val frac = (if (decimalAt < 0) "" else digits.substring(decimalAt + 1)).filter { it.isDigit() }
    if (whole.isEmpty() && frac.isEmpty()) return null
    val cents = (frac + "00").take(2)
    val minor = (whole.ifEmpty { "0" } + cents).toLongOrNull() ?: return null
    return ParsedMoney(minor, commodity)
}

/** RFC 2047 encoded-words, as they arrive in `emails.subject`. */
fun decodeMimeHeader(raw: String): String {
    if (!raw.contains("=?")) return raw
    val out = StringBuilder()
    var rest = raw
    while (true) {
        val start = rest.indexOf("=?")
        if (start < 0) break
        val parts = rest.drop(start + 2).split("?", limit = 4)
        if (parts.size < 4) break
        val (charset, encoding, payload) = Triple(parts[0], parts[1], parts[2])
        val end = rest.indexOf("?=", start + 2 + charset.length + encoding.length + payload.length)
        if (end < 0) break
        out.append(rest.substring(0, start))
        val decoded = when (encoding.uppercase()) {
            "B" -> decodeBase64Utf8(payload)
            "Q" -> decodeQuotedPrintable(payload.replace('_', ' '))
            else -> payload
        }
        out.append(decoded)
        rest = rest.substring(end + 2)
        // Adjacent encoded words are separated by whitespace that is not part
        // of the text ("=?..?= =?..?=" is one word split in two).
        if (rest.startsWith(" ") && rest.trimStart().startsWith("=?")) rest = rest.trimStart()
    }
    return (out.toString() + rest).trim()
}

/**
 * Email body → one line of readable text: quoted-printable decoded, tags and
 * entities removed, whitespace collapsed. The rules then read it as a
 * sentence, which is what makes "Monto … Comercio … Fecha" a single regex.
 */
fun emailPlainText(raw: String): String {
    val decoded = decodeQuotedPrintable(unwrapMime(raw))
    // Style and script bodies survive tag stripping as text, and a marketing
    // email is mostly stylesheet: leaving them in buries the receipt in
    // selectors and makes every rule scan kilobytes of noise.
    val noBlocks = decoded.replace(BLOCK, " ").replace(OPEN_BLOCK, " ")
    val noTags = noBlocks.replace(TAG, " ")
    val unescaped = ENTITIES.entries.fold(noTags) { acc, (k, v) -> acc.replace(k, v) }
    return unescaped.replace(WHITESPACE, " ").trim()
}

private val TAG = Regex("<[^>]*>")
// `[\s\S]` rather than `.` + DOT_MATCHES_ALL: that option is JVM-only and
// this file also compiles for JS (the web app runs the same rules).
private val BLOCK = Regex(
    "<(style|script)\\b[^>]*>[\\s\\S]*?</\\1\\s*>",
    RegexOption.IGNORE_CASE,
)
// A body stored truncated at 10 kB often ends *inside* a stylesheet, so the
// closing tag BLOCK needs never arrives and the whole CSS tail survives as
// text. 45 of the dev device's 132 mails are cut like this.
private val OPEN_BLOCK = Regex("<(style|script)\\b[^>]*>[\\s\\S]*$", RegexOption.IGNORE_CASE)
private val WHITESPACE = Regex("[\\s\\u00a0]+")
private val BOUNDARY_DECL = Regex("boundary=\"?([^\";\\r\\n]+)\"?", RegexOption.IGNORE_CASE)
// A `--token` line immediately followed by a MIME header is a boundary;
// requiring that next line is what keeps a line of dashes in a plain-text
// signature from being mistaken for one.
private val BOUNDARY_LINE = Regex(
    "^--(\\S{4,})[ \\t]*\\r?\\n(?=Content-)",
    setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE),
)
private val BLANK_LINE = Regex("\\r?\\n\\r?\\n")

/**
 * A stored `body_text` is frequently the whole MIME entity — boundaries,
 * headers and quoted-printable included — truncated at 10 kB, so anything
 * reading it (a rule, an embedding) measures `Content-Type: multipart/related`
 * instead of the receipt. Picks the richest part (html over plain) and decodes
 * its transfer encoding; returns [raw] unchanged when there is no multipart.
 */
fun unwrapMime(raw: String): String {
    // The declared boundary when the stored text still includes the entity
    // headers, and otherwise the first `--token` line: rows cut at 10 kB
    // frequently start *inside* the multipart, declaration already gone.
    val boundary = BOUNDARY_DECL.find(raw)?.groupValues?.get(1)
        ?: BOUNDARY_LINE.find(raw)?.groupValues?.get(1)
        ?: return raw
    var best = ""
    var bestRank = -1
    for (part in raw.split("--$boundary").drop(1)) {
        // Headers end at the first blank line; nested multiparts get unwrapped
        // again, which is how `related` inside `alternative` reaches the html.
        val split = BLANK_LINE.find(part)?.range?.first ?: continue
        val headers = part.substring(0, split).lowercase()
        if ("multipart/" in headers) {
            val inner = unwrapMime(part)
            if (inner != part && inner.length > best.length) {
                best = inner
                bestRank = 2
            }
            continue
        }
        val isHtml = "text/html" in headers
        val isPlain = "text/plain" in headers
        if (!isHtml && !isPlain) continue
        var body = part.substring(split).trim()
        if ("base64" in headers) body = decodeBase64Utf8(body)
        val rank = if (isHtml) 2 else 1
        if (rank > bestRank) {
            best = body
            bestRank = rank
        }
    }
    return best.ifEmpty { raw }
}
private val ENTITIES = mapOf(
    "&nbsp;" to " ", "&amp;" to "&", "&lt;" to "<", "&gt;" to ">",
    "&quot;" to "\"", "&#39;" to "'", "&aacute;" to "á", "&eacute;" to "é",
    "&iacute;" to "í", "&oacute;" to "ó", "&uacute;" to "ú", "&ntilde;" to "ñ",
)

/** Quoted-printable: `=XX` bytes and `=`-at-end-of-line soft breaks. */
fun decodeQuotedPrintable(raw: String): String {
    if (!raw.contains('=')) return raw
    val bytes = ArrayList<Byte>(raw.length)
    var i = 0
    while (i < raw.length) {
        val ch = raw[i]
        if (ch == '=' && i + 1 < raw.length) {
            val next = raw[i + 1]
            // A literal '=' is always encoded as "=3D", so a bare one before
            // any whitespace is a soft line break — including the space a
            // newline may have been flattened into upstream. Without this the
            // break lands mid-word and "Comercio" reads as "Come= rcio".
            if (next.isWhitespace()) {
                // Exactly one break: "\r\n" counts as one, and any other single
                // whitespace is a newline something upstream flattened. Eating
                // more would glue "Comercio=\r\n COTO" into one word.
                i += if (next == '\r' && raw.getOrNull(i + 2) == '\n') 3 else 2
                continue
            }
            val hex = raw.substring(i + 1, minOf(i + 3, raw.length))
            val value = hex.toIntOrNull(16)
            if (hex.length == 2 && value != null) {
                bytes.add(value.toByte())
                i += 3
                continue
            }
        }
        for (b in ch.toString().encodeToByteArray()) bytes.add(b)
        i++
    }
    return bytes.toByteArray().decodeToString()
}

private const val BASE64_ALPHABET =
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

private fun decodeBase64Utf8(raw: String): String {
    val clean = raw.filter { it in BASE64_ALPHABET }
    val bytes = ArrayList<Byte>(clean.length * 3 / 4 + 3)
    var buffer = 0
    var bits = 0
    for (ch in clean) {
        buffer = (buffer shl 6) or BASE64_ALPHABET.indexOf(ch)
        bits += 6
        if (bits >= 8) {
            bits -= 8
            bytes.add(((buffer shr bits) and 0xFF).toByte())
        }
    }
    return bytes.toByteArray().decodeToString()
}

/**
 * Best account for a movement's [hints], or null when the tree cannot answer
 * it confidently.
 *
 * Deliberately conservative: an app name is a weak signal ("Santander" covers
 * a peso account, a dollar account and two cards), so the commodity has to
 * agree and the winner has to be alone. Guessing wrong here files a charge
 * against the wrong account, which is worse than asking.
 */
fun resolveAccountHint(
    hints: List<String>,
    commodity: String,
    accounts: List<Account>,
): String? {
    if (hints.isEmpty()) return null
    val wanted = hints.flatMap { normalizePayee(it).split(' ') }.filter { it.length >= 3 }.toSet()
    if (wanted.isEmpty()) return null
    val scored = accounts
        .filter { it.type == AccountType.Asset || it.type == AccountType.Liability }
        .map { account ->
            val tokens = normalizePayee(account.name).split(' ').filter { it.length >= 3 }.toSet()
            var score = tokens.count { it in wanted } * 10
            // "Santander Dolares" versus "Santander Pesos": the amount's
            // currency is the tiebreak the name alone cannot give.
            val currencyWord = if (commodity == "USD") "dolar" else "peso"
            if (tokens.any { it.startsWith(currencyWord) }) score += 3
            account.id to score
        }
        .filter { it.second > 0 }
        .sortedByDescending { it.second }
    val best = scored.firstOrNull() ?: return null
    val runnerUp = scored.getOrNull(1)?.second ?: 0
    return if (best.second > runnerUp) best.first else null
}

/** Colon-joined path of every account, by id ("Activos:Banco:Santander"). */
fun accountPaths(accounts: List<Account>): Map<String, String> {
    val byId = accounts.associateBy { it.id }
    fun pathOf(id: String, seen: Set<String> = emptySet()): String {
        val account = byId[id] ?: return ""
        val parent = account.parentId
        // A cycle can only come from corrupt data, but it must not hang.
        return if (parent == null || parent in seen) account.name
        else "${pathOf(parent, seen + id)}:${account.name}"
    }
    return accounts.associate { it.id to pathOf(it.id) }
}

/**
 * Recognized movements → review rows: resolves each one's account hint against
 * the tree and drops the refs already linked to a transaction.
 *
 * Pure so both `IngestRepository` (which fetches the rows from the database)
 * and the web app in `app/web` (which fetches them over HTTP and
 * calls this through the Kotlin/JS bridge) produce the same inbox.
 *
 * [movements] pairs each movement with the headline of the message it came
 * from, which stands in as the payee when the bank names no merchant.
 */
fun buildInbox(
    movements: List<Pair<IngestedMovement, String>>,
    accounts: List<Account>,
    known: Set<String>,
): List<InboxCandidate> {
    val paths = accountPaths(accounts)
    return movements
        .filterNot { it.first.sourceRef in known }
        .sortedByDescending { it.first.date }
        .map { (movement, title) ->
            val accountId = resolveAccountHint(movement.accountHints, movement.commodity, accounts)
            InboxCandidate(
                candidate = ImportCandidate(
                    date = movement.date,
                    // "Tu pago fue aprobado" names no merchant, so the
                    // message's own headline stands in: it is the bank's
                    // wording, not a string this layer invented, and a
                    // blank payee would leave the row invalid to save.
                    payee = movement.payee.ifBlank { title },
                    note = null,
                    commodity = movement.commodity,
                    direction = movement.direction,
                    accountId = accountId,
                    accountPath = accountId?.let { paths[it] },
                    splits = listOf(ImportSplit(movement.amountMinor, null, null)),
                ),
                kind = movement.source,
                ref = movement.sourceRef,
                ruleId = movement.ruleId,
                title = title,
            )
        }
}
