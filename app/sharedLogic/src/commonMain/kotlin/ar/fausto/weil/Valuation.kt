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
) {
    /** Trading line → its security ([tradingLines]): AAPLD → AAPL. */
    private val lines: Map<String, String> by lazy { tradingLines(commodities.values) }

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
     * [display] picks the currency of the value: the line's own quote
     * currency (AAPLD in dollars even though its price comes from AAPL in
     * pesos), or everything in pesos or in MEP dollars. The gain is always
     * stated in the currency the position cost: value converted into it at
     * today's MEP rate when needed, minus the cost. Converting a peso gain
     * into dollars at today's rate would mix devaluation into it, and the
     * cost's historical rate is not something the ledger knows.
     */
    fun positions(
        holdings: Map<String, HeldPosition>,
        display: InvestmentsDisplay = InvestmentsDisplay.Original,
    ): List<PositionLine> {
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

/** One instrument of a holdings account, as [Valuation.positions] sees it. */
data class PositionLine(
    val commodity: String,
    val info: InstrumentInfo,
    val quantityMinor: Long,
    /** Total booked cost (Σ `@@`), null when nothing states one. */
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
