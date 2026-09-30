package ar.fausto.weil

/**
 * Market value on read (plans/inversiones-brokers.md, decision 3): a
 * per-commodity balance becomes per-currency money by valuing every
 * instrument at its latest price, in the currency it is quoted in. Nothing
 * here is ever booked.
 *
 * Every screen that prints a balance goes through [value]: an account that
 * holds 13 MELI and 1.923.076 S13N6 shows pesos, not a list of tickers with
 * two decimals, and a position that went to zero stops being a "0,00" line.
 */
data class Valuation(
    /** Described commodities, by id ('BCBA:MELI'); currencies have no row. */
    val commodities: Map<String, InstrumentInfo> = emptyMap(),
    /** Latest price per commodity. */
    val prices: Map<String, PriceQuote> = emptyMap(),
    /** Newest official USD rate in ARS, for [convert]; see [OFFICIAL_SOURCE]. */
    val official: PriceQuote? = null,
    /** Newest MEP USD rate in ARS, for [convertMoney]; see [MEP_SOURCE]. */
    val mep: PriceQuote? = null,
    /**
     * Every stored MEP rate, oldest first: the brokers' live quotes and the
     * daily closes ([MEP_CLOSE_SOURCE]). What a purchase cost in the other
     * currency is read from here ([mepAt]).
     */
    val mepHistory: List<PriceQuote> = emptyList(),
) {
    /** Trading line → its security ([tradingLines]): AAPLD → AAPL. */
    private val lines: Map<String, String> by lazy { tradingLines(commodities.values) }

    /** The security [commodity] is a trading line of; itself for anything else. */
    fun securityOf(commodity: String): String = lines[commodity] ?: commodity

    /** [security] and every trading line of it: BCBA:AAPL → {AAPL, AAPLD, AAPLC}. */
    fun linesOf(security: String): Set<String> =
        setOf(security) + lines.filterValues { it == security }.keys

    /**
     * Whether [positions] shows one row per security in [display]: only when
     * everything is stated in one currency, which needs the MEP rate. In
     * «Original» each trading line keeps its own row, in its own currency.
     */
    fun groupsLines(display: InvestmentsDisplay): Boolean = display.target != null && mep != null

    /**
     * The MEP rate that applied at [at]: the newest stored one not after it,
     * as long as it is at most [MEP_HISTORY_GAP_MS] older (a purchase from
     * before the history starts has no rate, rather than today's). A trade
     * at noon reads the previous day's close, which is also what the broker
     * quoted it against.
     */
    fun mepAt(at: Long): Decimal? {
        var lo = 0
        var hi = mepHistory.size - 1
        var found: PriceQuote? = null
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (mepHistory[mid].at <= at) {
                found = mepHistory[mid]
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return found?.takeIf { at - it.at <= MEP_HISTORY_GAP_MS }?.price?.takeIf { it.signum > 0 }
    }

    /**
     * The latest price of [commodity], or of the security it is a trading
     * line of: the broker quotes AAPL, and the AAPLD bought with dollars is
     * the same share, worth the same.
     */
    fun priceOf(commodity: String): PriceQuote? =
        prices[commodity] ?: lines[commodity]?.let { prices[it] }

    /**
     * [minor] cents of [from] in [to] at the MEP rate; itself when they are
     * the same currency, null for any other pair or without a rate.
     */
    fun convertMoney(minor: Long, from: String, to: String): Long? {
        if (from == to) return minor
        val rate = mep?.price?.takeIf { it.signum > 0 } ?: return null
        val amount = Decimal.ofMinorUnits(minor, 2)
        return when {
            from == "USD" && to == "ARS" -> (amount * rate).toMinorUnits(2)
            from == "ARS" && to == "USD" -> amount.divide(rate, 4).toMinorUnits(2)
            else -> null
        }
    }

    /**
     * [money] (currency → minor units) as [display] states it: everything
     * that converts, in its target currency; anything that doesn't (no MEP
     * rate, a third currency) left as it was.
     */
    fun inDisplay(money: Map<String, Long>, display: InvestmentsDisplay): Map<String, Long> {
        val target = display.target ?: return money
        val result = mutableMapOf<String, Long>()
        for ((commodity, minor) in money) {
            val converted = convertMoney(minor, commodity, target)
            val key = if (converted != null) target else commodity
            result[key] = (result[key] ?: 0L) + (converted ?: minor)
        }
        return result.filterValues { it != 0L }
    }

    /**
     * [totals] (commodity → minor units) as money: currencies kept, priced
     * instruments converted into their quote currency and added to it, zero
     * lines dropped. An instrument without a price has no money value to
     * show and is left out here; the positions view lists it by quantity.
     */
    fun value(totals: Map<String, Long>): Map<String, Long> {
        val result = mutableMapOf<String, Long>()
        for ((commodity, minor) in totals) {
            if (minor == 0L) continue
            val info = commodities[commodity]
            if (info == null) {
                // A plain currency (ARS, USD), or anything nobody described:
                // shown as it always was.
                result[commodity] = (result[commodity] ?: 0L) + minor
                continue
            }
            val (quote, money) = valueOf(commodity, minor) ?: continue
            result[quote] = (result[quote] ?: 0L) + money
        }
        return result.filterValues { it != 0L }
    }

    /** One position's value: (quote currency, minor units), or null without a price. */
    fun valueOf(commodity: String, quantityMinor: Long): Pair<String, Long>? {
        val info = commodities[commodity] ?: return null
        val price = priceOf(commodity) ?: return null
        val quantity = Decimal.ofMinorUnits(quantityMinor, info.scale)
        val value = (quantity * price.price).movePointLeft(digitsOf(info.pricePer))
        return price.quoteCommodity to value.toMinorUnits(2)
    }

    /**
     * The positions view of a holdings account (plans/inversiones-brokers.md,
     * question 3): one line per instrument in [holdings], valued at its
     * latest price, with the unrealized gain against the booked cost —
     * computed here, never booked. Currencies are left out (they are the
     * account's cash, not a position). Open positions come first, grouped by
     * quote currency and largest value first; closed ones (quantity zero)
     * after, by symbol.
     *
     * [display] picks the currency: in "Original" each trading line keeps
     * its row and its own quote currency (AAPLD in dollars even though its
     * price comes from AAPL in pesos), and the gain is stated in the currency
     * the line cost, because converting it at today's rate would mix
     * devaluation into it. All in pesos or all in MEP dollars is
     * [securityPositions]: one row per security, value and gain in that
     * currency, the cost converted at the MEP of each purchase's day, read
     * from [movements] (the holdings accounts' postings). That is how a
     * broker's app states it, and the only honest way to add a peso gain
     * to a dollar one.
     */
    fun positions(
        holdings: Map<String, HeldPosition>,
        display: InvestmentsDisplay = InvestmentsDisplay.Original,
        movements: List<HoldingMovement> = emptyList(),
    ): List<PositionLine> {
        val target = display.target
        if (target != null && groupsLines(display)) return securityPositions(holdings, target, movements)
        val lines = holdings.mapNotNull { (commodity, held) ->
            val info = commodities[commodity] ?: return@mapNotNull null
            val price = priceOf(commodity)
            val native = if (held.quantityMinor == 0L) null else valueOf(commodity, held.quantityMinor)
            val value = native?.let { (quote, minor) ->
                val target = display.target ?: info.quoteCommodity ?: quote
                convertMoney(minor, quote, target)?.let { target to it } ?: native
            }
            // Value minus cost in the cost's currency: a dollar bond bought
            // with pesos has no honest number without a rate, so without a
            // MEP rate it gets none.
            val costCommodity = held.costCommodity
            val gain = native?.takeIf { held.costMinor != 0L && costCommodity != null }
                ?.let { (quote, minor) -> convertMoney(minor, quote, costCommodity!!) }
                ?.let { it - held.costMinor }
            PositionLine(
                commodity = commodity,
                info = info,
                quantityMinor = held.quantityMinor,
                costMinor = held.costMinor.takeIf { it != 0L },
                costCommodity = costCommodity,
                price = price,
                valueCommodity = value?.first,
                valueMinor = value?.second,
                unrealizedMinor = gain,
            )
        }
        return sortPositions(lines)
    }

    /**
     * The positions stated wholly in [target] (pesos, or MEP dollars), the
     * way a broker's app shows them: one row per security (8 AAPLD bought
     * with dollars and 3 AAPL bought with pesos are 11 AAPL), valued in
     * [target], and the gain against what each purchase cost in [target] on
     * its own day ([datedCosts]) — a CEDEAR bought with pesos at a MEP of
     * 1.200 cost fewer dollars than today's rate would say, and that is the
     * dollar return. A line whose cost is in [target] already needs no rate;
     * one without a rate for some purchase leaves its security without a
     * gain rather than a guessed one.
     */
    private fun securityPositions(
        holdings: Map<String, HeldPosition>,
        target: String,
        movements: List<HoldingMovement>,
    ): List<PositionLine> {
        val dated = datedCosts(movements)
        val groups = holdings.filterKeys { it in commodities }.entries.groupBy { securityOf(it.key) }
        val lines = groups.map { (security, entries) ->
            val info = commodities[security] ?: commodities.getValue(entries.first().key)
            val quantity = entries.sumOf { it.value.quantityMinor }
            var value: Long? = 0L
            var cost: Long? = 0L
            for ((line, held) in entries) {
                if (held.quantityMinor == 0L) continue
                val lineValue = valueOf(line, held.quantityMinor)?.let { (quote, minor) -> convertMoney(minor, quote, target) }
                value = if (value != null && lineValue != null) value + lineValue else null
                val lineCost = when {
                    held.costMinor == 0L || held.costCommodity == null -> null
                    held.costCommodity == target -> held.costMinor
                    else -> dated[line]?.let { if (target == "ARS") it.ars else it.usd }
                }
                cost = if (cost != null && lineCost != null) cost + lineCost else null
            }
            val open = quantity != 0L
            val shownValue = value.takeIf { open }
            val shownCost = cost.takeIf { open && it != 0L }
            PositionLine(
                commodity = security,
                info = info,
                quantityMinor = quantity,
                costMinor = shownCost,
                costCommodity = target,
                price = priceOf(security) ?: entries.firstNotNullOfOrNull { priceOf(it.key) },
                valueCommodity = shownValue?.let { target },
                valueMinor = shownValue,
                unrealizedMinor = if (shownValue != null && shownCost != null) shownValue - shownCost else null,
            )
        }
        return sortPositions(lines)
    }

    /**
     * What the open units of each trading line cost in pesos and in dollars,
     * each purchase converted at the MEP of its day ([mepAt]). Walked in
     * date order per account and line: a buy adds its cost, a units-only
     * movement (a split) adds units, a sale takes its share of the cost out
     * (average cost, what the ledger books too), and a line that closes
     * starts over. A purchase without a rate leaves that currency unknown.
     */
    fun datedCosts(movements: List<HoldingMovement>): Map<String, DatedCost> {
        class Walk(var quantity: Long = 0L, var ars: Decimal? = Decimal.ZERO, var usd: Decimal? = Decimal.ZERO)
        val walks = mutableMapOf<Pair<String, String>, Walk>()
        for (m in movements.sortedBy { it.at }) {
            val walk = walks.getOrPut(m.accountId to m.commodity) { Walk() }
            if (m.quantityMinor > 0L) {
                walk.quantity += m.quantityMinor
                val cost = m.costMinor?.takeIf { it != 0L } ?: continue
                val amount = Decimal.ofMinorUnits(cost, 2)
                val rate = mepAt(m.at)
                when (m.costCommodity) {
                    "ARS" -> {
                        walk.ars = walk.ars?.plus(amount)
                        walk.usd = if (rate == null) null else walk.usd?.plus(amount.divide(rate, 4))
                    }
                    "USD" -> {
                        walk.usd = walk.usd?.plus(amount)
                        walk.ars = if (rate == null) null else walk.ars?.plus(amount * rate)
                    }
                    else -> {
                        walk.ars = null
                        walk.usd = null
                    }
                }
            } else if (m.quantityMinor < 0L) {
                val before = walk.quantity
                walk.quantity += m.quantityMinor
                if (walk.quantity <= 0L || before <= 0L) {
                    walks[m.accountId to m.commodity] = Walk(quantity = walk.quantity.coerceAtLeast(0L))
                } else {
                    val left = Decimal.of(walk.quantity)
                    val held = Decimal.of(before)
                    walk.ars = walk.ars?.let { (it * left).divide(held, 4) }
                    walk.usd = walk.usd?.let { (it * left).divide(held, 4) }
                }
            }
        }
        val result = mutableMapOf<String, DatedCost>()
        for ((key, walk) in walks) {
            if (walk.quantity == 0L) continue
            val ars = walk.ars?.toMinorUnits(2)
            val usd = walk.usd?.toMinorUnits(2)
            val before = result[key.second]
            result[key.second] = if (before == null) {
                DatedCost(ars, usd)
            } else {
                DatedCost(
                    if (before.ars != null && ars != null) before.ars + ars else null,
                    if (before.usd != null && usd != null) before.usd + usd else null,
                )
            }
        }
        return result
    }

    private fun sortPositions(lines: List<PositionLine>): List<PositionLine> {
        val (open, closed) = lines.partition { !it.closed }
        return open.sortedWith(
            compareBy<PositionLine> { it.valueCommodity == null }
                .thenBy { it.valueCommodity }
                .thenByDescending { it.valueMinor ?: 0L }
                .thenBy { it.info.symbol },
        ) + closed.sortedBy { it.info.symbol }
    }

    /** True for a commodity that is an instrument, not a currency. */
    fun isInstrument(commodity: String): Boolean = commodity in commodities

    private fun digitsOf(pricePer: Int): Int = pricePer.toString().length - 1
}

/**
 * A stored MEP rate older than this before a purchase says nothing about
 * that day: a week covers weekends and holidays, not a missing history.
 */
const val MEP_HISTORY_GAP_MS = 7L * 24 * 60 * 60 * 1000

/** One posting into a holdings account, dated: the input of [Valuation.datedCosts]. */
data class HoldingMovement(
    val accountId: String,
    val at: Long,
    val commodity: String,
    val quantityMinor: Long,
    /** The posting's `@@` cost, signed like the quantity; null for units without a cost (a split). */
    val costMinor: Long?,
    val costCommodity: String?,
)

/** What a line's open units cost in each currency, at each purchase's MEP; null when a rate was missing. */
data class DatedCost(val ars: Long?, val usd: Long?)

/** One instrument of a holdings account, as [Valuation.positions] sees it. */
data class PositionLine(
    val commodity: String,
    val info: InstrumentInfo,
    val quantityMinor: Long,
    /**
     * Total cost in [costCommodity]: the booked one (Σ `@@`), or for a
     * security row in pesos/MEP each purchase at its day's rate. Null when
     * nothing states one.
     */
    val costMinor: Long?,
    val costCommodity: String?,
    /** Latest known price, null when the ledger has none. */
    val price: PriceQuote?,
    /** Market value: currency and minor units, null without a price or when closed. */
    val valueCommodity: String?,
    val valueMinor: Long?,
    /** Value − cost, in [costCommodity]; null without a price, or without a rate between the two. */
    val unrealizedMinor: Long?,
) {
    val closed: Boolean get() = quantityMinor == 0L

    /** Unrealized gain over cost, as a fraction (0.12 = +12 %), or null. */
    val unrealizedRatio: Double?
        get() {
            val gain = unrealizedMinor ?: return null
            val cost = costMinor ?: return null
            if (cost <= 0L) return null
            return gain.toDouble() / cost.toDouble()
        }
}

/**
 * One instrument held at several brokers is one position (plan, UI §4):
 * `BCBA:MELI` at IOL and at Galicia is the same BYMA instrument. Quantities
 * add up; costs add up only when every broker states them in the same
 * currency — pesos plus dollars is not a cost, so the merged one has none
 * and the positions view shows no gain for it rather than a wrong one.
 */
fun consolidateHoldings(perBroker: List<Map<String, HeldPosition>>): Map<String, HeldPosition> {
    val result = mutableMapOf<String, HeldPosition>()
    for (holdings in perBroker) {
        for ((commodity, held) in holdings) {
            val current = result[commodity]
            result[commodity] = if (current == null) held else merge(current, held)
        }
    }
    return result
}

private fun merge(a: HeldPosition, b: HeldPosition): HeldPosition {
    val quantity = a.quantityMinor + b.quantityMinor
    // A side with no cost (a split, a transfer in) adds nothing to it.
    return when {
        b.costMinor == 0L -> a.copy(quantityMinor = quantity)
        a.costMinor == 0L -> b.copy(quantityMinor = quantity)
        a.costCommodity == b.costCommodity -> HeldPosition(quantity, a.costMinor + b.costMinor, a.costCommodity)
        else -> HeldPosition(quantity, 0L, null)
    }
}

/** Unrealized gain of a set of positions in one currency, and the cost it is measured against. */
data class UnrealizedTotal(val commodity: String, val gainMinor: Long, val costMinor: Long) {
    val ratio: Double? get() = if (costMinor > 0L) gainMinor.toDouble() / costMinor.toDouble() else null
}

/**
 * The hero's gain line: [lines]' unrealized gains summed per currency,
 * counting only the positions that have one, grouped by the currency they
 * cost in (which is the one the gain is stated in), so the percentage is
 * over the same cost the gain is.
 */
fun unrealizedTotals(lines: List<PositionLine>): List<UnrealizedTotal> =
    lines.filter { it.unrealizedMinor != null && it.costCommodity != null && it.costMinor != null }
        .groupBy { it.costCommodity!! }
        .map { (currency, group) ->
            UnrealizedTotal(currency, group.sumOf { it.unrealizedMinor!! }, group.sumOf { it.costMinor!! })
        }
        .sortedByDescending { kotlin.math.abs(it.costMinor) }
