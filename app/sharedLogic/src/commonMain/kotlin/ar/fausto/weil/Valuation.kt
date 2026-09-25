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
) {
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
        val price = prices[commodity] ?: return null
        val quantity = Decimal.ofMinorUnits(quantityMinor, info.scale)
        val value = (quantity * price.price).movePointLeft(digitsOf(info.pricePer))
        return price.quoteCommodity to value.toMinorUnits(2)
    }

    /** True for a commodity that is an instrument, not a currency. */
    fun isInstrument(commodity: String): Boolean = commodity in commodities

    private fun digitsOf(pricePer: Int): Int = pricePer.toString().length - 1
}
