package ar.fausto.weil

/*
 * The instrument screen (plans/inversiones-brokers.md, UI «Detalle de
 * instrumento»): one security across every broker that holds it, and how
 * its price moved. Pure, on top of [Valuation.positions], so the numbers
 * are the same ones the positions card shows.
 */

/** One security as the instrument screen shows it. */
data class InstrumentView(
    /** The security (BCBA:AAPL), whatever line was tapped. */
    val security: String,
    /** Its trading lines (AAPL, AAPLD, AAPLC); just the security for most. */
    val lines: Set<String>,
    /** Every broker together, as the positions card states it. */
    val total: List<PositionLine>,
    /** Per holdings account, only the accounts that ever held it; biggest value first. */
    val perAccount: List<Pair<String, List<PositionLine>>>,
)

/**
 * [security] (or any line of it) across [holdingsByAccount] (holdings
 * account id → what it holds), in [display], with each account's cost read
 * from its own [movements]. An account that never held a line is left out;
 * one that sold everything stays, as a closed row, because the register
 * behind it is still that instrument's history there.
 */
fun Valuation.instrumentView(
    security: String,
    holdingsByAccount: Map<String, Map<String, HeldPosition>>,
    display: InvestmentsDisplay,
    movements: List<HoldingMovement>,
): InstrumentView {
    val root = securityOf(security)
    val lines = linesOf(root)
    val filtered = holdingsByAccount
        .mapValues { (_, held) -> held.filterKeys { it in lines } }
        .filterValues { it.isNotEmpty() }
    val ownMovements = movements.filter { it.commodity in lines }
    val perAccount = filtered.map { (account, held) ->
        account to positions(held, display, ownMovements.filter { it.accountId == account })
    }.sortedByDescending { (_, rows) -> rows.sumOf { it.valueMinor ?: 0L } }
    val total = positions(consolidateHoldings(filtered.values.toList()), display, ownMovements)
    return InstrumentView(root, lines, total, perAccount)
}

/** One price per day, for the chart. */
data class PricePoint(val at: Long, val price: Decimal)

/**
 * [quotes] as a chart series: only [quote]-currency prices (a line quoted
 * in pesos and dollars would otherwise zigzag between the two), the newest
 * quote of each Argentine day (a sync every hour is still one point a day),
 * oldest first.
 */
fun dailySeries(quotes: List<PriceQuote>, quote: String): List<PricePoint> =
    quotes.filter { it.quoteCommodity == quote && it.price.signum > 0 }
        .groupBy { iolDate(it.at) }
        .map { (_, day) -> day.maxBy { it.at } }
        .sortedBy { it.at }
        .map { PricePoint(it.at, it.price) }

/** Which currency to chart [commodity]'s price in: its declared quote, else the one most quoted. */
fun chartCurrency(info: InstrumentInfo?, quotes: List<PriceQuote>): String? =
    info?.quoteCommodity?.takeIf { q -> quotes.any { it.quoteCommodity == q } }
        ?: quotes.groupingBy { it.quoteCommodity }.eachCount().maxByOrNull { it.value }?.key

/** First → last change of [series] as a fraction, or null with fewer than two points. */
fun seriesChange(series: List<PricePoint>): Double? {
    if (series.size < 2) return null
    val first = series.first().price
    val last = series.last().price
    if (first.signum <= 0) return null
    return (last - first).divide(first, 6).toPlainString().toDouble()
}
