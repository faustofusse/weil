package ar.fausto.weil

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * One movement, one look, everywhere it's listed: the icon of the account
 * that gives it its meaning (the category for an expense, the source for an
 * income), payee, the route dimmed underneath, amount right-aligned. Home's
 * preview and the full Movimientos list used to be two designs that happened
 * to sit next to each other in the tab bar; this is the version both now
 * render — and the same [AppListRow] the category lists use, so the whole app
 * is one row with different things on its right edge.
 */
@Composable
internal fun MovementRow(
    tx: Transaction,
    names: Map<String, String>,
    types: Map<String, AccountType>,
    icons: Map<String, String?>,
    colors: Map<String, String?>,
    hidden: Boolean,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Drops the disc and the category ink. For lists that are already *of*
     * one category (its own screen): there every row would wear the same
     * glyph in the same color, which is decoration repeated N times, not
     * information.
     */
    plain: Boolean = false,
    /** Ticked in the journal's multi-select run; renders with the checked box. */
    selected: Boolean = false,
    /** Long-press starts (or toggles) a selection run; null leaves the plain row. */
    onLongClick: (() -> Unit)? = null,
) {
    val flow = flowOf(tx, types)
    val from = flow?.fromId?.let { names[it] }
    val to = flow?.toId?.let { names[it] }
    val iconId = iconAccountId(flow, types)
    // Same account that lends the icon lends the color — anything else would
    // put a "Comida" glyph on a "Transporte" disc. Categories fall back to
    // their derived color; an asset (a transfer between own accounts, where
    // no leg means anything the other doesn't) stays neutral.
    val categorical = types[iconId] == AccountType.Expense || types[iconId] == AccountType.Income
    val paint = accountPaint(colors[iconId], seed = iconId.takeIf { categorical })
    // The selected disc's ink on a primary slab: `primary` alone is a surface
    // tone that a white glyph can wash out on, and `onPrimary` is the exact
    // pair the theme guarantees legible.
    val primary = MaterialTheme.colorScheme.primary
    val selectedPaint = AccountPaint(primary, MaterialTheme.colorScheme.onPrimary)

    AppListRow(
        icon = if (plain) null else {
            if (selected) {
                // The checkbox replaces the disc while a selection run is on:
                // a tick beside the amount is invisible against a list of
                // amounts, but the slot that always identifies the row is
                // where a picker's mark belongs.
                Icons.Filled.Check
            } else {
                AccountIcons.resolve(icons[iconId], types[iconId])
            }
        },
        paint = if (selected) selectedPaint else paint,
        title = tx.payee.censored().ifBlank { names[iconId] ?: "" },
        // The payee takes the category's ink, like the category's own name
        // does in the lists: the disc and the title are one label, and the
        // color is what ties the row to the category it belongs to.
        titleColor = if (plain) Color.Unspecified else paint.ink,
        subtitle = when {
            from != null && to != null && from != to -> "$from $ROUTE_ARROW $to"
            else -> from ?: to
        },
        onClick = onOpen,
        onLongClick = onLongClick,
        modifier = modifier,
    ) {
        if (flow != null) {
            Text(
                // Unsigned: this is a list of rows, and color already says
                // which way the money moved. A minus here would be the same
                // fact printed twice.
                maskedAmount(flow.amountMinor, flow.commodity, hidden, signed = false),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = flowColor(flow.direction),
                maxLines = 1,
            )
        }
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
