package ar.fausto.weil

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToLong
import org.jetbrains.compose.resources.stringResource
import weil.app.sharedui.generated.resources.Res
import weil.app.sharedui.generated.resources.action_back
import weil.app.sharedui.generated.resources.instrument_brokers
import weil.app.sharedui.generated.resources.instrument_movements
import weil.app.sharedui.generated.resources.instrument_no_history
import weil.app.sharedui.generated.resources.instrument_price
import weil.app.sharedui.generated.resources.instrument_units
import weil.app.sharedui.generated.resources.investments_unrealized
import weil.app.sharedui.generated.resources.positions_cost
import weil.app.sharedui.generated.resources.positions_no_price

/**
 * One instrument across every broker (plans/inversiones-brokers.md, UI
 * «Detalle de instrumento»): what it is worth and what it gained, how its
 * price moved (the `prices` rows the syncs have left, one point a day), how
 * much of it each broker holds, and its buys, sales and income. Reached by
 * tapping a position on the investments tab; each broker row opens that
 * broker's register narrowed to the instrument.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstrumentScreen(
    ledgerState: LedgerState,
    brokers: BrokersRepository,
    commodity: String,
    onNavigateBack: () -> Unit,
    onOpenAccount: (AccountDetailRoute) -> Unit,
    onOpenTransaction: (String) -> Unit,
) {
    val ledger = ledgerState.ledger
    val valuation = ledgerState.valuation
    val display = ledgerState.investmentsDisplay
    val hidden = ledgerState.amountsHidden
    val security = valuation.securityOf(commodity)
    val lines = remember(valuation, security) { valuation.linesOf(security) }

    var holdingsByAccount by remember { mutableStateOf<Map<String, Map<String, HeldPosition>>>(emptyMap()) }
    var movements by remember { mutableStateOf<List<HoldingMovement>>(emptyList()) }
    var quotes by remember { mutableStateOf<List<PriceQuote>>(emptyList()) }
    var transactions by remember { mutableStateOf<List<Transaction>>(emptyList()) }

    suspend fun load() {
        val holdingsIds = runCatching { brokers.connections() }.getOrNull()
            ?.map { it.accounts.holdings }?.distinct() ?: return
        holdingsByAccount = holdingsIds.associateWith { ledger.holdings(it) }
        movements = ledger.holdingMovements(holdingsIds)
        quotes = brokers.priceHistory(setOf(security))
        val txIds = ledger.register(subtreeIds = holdingsIds, limit = MOVEMENTS_SCAN, commodities = lines)
            .map { it.posting.transactionId }.distinct()
        val byId = ledger.getAll(txIds)
        transactions = txIds.mapNotNull { byId[it] }
    }

    LaunchedEffect(security, lines) { load() }
    LaunchedEffect(Unit) { ledger.changes.collect { load() } }

    val view = remember(holdingsByAccount, movements, valuation, display, security) {
        valuation.instrumentView(security, holdingsByAccount, display, movements)
    }
    val info = valuation.commodities[security]
    val nodes = remember(ledgerState.tree) { ledgerState.tree.flatMap { it.selfAndDescendants } }
    val names = remember(nodes) { nodes.associate { it.account.id to it.account.name.censored() } }
    val types = remember(nodes) { nodes.associate { it.account.id to it.account.type } }
    val parents = remember(nodes) { nodes.associate { it.account.id to it.account.parentId } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(info?.symbol ?: security, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(Res.string.action_back))
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
        ) {
            item(key = "header") { InstrumentHeader(info, view.total, hidden) }
            item(key = "price") {
                val currency = remember(quotes, info) { chartCurrency(info, quotes) }
                val series = remember(quotes, currency) { currency?.let { dailySeries(quotes, it) }.orEmpty() }
                PriceCard(series, currency, info?.pricePer ?: 1)
            }
            if (view.perAccount.isNotEmpty()) {
                item(key = "brokers-header") { SectionHeader(title = stringResource(Res.string.instrument_brokers)) }
                items(view.perAccount, key = { "broker-${it.first}" }) { (account, rows) ->
                    val broker = (parents[account]?.let { names[it] } ?: names[account]).orEmpty()
                    // One row per line the broker holds (in «Original» a
                    // dollar line keeps its own), each opening that line's register.
                    rows.forEach { row ->
                        val quantity = formatQuantity(row.quantityMinor, row.info.scale)
                        AppListRow(
                            icon = Icons.Filled.TrendingUp,
                            paint = accountPaint(null),
                            title = if (rows.size > 1) "$broker · ${row.info.symbol}" else broker,
                            subtitle = stringResource(Res.string.instrument_units, quantity),
                            onClick = { onOpenAccount(AccountDetailRoute(account, row.commodity)) },
                        ) {
                            Column(horizontalAlignment = Alignment.End) {
                                val value = row.valueMinor
                                val valueCommodity = row.valueCommodity
                                if (value != null && valueCommodity != null) {
                                    Text(
                                        maskedAmount(value, valueCommodity, hidden),
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                    )
                                }
                                GainText(row, hidden)
                            }
                        }
                    }
                }
            }
            if (transactions.isNotEmpty()) {
                item(key = "movements-header") { SectionHeader(title = stringResource(Res.string.instrument_movements)) }
                items(transactions, key = { "tx-${it.id}" }) { tx ->
                    MovementRow(
                        tx = tx,
                        names = names,
                        types = types,
                        looks = ledgerState.looks,
                        hidden = hidden,
                        onOpen = { onOpenTransaction(tx.id) },
                    )
                }
            }
        }
    }
}

/** How many register rows are read: a trade has two legs in the holdings accounts at most. */
private const val MOVEMENTS_SCAN = 60

/**
 * The instrument's total, the account header's language: name, value per
 * currency (a security held as a peso line and a dollar line has two in
 * «Original»), the unrealized gain, units and what they cost.
 */
@Composable
private fun InstrumentHeader(info: InstrumentInfo?, total: List<PositionLine>, hidden: Boolean) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
            info?.name?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
            }
            val values = total.filter { it.valueMinor != null && it.valueCommodity != null }
                .groupBy { it.valueCommodity!! }
                .mapValues { (_, rows) -> rows.sumOf { it.valueMinor!! } }
                .entries.sortedByDescending { abs(it.value) }
            val primary = values.firstOrNull()
            Text(
                primary?.let { maskedAmount(it.value, it.key, hidden) } ?: stringResource(Res.string.positions_no_price),
                style = MaterialTheme.typography.displaySmall,
                maxLines = 1,
            )
            values.drop(1).forEach { (currency, minor) ->
                Text(
                    maskedAmount(minor, currency, hidden),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            unrealizedTotals(total).forEach { g ->
                Text(
                    stringResource(
                        Res.string.investments_unrealized,
                        if (hidden) maskedAmount(g.gainMinor, g.commodity, true) else gainText(g.gainMinor, g.commodity, g.ratio),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = when {
                        hidden || g.gainMinor == 0L -> MaterialTheme.colorScheme.onSurfaceVariant
                        g.gainMinor > 0 -> MoneyColor.positive
                        else -> MoneyColor.negative
                    },
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            // "13 unidades · Costo $ 319.256,83", per line when there are several.
            total.filter { !it.closed }.forEach { row ->
                val units = stringResource(Res.string.instrument_units, formatQuantity(row.quantityMinor, row.info.scale))
                val cost = row.costMinor?.let { c ->
                    row.costCommodity?.let { stringResource(Res.string.positions_cost, maskedAmount(c, it, hidden)) }
                }
                val symbol = if (total.size > 1) "${row.info.symbol}: " else ""
                Text(
                    symbol + listOfNotNull(units, cost).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun GainText(row: PositionLine, hidden: Boolean) {
    val gain = row.unrealizedMinor ?: return
    val currency = row.costCommodity ?: return
    Text(
        if (hidden) maskedAmount(gain, currency, true) else gainText(gain, currency, row.unrealizedRatio),
        style = MaterialTheme.typography.bodySmall,
        color = when {
            hidden || gain == 0L -> MaterialTheme.colorScheme.onSurfaceVariant
            gain > 0 -> MoneyColor.positive
            else -> MoneyColor.negative
        },
        maxLines = 1,
    )
}

/**
 * The price over the days the ledger has one, as a line, with the last
 * price and the change over the period. No history until syncs pile up, so
 * one point (or none) says so instead of drawing a dot.
 */
@Composable
private fun PriceCard(series: List<PricePoint>, currency: String?, pricePer: Int) {
    val per = if (pricePer != 1) " c/$pricePer" else ""
    Column(modifier = Modifier.fillMaxWidth()) {
        SectionHeader(title = stringResource(Res.string.instrument_price)) {
            val last = series.lastOrNull()
            if (last != null && currency != null) {
                Text(
                    formatPrice(last.price, currency, exact = true) + per,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                seriesChange(series)?.let { change ->
                    Text(
                        "  " + percentText(change),
                        style = MaterialTheme.typography.bodyMedium,
                        color = when {
                            change > 0 -> MoneyColor.positive
                            change < 0 -> MoneyColor.negative
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                    )
                }
            }
        }
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (series.size < 2) {
                Text(
                    stringResource(Res.string.instrument_no_history),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 18.dp),
                )
            } else {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                    PriceChart(series, Modifier.fillMaxWidth().height(140.dp))
                    Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        Text(
                            shortDate(series.first().at),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            shortDate(series.last().at),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** The series as a line over time (x by date, not by index: gaps are weekends and missed syncs). */
@Composable
private fun PriceChart(series: List<PricePoint>, modifier: Modifier) {
    val ink = MaterialTheme.colorScheme.primary
    val values = remember(series) { series.map { it.price.toPlainString().toDouble() } }
    Canvas(modifier) {
        val minT = series.first().at
        val spanT = (series.last().at - minT).coerceAtLeast(1L).toFloat()
        val minV = values.min()
        val spanV = (values.max() - minV).takeIf { it > 0.0 } ?: 1.0
        val pad = 4.dp.toPx()
        fun point(i: Int) = Offset(
            x = (series[i].at - minT) / spanT * size.width,
            y = pad + (1f - ((values[i] - minV) / spanV).toFloat()) * (size.height - 2 * pad),
        )
        val line = Path().apply {
            moveTo(point(0).x, point(0).y)
            for (i in 1 until series.size) lineTo(point(i).x, point(i).y)
        }
        val fill = Path().apply {
            addPath(line)
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        }
        drawPath(fill, ink.copy(alpha = 0.10f))
        drawPath(line, ink, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawCircle(ink, radius = 3.5.dp.toPx(), center = point(series.lastIndex))
    }
}

/** "+4,6 %": one decimal, es-AR. */
private fun percentText(fraction: Double): String {
    val tenths = (fraction * 1000).roundToLong()
    val sign = if (tenths > 0) "+" else if (tenths < 0) "-" else ""
    val t = abs(tenths)
    return "$sign${t / 10},${t % 10} %"
}
