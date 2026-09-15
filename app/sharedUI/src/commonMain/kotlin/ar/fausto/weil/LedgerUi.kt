package ar.fausto.weil

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * App-scoped undo feedback. RootScreen installs the host state at startup;
 * screens fire undoable actions through [Feedback.undoable] from a scope that
 * survives the screen pop which immediately follows a destructive edit.
 */
object Feedback {
    var host: SnackbarHostState? = null
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    /** Plain snackbar, no action (errors and one-off confirmations). */
    fun show(message: String) {
        val state = host ?: return
        scope.launch {
            try {
                state.showSnackbar(message)
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
            }
        }
    }

    fun undoable(message: String, actionLabel: String = "Undo", onAction: suspend () -> Unit) {
        val state = host ?: return
        scope.launch {
            try {
                val shown = state.showSnackbar(message, actionLabel, withDismissAction = false)
                if (shown == SnackbarResult.ActionPerformed) {
                    try {
                        onAction()
                    } catch (e: Throwable) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        state.showSnackbar(e.message ?: e.toString())
                    }
                }
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
            }
        }
    }
}

/** Positive amounts readable, negatives (credits from the row's view) red. */
@Composable
fun amountColor(minor: Long): Color =
    if (minor >= 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error

/**
 * Transaction-row color for one posting: green when it adds to an asset
 * account, red only when it takes from one — that's the only sign flip a
 * user actually parses at a glance ("did my money grow or shrink"). Every
 * other posting (categories, liabilities, equity) is bookkeeping detail, not
 * a balance moving, so it stays neutral instead of inheriting the raw
 * debit/credit sign.
 */
@Composable
fun postingColor(accountType: AccountType?, minor: Long): Color = when {
    accountType != AccountType.Asset -> MaterialTheme.colorScheme.onSurface
    minor >= 0 -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.error
}

/** Corner radius of the grouped list cards (accounts, devices, sections). */
internal val GroupRadius = 18.dp

/**
 * Skin of one row inside a grouped card: only the first/last row of a run get
 * rounded corners, and every row but the first carries an inset hairline. Rows
 * stay independent lazy items while reading as a single card.
 */
internal data class RowSkin(val shape: Shape, val container: Color, val divider: Boolean)

@Composable
internal fun rowSkin(first: Boolean, last: Boolean): RowSkin = RowSkin(
    shape = RoundedCornerShape(
        topStart = if (first) GroupRadius else 0.dp,
        topEnd = if (first) GroupRadius else 0.dp,
        bottomStart = if (last) GroupRadius else 0.dp,
        bottomEnd = if (last) GroupRadius else 0.dp,
    ),
    container = MaterialTheme.colorScheme.surfaceContainerLow,
    divider = !first,
)

/**
 * Right-aligned balance. Every commodity renders at the same [style] and the
 * same color rule — ARS and USD used to differ (one bold headline, the rest
 * demoted to a small muted line), which read as "the small one doesn't
 * matter." A multi-commodity account now costs one extra line per commodity
 * instead of a size difference.
 *
 * [signalNegative] is off for income/liability/equity, whose balances are
 * negative by bookkeeping convention — painting those red turned the whole
 * tree into a warning.
 */
@Composable
internal fun BalanceText(
    totals: Map<String, Long>,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.titleSmall,
    signalNegative: Boolean = true,
) {
    val entries = totals.entries.sortedByDescending { abs(it.value) }
    Column(horizontalAlignment = Alignment.End, modifier = modifier) {
        if (entries.isEmpty()) {
            Text(
                "—",
                style = style,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        entries.forEach { (commodity, minor) ->
            Text(
                "$commodity ${formatMinorUnits(minor)}",
                style = style,
                color = if (signalNegative) amountColor(minor) else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Rounded tonal chip for totals and short status words. */
@Composable
internal fun ValuePill(
    text: String,
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    content: Color = MaterialTheme.colorScheme.onSurface,
) {
    Surface(shape = CircleShape, color = container, modifier = modifier) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

/** Inline failure banner; every screen renders repository errors the same way. */
@Composable
internal fun ErrorBanner(message: String, modifier: Modifier = Modifier) {
    Banner(
        message = message,
        icon = Icons.Filled.Warning,
        container = MaterialTheme.colorScheme.errorContainer,
        content = MaterialTheme.colorScheme.onErrorContainer,
        modifier = modifier,
    )
}

/** Inline success banner (device approved, credential revoked, …). */
@Composable
internal fun NoticeBanner(message: String, modifier: Modifier = Modifier) {
    Banner(
        message = message,
        icon = Icons.Filled.Check,
        container = MaterialTheme.colorScheme.secondaryContainer,
        content = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = modifier,
    )
}

@Composable
private fun Banner(
    message: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    container: Color,
    content: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = container,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text(message, style = MaterialTheme.typography.bodySmall, color = content)
        }
    }
}
