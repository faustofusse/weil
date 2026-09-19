package ar.fausto.weil

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * One movement, one look, everywhere it's listed: the icon of the account
 * that gives it its meaning (the category for an expense, the source for an
 * income), payee, the route dimmed underneath, amount right-aligned. Home's
 * preview and the full Movimientos list used to be two designs that
 * happened to sit next to each other in the tab bar; this is the version
 * both now render, so switching tabs isn't also a change of vocabulary.
 */
@Composable
internal fun MovementRow(
    tx: Transaction,
    names: Map<String, String>,
    types: Map<String, AccountType>,
    icons: Map<String, String?>,
    hidden: Boolean,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val flow = flowOf(tx, types)
    val from = flow?.fromId?.let { names[it] }
    val to = flow?.toId?.let { names[it] }
    val iconId = iconAccountId(flow, types)
    val avatar = AccountIcons.resolve(icons[iconId], types[iconId])

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp)
            .clip(RoundedCornerShape(18.dp))
            // The tile slate at 15%: a tint of the same ink the account
            // tiles are made of, so the list belongs to them instead of
            // introducing a fourth grey. An outline would have been a fifth
            // edge on a page that already has four card shapes.
            .background(MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.10f))
            .clickable(onClick = onOpen)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        // Filled page-color circle, not an outline: on the tinted row an
        // outline read as a hole in the background rather than an icon.
        // Smaller than the default avatar (40.dp) so it reads as a marker
        // next to the payee, not a second focal point competing with it.
        AccountAvatar(icon = avatar, container = MaterialTheme.colorScheme.background, size = 32.dp)
        Spacer(Modifier.width(12.dp))
        // `end` inset, not a Spacer after the column: the payee is what gets
        // ellipsized when space runs out, and without a reserved gap it ran
        // straight into the amount.
        Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
            Text(
                tx.payee.censored().ifBlank { names[iconId] ?: "" },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (from != null || to != null) {
                Text(
                    if (from != null && to != null && from != to) "$from $ROUTE_ARROW $to" else (from ?: to ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (flow != null) {
            Text(
                // Unsigned: this is a list of rows, and color already says
                // which way the money moved. A minus here would be the same
                // fact printed twice.
                maskedAmount(flow.amountMinor, flow.commodity, hidden, signed = false),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = transactionRowColor(flow.direction),
                maxLines = 1,
            )
        }
        Icon(
            Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.padding(start = 6.dp).size(20.dp),
        )
    }
}

/**
 * Which account's icon represents a movement: the leg that says *what it was
 * for* — the category of an expense, the source of an income — falling back
 * to the destination for a transfer between two of the user's own accounts,
 * where no leg carries a meaning the other doesn't.
 */
internal fun iconAccountId(flow: TxnFlow?, types: Map<String, AccountType>): String? {
    if (flow == null) return null
    val ends = listOfNotNull(flow.fromId, flow.toId)
    return ends.firstOrNull {
        types[it] == AccountType.Expense || types[it] == AccountType.Income
    } ?: flow.toId ?: flow.fromId
}
