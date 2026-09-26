package ar.fausto.weil

/*
 * Interactive Brokers by file (plans/inversiones-brokers.md, phase 3): a Flex
 * Query report (the `weil` query, XML, Flex v3) → a [BrokerBatch]. Pure and
 * without I/O, like iolBatch: the repository reads the bytes, this reads the
 * report, planBrokerImport decides.
 *
 * Shapes confirmed on real reports: the TTWO buy (two fills of one order,
 * commission capitalized, cost basis 100,98), the cash report and the open
 * position (trade-ttwo.xml), and the deposit advance netted by
 * clientReference (backfill-deposit.xml, rebuilt from the recorded figures).
 * Dividends, withholding and forex trades follow IBKR's documented field
 * names but no real row has been seen yet.
 */

/** One element of a Flex report: its tag and attributes (the format has no text content). */
data class FlexElement(val tag: String, val attrs: Map<String, String>) {
    operator fun get(name: String): String = attrs[name].orEmpty()
    fun decimal(name: String): Decimal? = attrs[name]?.takeIf { it.isNotBlank() }?.let { Decimal.parse(it) }
}

/** A Flex statement: the period it covers and every element inside it, in order. */
data class FlexStatement(
    val accountId: String,
    /** "yyyy-MM-dd". */
    val fromDate: String,
    val toDate: String,
    val elements: List<FlexElement>,
) {
    fun all(tag: String): List<FlexElement> = elements.filter { it.tag == tag }
}

class FlexParseException(message: String) : Exception(message)

/** True when [bytes] look like a Flex report, whatever the file was called or typed as. */
fun isFlexReport(bytes: ByteArray): Boolean =
    bytes.copyOf(minOf(bytes.size, 512)).decodeToString().contains("<FlexQueryResponse")

private val ELEMENT = Regex("""<([A-Za-z][A-Za-z0-9]*)((?:\s+[A-Za-z_:][\w:.-]*\s*=\s*"[^"]*")*)\s*/?>""")
private val ATTRIBUTE = Regex("""([A-Za-z_:][\w:.-]*)\s*=\s*"([^"]*)"""")

/**
 * Reads every statement in a Flex report. The XML is flat and all
 * attributes, so a scanner over start tags is the whole parser: closing
 * tags, comments and the prolog are skipped, entities decoded.
 */
fun parseFlexReport(xml: String): List<FlexStatement> {
    if (!xml.contains("<FlexQueryResponse")) throw FlexParseException("not a Flex report")
    val statements = mutableListOf<FlexStatement>()
    var current: Pair<FlexElement, MutableList<FlexElement>>? = null
    // [\s\S] rather than DOT_MATCHES_ALL, which the JS target lacks.
    val body = xml.replace(Regex("""<!--[\s\S]*?-->"""), "")
    for (match in ELEMENT.findAll(body)) {
        val tag = match.groupValues[1]
        val attrs = ATTRIBUTE.findAll(match.groupValues[2]).associate { it.groupValues[1] to unescape(it.groupValues[2]) }
        val element = FlexElement(tag, attrs)
        if (tag == "FlexStatement") {
            current?.let { (head, items) -> statements += statementOf(head, items) }
            current = element to mutableListOf()
        } else {
            current?.second?.add(element)
        }
    }
    current?.let { (head, items) -> statements += statementOf(head, items) }
    // IBKR answers errors in the same envelope: <FlexStatementResponse> with
    // an <ErrorMessage>, or a statement-less response.
    if (statements.isEmpty()) throw FlexParseException("the report has no statement")
    return statements
}

private fun statementOf(head: FlexElement, items: List<FlexElement>) =
    FlexStatement(head["accountId"], flexDate(head["fromDate"]), flexDate(head["toDate"]), items)

private fun unescape(text: String): String =
    if ('&' !in text) text
    else text.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

/** "20260925" → "2026-09-25". */
internal fun flexDate(text: String): String {
    val d = text.substringBefore(';').filter { it.isDigit() }
    if (d.length != 8) throw FlexParseException("unreadable date '$text'")
    return "${d.substring(0, 4)}-${d.substring(4, 6)}-${d.substring(6, 8)}"
}

/**
 * A Flex `dateTime` → epoch ms and whether it carries a time. Times are in
 * US Eastern (the Flex default); a bare date (cash transactions) is that
 * day at midnight in Argentina, the convention every date-only row in the
 * ledger uses ([iolTime]).
 */
internal fun flexTime(text: String): Pair<Long, Boolean> {
    val date = flexDate(text)
    val time = text.substringAfter(';', "").filter { it.isDigit() }
    if (time.length < 4) return (iolTime(date) ?: throw FlexParseException("unreadable date '$text'")) to false
    val hh = time.substring(0, 2)
    val mm = time.substring(2, 4)
    val ss = time.substring(4).padEnd(2, '0').take(2)
    // iolTime reads a wall time at UTC−3; Eastern is UTC−4 (EDT) or −5 (EST).
    val atArt = iolTime("${date}T$hh:$mm:$ss") ?: throw FlexParseException("unreadable time '$text'")
    val hoursBehindArt = if (isUsDaylightTime(date, hh.toInt())) 1 else 2
    return atArt + hoursBehindArt * 3_600_000L to true
}

/** US DST: second Sunday of March 02:00 to first Sunday of November 02:00, local time. */
private fun isUsDaylightTime(date: String, hour: Int): Boolean {
    val (y, m, d) = date.split('-').map { it.toInt() }
    fun nthSunday(month: Int, n: Int): Int {
        // Day of week of the 1st (0 = Sunday), from the epoch day (1970-01-01 was a Thursday).
        val first = (iolTime("$y-${month.toString().padStart(2, '0')}-01")!! + 3 * 3_600_000L) / 86_400_000L
        val dow = ((first + 4) % 7).toInt()
        return 1 + (7 - dow) % 7 + (n - 1) * 7
    }
    return when (m) {
        in 4..10 -> true
        3 -> nthSunday(3, 2).let { start -> d > start || (d == start && hour >= 2) }
        11 -> nthSunday(11, 1).let { end -> d < end || (d == end && hour < 2) }
        else -> false
    }
}

const val IBKR_PROVIDER = "ibkr"

/**
 * One statement → a batch: trades (fills of one order on one day merged),
 * cash transactions (deposits, dividends with their withholding, interest),
 * the instruments, the mark prices and the snapshot (cash report + open
 * positions). What it can't translate becomes a note, never a guess.
 */
fun ibkrBatch(statement: FlexStatement): BrokerBatch {
    val notes = mutableListOf<PlanIssue>()
    val instruments = linkedMapOf<String, InstrumentInfo>()

    fun instrumentOf(e: FlexElement): String? {
        val info = ibkrInstrument(e) ?: return null
        instruments.getOrPut(info.id) { info }
        return info.id
    }
    statement.all("SecurityInfo").forEach { instrumentOf(it) }

    val events = mutableListOf<BrokerEvent>()
    events += trades(statement.all("Trade"), ::instrumentOf, notes)
    events += cashTransactions(statement.all("CashTransaction"), notes)

    val positions = statement.all("OpenPosition").mapNotNull { p ->
        val id = instrumentOf(p) ?: return@mapNotNull null.also {
            notes += PlanIssue(null, "position in ${p["symbol"]} (${p["assetCategory"]}) is not supported")
        }
        SnapshotPosition(
            instrument = id,
            quantity = p.decimal("position") ?: return@mapNotNull null,
            price = p.decimal("markPrice"),
            cost = p.decimal("costBasisMoney"),
            costCommodity = p["currency"].takeIf { it.isNotBlank() },
        )
    }
    val prices = statement.all("OpenPosition").mapNotNull { p ->
        val id = instruments.values.firstOrNull { it.conid == p["conid"] }?.id ?: return@mapNotNull null
        val price = p.decimal("markPrice") ?: return@mapNotNull null
        // A mark is the close: 16:00 in New York.
        PriceQuote(id, p["currency"], flexTime("${p["reportDate"]};160000").first, price, IBKR_PROVIDER)
    }
    // One row per currency plus a BASE_SUMMARY that adds them up in the
    // base currency: counting it too would double the cash.
    val cash = statement.all("CashReportCurrency")
        .filter { it["currency"].length == 3 && it["levelOfDetail"] != "BaseCurrency" }
        .mapNotNull { row -> row.decimal("endingCash")?.let { row["currency"] to it } }
        .toMap()
    val snapshot = BrokerSnapshot(flexTime("${statement.toDate.replace("-", "")};235959").first, cash, positions)

    return BrokerBatch(
        IBKR_PROVIDER, events.sortedBy { it.at }, snapshot, instruments.values.toList(), prices, notes,
        since = iolTime(statement.fromDate),
    )
}

/** The currencies a statement holds cash in, for the accounts a connection creates. */
fun ibkrCurrencies(statement: FlexStatement): List<String> =
    statement.all("CashReportCurrency").map { it["currency"] }
        .filter { it.length == 3 && it.all { c -> c.isUpperCase() } }
        .plus(statement.all("Trade").map { it["currency"] })
        .filter { it.isNotBlank() }
        .distinct()
        .ifEmpty { listOf("USD") }

/**
 * An IBKR security as a commodity: market-scoped id like IOL's
 * (`NASDAQ:TTWO`), so the same instrument at two brokers is one commodity.
 * Stocks and ETFs take 4 decimals (IBKR sells fractions: 0,4939 TTWO),
 * bonds quote per 100 face value. Anything else (options, futures) is not
 * modelled yet and comes back null.
 */
internal fun ibkrInstrument(e: FlexElement): InstrumentInfo? {
    val symbol = e["symbol"].takeIf { it.isNotBlank() } ?: return null
    val market = e["listingExchange"].ifBlank { e["exchange"] }.ifBlank { "IBKR" }.uppercase()
    val (kind, scale, per) = when (e["assetCategory"]) {
        "STK" -> Triple(if (e["subCategory"] == "ETF") "etf" else "stock", 4, 1)
        "BOND" -> Triple("bond", 0, 100)
        else -> return null
    }
    return InstrumentInfo(
        id = "$market:$symbol",
        symbol = symbol,
        name = e["description"].takeIf { it.isNotBlank() }?.let(::titleCase),
        kind = kind,
        scale = scale,
        pricePer = per,
        quoteCommodity = e["currency"].takeIf { it.isNotBlank() },
        isin = e["isin"].takeIf { it.isNotBlank() },
        conid = e["conid"].takeIf { it.isNotBlank() },
    )
}

/** "TAKE-TWO INTERACTIVE SOFTWRE" → "Take-Two Interactive Softwre". */
private fun titleCase(text: String): String =
    text.lowercase().split(' ').joinToString(" ") { word ->
        word.split('-').joinToString("-") { it.replaceFirstChar { c -> c.uppercaseChar() } }
    }

/**
 * Executions → trades. The fills of one order on one day are one trade (the
 * TTWO buy arrived as 0,0002 + 0,4937): one row in the ledger per decision
 * the user made. Keyed by order and day rather than by order alone, so a
 * good-till-cancelled order that fills again tomorrow is a new trade and not
 * a ref already booked.
 */
private fun trades(
    rows: List<FlexElement>,
    instrumentOf: (FlexElement) -> String?,
    notes: MutableList<PlanIssue>,
): List<BrokerEvent> {
    val executions = rows.filter { it["levelOfDetail"].ifBlank { "EXECUTION" } == "EXECUTION" }
    return executions.groupBy { (it["ibOrderID"].ifBlank { it["transactionID"] }) to it["tradeDate"] }
        .mapNotNull { (key, fills) ->
            val first = fills.first()
            val ref = "${key.first}-${key.second}"
            val currency = first["currency"]
            fun sum(name: String) = fills.fold(Decimal.ZERO) { acc, f -> acc + (f.decimal(name) ?: Decimal.ZERO) }
            val at = flexTime(first["dateTime"].ifBlank { first["tradeDate"] })
            if (first["assetCategory"] == "CASH") return@mapNotNull forexTrade(ref, at, first, sum("quantity"), sum("proceeds"), sum("ibCommission"), notes)
            val instrument = instrumentOf(first) ?: return@mapNotNull null.also {
                notes += PlanIssue(brokerRef(IBKR_PROVIDER, ref), "trade in ${first["symbol"]} (${first["assetCategory"]}) is not supported")
            }
            val quantity = sum("quantity")
            val proceeds = sum("proceeds")
            // Commission and transfer taxes both come negative.
            val sameCurrency = fills.all { it["ibCommissionCurrency"].ifBlank { currency } == currency }
            val commission = -sum("ibCommission")
            val fees = -sum("taxes") + (if (sameCurrency) commission else Decimal.ZERO)
            val foreignFees = if (sameCurrency) emptyMap() else fills.groupBy { it["ibCommissionCurrency"] }
                .mapValues { (_, g) -> g.fold(Decimal.ZERO) { acc, f -> acc - (f.decimal("ibCommission") ?: Decimal.ZERO) } }
            // A sale's basis, as IBKR matched it (FIFO): what came in minus
            // what it says was realized. Makes the ledger's gain its gain.
            val basis = if (quantity.signum < 0) {
                fills.fold(Decimal.ZERO) { acc, f ->
                    acc + (f.decimal("netCash") ?: Decimal.ZERO) - (f.decimal("fifoPnlRealized") ?: Decimal.ZERO)
                }
            } else null
            val verb = if (quantity.signum > 0) "Compra" else "Venta"
            BrokerEvent.Trade(
                ref = ref, at = at.first, timeKnown = at.second,
                description = "$verb ${first["symbol"]}",
                instrument = instrument,
                quantity = quantity,
                gross = proceeds.abs(),
                cashCommodity = currency,
                fees = fees,
                costBasis = basis,
                foreignFees = foreignFees,
            )
        }
}

/**
 * A forex trade (`EUR.USD`): [quantity] of the base currency bought (+) or
 * sold (−) for [proceeds] of the quote currency. The commission is taken in
 * whatever currency was paid; a commission in the other one is a note.
 */
private fun forexTrade(
    ref: String,
    at: Pair<Long, Boolean>,
    row: FlexElement,
    quantity: Decimal,
    proceeds: Decimal,
    commission: Decimal,
    notes: MutableList<PlanIssue>,
): BrokerEvent? {
    val base = row["symbol"].substringBefore('.')
    val quote = row["currency"]
    if (base.length != 3 || quote.length != 3) {
        notes += PlanIssue(brokerRef(IBKR_PROVIDER, ref), "unreadable forex pair ${row["symbol"]}")
        return null
    }
    val buying = quantity.signum > 0
    val from = if (buying) proceeds.abs() else quantity.abs()
    val fromCommodity = if (buying) quote else base
    val to = if (buying) quantity else proceeds.abs()
    val toCommodity = if (buying) base else quote
    val feeCurrency = row["ibCommissionCurrency"].ifBlank { quote }
    if (feeCurrency != fromCommodity && !commission.isZero) {
        notes += PlanIssue(brokerRef(IBKR_PROVIDER, ref), "forex commission in $feeCurrency, not in $fromCommodity")
        return null
    }
    return BrokerEvent.FxConversion(
        ref = ref, at = at.first, timeKnown = at.second,
        description = "Cambio $fromCommodity → $toCommodity",
        from = from, fromCommodity = fromCommodity,
        to = to, toCommodity = toCommodity,
        fees = commission.abs(),
    )
}

/**
 * Cash transactions → transfers and income.
 *
 * Deposit advances: IBKR credits part of a deposit early and cancels it
 * when the real receipt lands, both rows sharing a `clientReference`. A
 * group that nets to zero is dropped; importing it leaves two junk rows
 * and a transfer Reconcile could pair with anything. A group that doesn't
 * net (the cancellation is in a later report) goes in row by row, so the
 * next report's cancellation completes it.
 *
 * Dividends pair with the withholding of the same security and day.
 */
private fun cashTransactions(
    rows: List<FlexElement>,
    notes: MutableList<PlanIssue>,
): List<BrokerEvent> {
    val detail = rows.filter { it["levelOfDetail"].ifBlank { "DETAIL" } == "DETAIL" }
    val netted = detail.filter { it["type"] == "Deposits/Withdrawals" && it["clientReference"].isNotBlank() }
        .groupBy { it["clientReference"] to it["currency"] }
        .filterValues { group -> group.size > 1 && group.fold(Decimal.ZERO) { acc, r -> acc + (r.decimal("amount") ?: Decimal.ZERO) }.isZero }
        .values.flatten().toSet()
    val withholding = detail.filter { it["type"] == "Withholding Tax" }.toMutableList()
    val events = mutableListOf<BrokerEvent>()
    for (row in detail) {
        if (row in netted) continue
        val ref = row["transactionID"]
        if (ref.isBlank()) continue
        val amount = row.decimal("amount") ?: continue
        val currency = row["currency"]
        val (at, timeKnown) = flexTime(row["dateTime"].ifBlank { row["reportDate"] })
        when (row["type"]) {
            "Deposits/Withdrawals" -> events += BrokerEvent.CashTransfer(
                ref, at, timeKnown,
                if (amount.signum > 0) "Depósito IBKR" else "Extracción IBKR",
                amount, currency,
            )
            "Dividends", "Payment In Lieu Of Dividends" -> {
                val tax = withholding.firstOrNull { it["conid"] == row["conid"] && it["currency"] == currency && flexDate(it["dateTime"].ifBlank { it["reportDate"] }) == flexDate(row["dateTime"].ifBlank { row["reportDate"] }) }
                tax?.let { withholding.remove(it) }
                events += BrokerEvent.Income(
                    ref, at, timeKnown, "Dividendo ${row["symbol"]}".trim(), IncomeKind.Dividend,
                    amount, currency,
                    tax = tax?.decimal("amount")?.abs() ?: Decimal.ZERO,
                )
            }
            "Broker Interest Received", "Bond Interest Received" -> events += BrokerEvent.Income(
                ref, at, timeKnown,
                if (row["symbol"].isBlank()) "Intereses IBKR" else "Renta ${row["symbol"]}",
                IncomeKind.Interest, amount, currency,
            )
            "Withholding Tax" -> Unit // paired above, or reported below
            else -> notes += PlanIssue(brokerRef(IBKR_PROVIDER, ref), "cash transaction '${row["type"]}' (${row["description"]}) is not supported")
        }
    }
    // A withholding with no dividend beside it (a refund, a correction).
    withholding.forEach { notes += PlanIssue(brokerRef(IBKR_PROVIDER, it["transactionID"]), "withholding without its dividend: ${it["description"]}") }
    return events
}
