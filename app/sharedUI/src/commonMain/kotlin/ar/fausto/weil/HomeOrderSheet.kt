@file:OptIn(ExperimentalMaterial3Api::class)

package ar.fausto.weil

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
 */
@Composable
internal fun HomeOrderSheet(state: LedgerState, onDismiss: () -> Unit) {
    // Re-seeded whenever the stored order or the tree changes (a sync
    // landing while the sheet is open, or the save of the last drop).
    var items by remember(state.homeAccounts) { mutableStateOf(state.homeAccounts) }
    var dragging by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val heights = remember { mutableStateMapOf<String, Int>() }

    fun drop() {
        dragging = null
        dragOffset = 0f
        val ids = items.map { it.account.id }
        if (ids != state.homeAccounts.map { it.account.id }) state.saveHomeOrder(ids)
    }

    // Swaps with a neighbour once the row has travelled past half of it, and
    // hands the travelled distance back so the row stays under the finger.
    fun drag(id: String, dy: Float) {
        dragOffset += dy
        val i = items.indexOfFirst { it.account.id == id }
        if (i < 0) return
        val target = when {
            dragOffset > 0 && i < items.lastIndex -> i + 1
            dragOffset < 0 && i > 0 -> i - 1
            else -> return
        }
        val h = heights[items[target].account.id] ?: return
        if (abs(dragOffset) < h / 2f) return
        items = items.toMutableList().apply { add(target, removeAt(i)) }
        dragOffset -= if (target > i) h else -h
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
                    .verticalScroll(rememberScrollState())
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
                            handle = Modifier.pointerInput(id) {
                                detectVerticalDragGestures(
                                    onDragStart = { dragging = id; dragOffset = 0f },
                                    onDragEnd = { drop() },
                                    onDragCancel = { drop() },
                                    onVerticalDrag = { change, dy ->
                                        change.consume()
                                        drag(id, dy)
                                    },
                                )
                            },
                            modifier = Modifier
                                .onSizeChanged { heights[id] = it.height }
                                .zIndex(if (lifted) 1f else 0f)
                                .graphicsLayer { translationY = if (lifted) dragOffset else 0f },
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
    handle: Modifier,
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
            modifier = handle.padding(12.dp).size(24.dp),
        )
    }
}
