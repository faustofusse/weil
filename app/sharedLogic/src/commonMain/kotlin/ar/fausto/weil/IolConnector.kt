package ar.fausto.weil

/*
 * IOL → BrokerBatch (plans/inversiones-brokers.md, phase 4). Pure: the
 * repository fetches, this translates, planBrokerImport decides. Every rule
 * here comes from the real account's history, not from IOL's docs, which
 * describe none of it:
 *
 *  - Compra/Venta/Suscripción FCI/Rescate FCI are trades; their currency
 *    and fees live only in the per-order detail, and a dollar trade's market
 *    fee is billed in pesos (a foreign fee).
 *  - A buy of a bond in pesos followed, within days and for the same
 *    quantity, by a sale of its "D" (MEP) or "C" (cable) ticker in dollars
 *    is not two trades: it is buying dollars. It becomes one FxConversion.
 *  - Pago de Renta / Pago de Amortización arrive as pairs: a row with the
 *    cash ("AO27 US$", the currency in the symbol) and a row without an
 *    amount (for an amortization, the securities leaving). The cash row is
 *    the event; the empty companion of an amortization makes it a
 *    whole-position redemption.
 *  - Timestamps have no zone; they are Argentina's (UTC−3, no DST).
 */

/** Everything one IOL sync fetched. */
data class IolFetch(
    /** When the snapshot was taken (epoch ms). */
    val at: Long,
    val accountState: IolAccountState?,
    val portfolios: List<IolPortfolio>,
    val operations: List<IolOperation>,
    /** Order detail by number, for the trades that are new to the ledger. */
    val details: Map<Long, IolOperationDetail>,
    /** Instrument lookups by symbol, for the symbols no portfolio describes. */
    val instruments: Map<String, IolInstrument> = emptyMap(),
)

const val IOL_PROVIDER = "iol"

private val TRADE_TYPES = setOf("compra", "venta", "suscripción fci", "rescate fci")
private const val DAY_MS = 24L * 60 * 60 * 1000

/**
 * The orders whose detail the batch needs: trades that are finished and new.
 * An order booked as half of a MEP pair is known under the pair's ref
 * ("iol:140633678+140633706"), so the refs are split on '+' before
 * comparing; without that every sync re-fetched both halves of every pair.
 */
fun iolDetailsNeeded(operations: List<IolOperation>, knownRefs: Set<String>): List<Long> {
    val prefix = "$IOL_PROVIDER:"
    val knownOrders = knownRefs.filter { it.startsWith(prefix) }
        .flatMap { it.removePrefix(prefix).split('+') }
        .toSet()
    return operations.filter { it.isFinished() && it.tipo.lowercase() in TRADE_TYPES }
        .map { it.numero }
        .filter { it.toString() !in knownOrders }
}

/** Symbols traded but absent from every portfolio: their type has to be looked up. */
fun iolSymbolsNeeded(operations: List<IolOperation>, portfolios: List<IolPortfolio>): List<Pair<String, String>> {
    val described = portfolios.flatMap { p -> p.activos.map { it.titulo.simbolo.uppercase() } }.toSet()
    return operations.filter { it.isFinished() && it.tipo.lowercase() in TRADE_TYPES }
        .map { (it.mercado ?: "bcba") to it.simbolo }
        .distinctBy { it.second.uppercase() }
        .filter { it.second.uppercase() !in described }
}

fun iolBatch(fetch: IolFetch): BrokerBatch {
    val notes = mutableListOf<PlanIssue>()
    val instruments = mutableMapOf<String, InstrumentInfo>()
    val titles = fetch.instruments.mapKeys { it.key.uppercase() } +
        fetch.portfolios.flatMap { p -> p.activos.map { it.titulo.simbolo.uppercase() to it.titulo } }

    fun instrumentOf(symbol: String, market: String?): InstrumentInfo {
        val title = titles[symbol.uppercase()]
        val info = iolInstrumentInfo(symbol, market ?: title?.mercado, title)
        instruments.getOrPut(info.id) { info }
        return info
    }

    val finished = fetch.operations.filter { it.isFinished() }
    val events = mutableListOf<BrokerEvent>()

    // --- MEP / cable pairs -------------------------------------------------
    val paired = mutableSetOf<Long>()
    val trades = finished.filter { it.tipo.lowercase() in TRADE_TYPES }
    for (sale in trades.filter { it.tipo.equals("Venta", true) }) {
        val detail = fetch.details[sale.numero] ?: continue
        if (iolCurrency(detail.moneda) != "USD") continue
        val symbol = sale.simbolo.uppercase()
        val flavour = symbol.lastOrNull()
        if (symbol.length < 4 || (flavour != 'D' && flavour != 'C')) continue
        val base = symbol.dropLast(1)
        val saleAt = iolTime(sale.fechaOperada ?: sale.fechaOrden) ?: continue
        val buy = trades.firstOrNull { candidate ->
            candidate.numero !in paired &&
                candidate.tipo.equals("Compra", true) &&
                candidate.simbolo.equals(base, true) &&
                candidate.cantidadOperada == sale.cantidadOperada &&
                iolCurrency(fetch.details[candidate.numero]?.moneda) == "ARS" &&
                iolTime(candidate.fechaOperada ?: candidate.fechaOrden)
                    ?.let { it <= saleAt && saleAt - it <= 5 * DAY_MS } == true
        } ?: continue
        val buyDetail = fetch.details.getValue(buy.numero)
        val pesos = buy.montoOperado ?: continue
        val dollars = sale.montoOperado ?: continue
        val pesoFees = (buyDetail.arancelesARS ?: Decimal.ZERO) + (detail.arancelesARS ?: Decimal.ZERO)
        val dollarFees = (buyDetail.arancelesUSD ?: Decimal.ZERO) + (detail.arancelesUSD ?: Decimal.ZERO)
        paired += buy.numero
        paired += sale.numero
        events += BrokerEvent.FxConversion(
            ref = "${buy.numero}+${sale.numero}",
            at = saleAt,
            timeKnown = true,
            description = (if (flavour == 'C') "Dólar cable" else "Dólar MEP") + " ($base)",
            from = pesos + pesoFees,
            fromCommodity = "ARS",
            // Dollar fees come out of what arrives: they are part of the rate.
            to = dollars - dollarFees,
            toCommodity = "USD",
            fees = pesoFees,
        )
    }

    // --- trades ------------------------------------------------------------
    for (op in trades) {
        if (op.numero in paired) continue
        val ref = op.numero.toString()
        val detail = fetch.details[op.numero]
        if (detail == null) {
            // Known to the ledger already (no detail fetched) or the fetch
            // failed: either way nothing to plan, and the planner would skip
            // a known ref anyway.
            continue
        }
        val cash = iolCurrency(detail.moneda)
        val quantity = op.cantidadOperada
        val gross = op.montoOperado
        val at = iolTime(op.fechaOperada ?: detail.fechaOperado ?: op.fechaOrden)
        if (cash == null || quantity == null || gross == null || at == null) {
            notes += PlanIssue(brokerRef(IOL_PROVIDER, ref), "IOL order ${op.numero} lacks currency, quantity, amount or date")
            continue
        }
        val kind = op.tipo.lowercase()
        val selling = kind == "venta" || kind == "rescate fci"
        val info = instrumentOf(op.simbolo, op.mercado)
        val fees = mapOf("ARS" to (detail.arancelesARS ?: Decimal.ZERO), "USD" to (detail.arancelesUSD ?: Decimal.ZERO))
        events += BrokerEvent.Trade(
            ref = ref,
            at = at,
            timeKnown = true,
            description = "${iolVerb(kind)} ${op.simbolo}",
            instrument = info.id,
            quantity = if (selling) -quantity else quantity,
            gross = gross,
            cashCommodity = cash,
            fees = fees[cash] ?: Decimal.ZERO,
            foreignFees = fees.filterKeys { it != cash }.filterValues { !it.isZero },
        )
    }

    // --- coupons, dividends, amortizations ------------------------------------
    val payments = finished.filter { it.tipo.startsWith("Pago de", ignoreCase = true) }
    for (op in payments) {
        val amount = op.montoOperado ?: continue // the empty half of a pair
        val ref = op.numero.toString()
        val at = iolTime(op.fechaOperada ?: op.fechaOrden)
        if (at == null) {
            notes += PlanIssue(brokerRef(IOL_PROVIDER, ref), "IOL payment ${op.numero} has no readable date")
            continue
        }
        val (symbol, currency) = iolPaymentSymbol(op.simbolo)
        val tipo = op.tipo.lowercase()
        when {
            "renta" in tipo || "dividendo" in tipo -> events += BrokerEvent.Income(
                ref = ref,
                at = at,
                timeKnown = true,
                description = "${if ("dividendo" in tipo) "Dividendo" else "Renta"} $symbol",
                kind = if ("dividendo" in tipo) IncomeKind.Dividend else IncomeKind.Interest,
                gross = amount,
                cashCommodity = currency,
                instrument = instrumentOf(symbol, op.mercado).id,
            )
            "amortización" in tipo || "amortizacion" in tipo -> {
                val opAt = at
                val companion = payments.any { other ->
                    other.montoOperado == null &&
                        other.tipo.equals(op.tipo, true) &&
                        iolPaymentSymbol(other.simbolo).first.equals(symbol, true) &&
                        iolTime(other.fechaOperada ?: other.fechaOrden)?.let { kotlin.math.abs(it - opAt) <= DAY_MS } == true
                }
                if (!companion) {
                    // Cash with no securities leaving: a partial amortization,
                    // which lowers the residual value instead of the count.
                    // Booking it needs the residual, which IOL doesn't give.
                    notes += PlanIssue(
                        brokerRef(IOL_PROVIDER, ref),
                        "partial amortization of $symbol (${amount.toPlainString()} $currency) is not supported yet",
                    )
                } else {
                    events += BrokerEvent.Principal(
                        ref = ref,
                        at = at,
                        timeKnown = true,
                        description = "Amortización $symbol",
                        instrument = instrumentOf(symbol, op.mercado).id,
                        quantity = null,
                        cash = amount,
                        cashCommodity = currency,
                    )
                }
            }
            else -> notes += PlanIssue(brokerRef(IOL_PROVIDER, ref), "unknown IOL payment type '${op.tipo}'")
        }
    }

    // --- snapshot ------------------------------------------------------------
    val snapshot = fetch.accountState?.let { state ->
        val cash = mutableMapOf<String, Decimal>()
        for (account in state.cuentas) {
            val currency = iolCurrency(account.moneda) ?: continue
            // Argentina's dollar account and the US one are both dollars,
            // and both land in the one dollar cash account.
            cash[currency] = (cash[currency] ?: Decimal.ZERO) + (account.saldo ?: Decimal.ZERO)
        }
        val positions = fetch.portfolios.flatMap { it.activos }.mapNotNull { position ->
            val quantity = position.cantidad ?: return@mapNotNull null
            val info = instrumentOf(position.titulo.simbolo, position.titulo.mercado)
            val quote = iolCurrency(position.titulo.moneda)
            SnapshotPosition(
                instrument = info.id,
                quantity = quantity,
                price = position.ultimoPrecio,
                // ppc is per the same face value as the price (per 100 VN
                // for bonds), so the total cost takes the same divisor.
                cost = position.ppc?.let { (it * quantity).movePointLeft(pricePerDigits(info.pricePer)) },
                costCommodity = quote,
            )
        }
        BrokerSnapshot(fetch.at, cash, positions)
    }

    return BrokerBatch(
        provider = IOL_PROVIDER,
        events = events,
        snapshot = snapshot,
        instruments = instruments.values.sortedBy { it.id },
        notes = notes,
    )
}

/** Commodity, kind, scale and face value of an IOL symbol. */
fun iolInstrumentInfo(symbol: String, market: String?, title: IolInstrument?): InstrumentInfo {
    val tipo = title?.tipo.orEmpty().lowercase()
    val (kind, scale, pricePer) = when {
        "fondo" in tipo -> Triple("fci", 4, 1)
        "cedear" in tipo -> Triple("cedear", 0, 1)
        "letra" in tipo -> Triple("letra", 0, 100)
        "titulospublicos" in tipo || "bono" in tipo -> Triple("bond", 0, 100)
        "obligacion" in tipo -> Triple("on", 0, 100)
        "accion" in tipo || tipo == "adr" -> Triple("stock", 0, 1)
        else -> Triple("other", 0, 1)
    }
    // A fund is not listed on a market, so it gets its own namespace
    // (plan decision 1); everything else is keyed by the market it trades on,
    // which is what makes IOL's and Galicia's MELI one commodity.
    val id = if (kind == "fci") "FCI:${symbol.uppercase()}" else "${iolMarket(market ?: title?.mercado)}:${symbol.uppercase()}"
    return InstrumentInfo(
        id = id,
        symbol = symbol.uppercase(),
        name = title?.descripcion,
        kind = kind,
        scale = scale,
        pricePer = pricePer,
        quoteCommodity = iolCurrency(title?.moneda),
    )
}

/** "bcba" / "BCBA" / "nYSE" → "BCBA" / "NYSE"; missing → BCBA, IOL's home market. */
private fun iolMarket(market: String?): String = market?.uppercase()?.takeIf { it.isNotBlank() } ?: "BCBA"

/** IOL's currency spellings ("peso_Argentino", "PESO_ARGENTINO", "DOLARES_USA", …). */
fun iolCurrency(moneda: String?): String? {
    val m = moneda?.lowercase() ?: return null
    return when {
        "peso" in m -> "ARS"
        "dolar" in m -> "USD"
        else -> null
    }
}

/** "AO27 US$" → ("AO27", "USD"); "AO27" → ("AO27", "ARS"). */
private fun iolPaymentSymbol(symbol: String): Pair<String, String> {
    val trimmed = symbol.trim()
    return if (trimmed.endsWith("US$", ignoreCase = true)) {
        trimmed.dropLast(3).trim() to "USD"
    } else {
        trimmed to "ARS"
    }
}

private fun iolVerb(kind: String): String = when (kind) {
    "compra" -> "Compra"
    "venta" -> "Venta"
    "suscripción fci" -> "Suscripción"
    "rescate fci" -> "Rescate"
    else -> kind.replaceFirstChar { it.uppercase() }
}

private fun IolOperation.isFinished(): Boolean = estado.equals("terminada", ignoreCase = true)

private fun pricePerDigits(pricePer: Int): Int = pricePer.toString().length - 1

/**
 * "2026-08-14T12:10:24.697" (Argentina time, no zone) → epoch ms. Null when
 * the text isn't that shape. Argentina has no DST, so UTC−3 is exact.
 */
fun iolTime(text: String?): Long? {
    val t = text?.trim() ?: return null
    val match = Regex("""^(\d{4})-(\d{2})-(\d{2})(?:T(\d{2}):(\d{2})(?::(\d{2})(?:\.(\d{1,7}))?)?)?""").find(t) ?: return null
    val (y, mo, d, h, mi, s, frac) = match.destructured
    val days = daysFromCivil(y.toInt(), mo.toInt(), d.toInt())
    val millis = frac.padEnd(3, '0').take(3).ifEmpty { "0" }.toLong()
    val local = ((days * 24 + (h.ifEmpty { "0" }.toLong())) * 60 + mi.ifEmpty { "0" }.toLong()) * 60 +
        s.ifEmpty { "0" }.toLong()
    return (local + 3 * 3600) * 1000 + millis
}

/** Days since 1970-01-01 of a proleptic Gregorian date (Howard Hinnant's algorithm). */
private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
    val y = (if (month <= 2) year - 1 else year).toLong()
    val era = (if (y >= 0) y else y - 399) / 400
    val yoe = y - era * 400
    val mp = (month + 9) % 12
    val doy = (153 * mp + 2) / 5 + day - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    return era * 146097 + doe - 719468
}
