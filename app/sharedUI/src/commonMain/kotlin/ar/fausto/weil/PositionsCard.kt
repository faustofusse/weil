package ar.fausto.weil

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringResource
import weil.app.sharedui.generated.resources.Res
import weil.app.sharedui.generated.resources.positions_closed
import weil.app.sharedui.generated.resources.positions_cost
import weil.app.sharedui.generated.resources.positions_no_price
import weil.app.sharedui.generated.resources.positions_prices_at
import weil.app.sharedui.generated.resources.positions_title

/**
 * The positions of a holdings account (plans/inversiones-brokers.md,
 * question 3): one row per instrument with quantity, last price and its
 * date, market value and the unrealized gain against the booked cost.
 * Tapping a row narrows the register below to that instrument ([selected]),
 * tapping it again widens it back. Closed positions sit folded at the end.
 */
@Composable
internal fun PositionsCard(
    lines: List<PositionLine>,
    selected: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val open = lines.filterNot { it.closed }
    val closed = lines.filter { it.closed }
    // A filtered closed position must stay visible, or the chip above the
    // register would name a row nobody can see to untap.
    var showClosed by rememberSaveable { mutableStateOf(false) }
    val closedVisible = showClosed || closed.any { it.commodity == selected }
    // The prices' day, said once in the header; a row names its own only
    // when it is older (an instrument that stopped trading, a failed quote),
    // which is the one date worth reading.
    val newest = open.mapNotNull { it.price?.at }.maxOrNull()
    val newestDay = newest?.let { shortDate(it) }
    Column(modifier = modifier.fillMaxWidth()) {
        SectionHeader(title = stringResource(Res.string.positions_title)) {
            newestDay?.let {
                Text(
                    stringResource(Res.string.positions_prices_at, it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth().animateContentSize(),
        ) {
            Column {
                open.forEachIndexed { i, line ->
                    if (i > 0) RowDivider()
                    PositionRow(line, line.commodity == selected, newestDay) {
                        onSelect(if (line.commodity == selected) null else line.commodity)
                    }
                }
                if (closed.isNotEmpty()) {
                    if (open.isNotEmpty()) RowDivider()
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showClosed = !closedVisible }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        Text(
                            stringResource(Res.string.positions_closed, closed.size),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            Icons.Filled.ExpandMore,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp).rotate(if (closedVisible) 180f else 0f),
                        )
                    }
                    if (closedVisible) {
                        closed.forEach { line ->
                            RowDivider()
                            PositionRow(line, line.commodity == selected, newestDay) {
                                onSelect(if (line.commodity == selected) null else line.commodity)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RowDivider() {
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
        modifier = Modifier.padding(horizontal = 16.dp),
    )
}

@Composable
private fun PositionRow(line: PositionLine, selected: Boolean, newestDay: String?, onClick: () -> Unit) {
    val info = line.info
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    info.symbol,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (line.closed) dim else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
                info.name?.let { name ->
                    Spacer(Modifier.width(6.dp))
                    Text(
                        name,
                        style = MaterialTheme.typography.bodySmall,
                        color = dim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            // "13 × $ 24.480,00 · 24/09": what the value is made of. Bonds
            // and letras quote per 100 face value, which the price says, or
            // the product would read as 100 times the value.
            val quantity = formatQuantity(line.quantityMinor, info.scale)
            val per = if (info.pricePer != 1) " c/${info.pricePer}" else ""
            val detail = line.price?.let { p ->
                val day = shortDate(p.at).takeIf { it != newestDay }?.let { " · $it" }.orEmpty()
                "$quantity × ${formatPrice(p.price, p.quoteCommodity, exact = true)}$per$day"
            } ?: "$quantity · ${stringResource(Res.string.positions_no_price)}"
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = dim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.Center) {
            val value = line.valueMinor
            val valueCommodity = line.valueCommodity
            if (value != null && valueCommodity != null) {
                Text(
                    formatMoney(value, valueCommodity, signed = true),
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                )
            }
            val gain = line.unrealizedMinor
            val cost = line.costMinor
            val costCommodity = line.costCommodity
            when {
                gain != null && valueCommodity != null -> Text(
                    gainText(gain, valueCommodity, line.unrealizedRatio),
                    style = MaterialTheme.typography.bodySmall,
                    color = when {
                        gain > 0 -> MoneyColor.positive
                        gain < 0 -> MoneyColor.negative
                        else -> dim
                    },
                    maxLines = 1,
                )
                // No gain to show (closed, unpriced, or cost in another
                // currency): the cost still says what went in.
                cost != null && costCommodity != null && !line.closed -> Text(
                    stringResource(Res.string.positions_cost, formatMoney(cost, costCommodity)),
                    style = MaterialTheme.typography.bodySmall,
                    color = dim,
                    maxLines = 1,
                )
            }
        }
    }
}

/** "+$ 1.234,00 · +4,6 %": signed both ways, since a gain is not a flow. */
private fun gainText(gain: Long, commodity: String, ratio: Double?): String {
    val sign = if (gain > 0) "+" else ""
    val money = sign + formatMoney(gain, commodity, signed = true)
    val pct = ratio?.let { r ->
        val tenths = (r * 1000).roundToLong()
        val s = if (tenths > 0) "+" else if (tenths < 0) "-" else ""
        val t = abs(tenths)
        " · $s${t / 10},${t % 10} %"
    }.orEmpty()
    return money + pct
}

/** "24/09": a price's day, the year only when it's not this one. */
private fun shortDate(at: Long): String {
    val tz = TimeZone.currentSystemDefault()
    val date = Instant.fromEpochMilliseconds(at).toLocalDateTime(tz).date
    val thisYear = Instant.fromEpochMilliseconds(epochMillis()).toLocalDateTime(tz).year
    val dm = date.day.toString().padStart(2, '0') + "/" + (date.month.ordinal + 1).toString().padStart(2, '0')
    return if (date.year == thisYear) dm else "$dm/${date.year % 100}"
}
