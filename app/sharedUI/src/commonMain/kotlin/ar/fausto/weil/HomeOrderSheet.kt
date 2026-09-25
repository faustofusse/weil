@file:OptIn(ExperimentalMaterial3Api::class)

package ar.fausto.weil

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameMillis
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlin.math.abs
import org.jetbrains.compose.resources.stringResource
import weil.app.sharedui.generated.resources.Res
import weil.app.sharedui.generated.resources.home_order_drag
import weil.app.sharedui.generated.resources.home_order_hidden
import weil.app.sharedui.generated.resources.home_order_hint
import weil.app.sharedui.generated.resources.home_order_title

/**
 * Orders Home's account tiles by dragging. Home shows the first
 * [HOME_ACCOUNT_SLOTS], so ordering is also how the user picks which
 * accounts the summary shows — one gesture instead of a checkbox list plus a
 * separate order.
 *
 * The list is edited locally and saved once per drop: saving on every swap
 * would queue a synced write per row crossed.
 *
 * The drag is tracked in the *list's* coordinates, as an absolute position,
 * not as deltas summed on the moving row: the row changes slot under the
 * finger, and every swap booked against a measured height is a chance to
 * drift. Here the row sits wherever the finger is, and its slot is simply
 * the one whose natural top is closest — nothing accumulates.
 */
@Composable
internal fun HomeOrderSheet(state: LedgerState, onDismiss: () -> Unit) {
    var items by remember { mutableStateOf(state.homeAccounts) }
    var dragging by remember { mutableStateOf<String?>(null) }
    /** Finger, in the scrolled content's coordinates. */
    var fingerY by remember { mutableFloatStateOf(0f) }
    /** Finger's distance from the top of the row it grabbed. */
    var grab by remember { mutableFloatStateOf(0f) }
    val heights = remember { mutableStateMapOf<String, Int>() }
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    val handleZone = with(density) { 72.dp.toPx() }
    val edgeZone = with(density) { 56.dp.toPx() }

    // Follows the stored order (a sync landing while the sheet is open, the
    // save of the last drop) — but never mid-drag, where it would yank the
    // row out from under the finger.
    LaunchedEffect(state.homeAccounts, dragging == null) {
        if (dragging == null) items = state.homeAccounts
    }

    fun heightOf(node: AccountNode) = (heights[node.account.id] ?: 0).toFloat()
    fun topOf(index: Int) = items.take(index).sumOf { heightOf(it).toDouble() }.toFloat()

    fun moveTo(y: Float) {
        fingerY = y
        val id = dragging ?: return
        val i = items.indexOfFirst { it.account.id == id }
        if (i < 0) return
        val dragged = items[i]
        val others = items.filterIndexed { j, _ -> j != i }
        val top = (y - grab).coerceIn(0f, (others.sumOf { heightOf(it).toDouble() }).toFloat())
        // Natural top of slot k is the height of the k rows above it.
        var best = 0
        var acc = 0f
        var bestDistance = abs(top)
        others.forEachIndexed { k, node ->
            acc += heightOf(node)
            val d = abs(top - acc)
            if (d < bestDistance) {
                bestDistance = d
                best = k + 1
            }
        }
        if (best != i) items = others.toMutableList().apply { add(best, dragged) }
    }

    fun drop() {
        dragging = null
        val ids = items.map { it.account.id }
        if (ids != state.homeAccounts.map { it.account.id }) state.saveHomeOrder(ids)
    }

    // Scrolls while the finger rests near an edge, so a long list can still
    // be reordered end to end.
    LaunchedEffect(dragging) {
        if (dragging == null) return@LaunchedEffect
        while (true) {
            withFrameMillis { }
            val y = fingerY - scroll.value
            val viewport = scroll.viewportSize.toFloat()
            val speed = when {
                y < edgeZone -> -(edgeZone - y) / edgeZone * 18f
                y > viewport - edgeZone -> (y - (viewport - edgeZone)) / edgeZone * 18f
                else -> 0f
            }
            if (speed != 0f) {
                val used = scroll.scrollBy(speed)
                if (used != 0f) moveTo(fingerY + used)
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        // Full height from the start: a half-open sheet leaves no room to drag.
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(modifier = Modifier.padding(bottom = 24.dp)) {
            Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)) {
                Text(stringResource(Res.string.home_order_title), style = MaterialTheme.typography.titleLarge)
                Text(
                    stringResource(Res.string.home_order_hint, HOME_ACCOUNT_SLOTS),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider(modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(scroll)
                    // After verticalScroll, so positions are content
                    // coordinates: stable while rows swap and while scrolling.
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            // Only the handle drags; the rest of the row
                            // scrolls the list like any other.
                            if (down.position.x < size.width - handleZone) return@awaitEachGesture
                            var acc = 0f
                            val index = items.indexOfFirst { node ->
                                val h = heightOf(node)
                                (down.position.y < acc + h).also { if (!it) acc += h }
                            }
                            if (index < 0) return@awaitEachGesture
                            val start = awaitVerticalTouchSlopOrCancellation(down.id) { change, _ ->
                                change.consume()
                            } ?: return@awaitEachGesture
                            grab = down.position.y - acc
                            dragging = items[index].account.id
                            moveTo(start.position.y)
                            verticalDrag(start.id) { change ->
                                change.consume()
                                moveTo(change.position.y)
                            }
                            drop()
                        }
                    }
                    .padding(horizontal = 12.dp),
            ) {
                items.forEachIndexed { index, node ->
                    val id = node.account.id
                    key(id) {
                        val lifted = dragging == id
                        HomeOrderRow(
                            node = node,
                            onHome = index < HOME_ACCOUNT_SLOTS,
                            totals = state.totals[id].orEmpty(),
                            hidden = state.amountsHidden,
                            lifted = lifted,
                            modifier = Modifier
                                .onSizeChanged { heights[id] = it.height }
                                .zIndex(if (lifted) 1f else 0f)
                                .graphicsLayer {
                                    // Read here, not in composition: a
                                    // finger move repaints one layer.
                                    translationY = if (lifted) {
                                        val i = items.indexOfFirst { it.account.id == id }
                                        val bottom = topOf(items.size) - heightOf(node)
                                        (fingerY - grab).coerceIn(0f, bottom) - topOf(i)
                                    } else {
                                        0f
                                    }
                                },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeOrderRow(
    node: AccountNode,
    onHome: Boolean,
    totals: Map<String, Long>,
    hidden: Boolean,
    lifted: Boolean,
    modifier: Modifier = Modifier,
) {
    val entry = totals.entries.maxByOrNull { abs(it.value) }
    val ink = if (onHome) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .then(if (lifted) Modifier.shadow(6.dp, RoundedCornerShape(12.dp)) else Modifier)
            .clip(RoundedCornerShape(12.dp))
            .background(if (lifted) MaterialTheme.colorScheme.surfaceContainerHighest else Color.Transparent)
            .padding(start = 12.dp, top = 6.dp, bottom = 6.dp),
    ) {
        // Home glyph on the rows that make it onto Home, so the cut-off is
        // legible without a divider jumping around mid-drag.
        Icon(
            Icons.Filled.Home,
            contentDescription = null,
            tint = if (onHome) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                node.account.name.censored(),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (onHome) FontWeight.Medium else FontWeight.Normal,
                color = ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (onHome) {
                    maskedAmount(entry?.value ?: 0L, entry?.key ?: Money.DEFAULT_COMMODITY, hidden)
                } else {
                    stringResource(Res.string.home_order_hidden)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Icon(
            Icons.Filled.DragHandle,
            contentDescription = stringResource(Res.string.home_order_drag),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(12.dp).size(24.dp),
        )
    }
}
