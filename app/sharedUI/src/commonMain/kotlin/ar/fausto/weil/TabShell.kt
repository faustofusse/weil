@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package ar.fausto.weil

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay

/**
 * The four tabs and the bar that commands them.
 *
 * The bar is drawn *here*, above a nested [NavDisplay], instead of by each
 * screen: when the tabs were four roots of the one back stack, switching one
 * animated the bar along with the content — the thing you just pressed
 * flickered out and back in, which reads as a page load. Nested, the swap is
 * confined to the content and the bar simply stays.
 *
 * Screens still receive a `bar` slot, but it is only a spacer as tall as the
 * real bar (measured, not guessed, since the bar's height depends on the
 * navigation-bar inset): a Scaffold has to reserve the space, and the thing
 * reserving it must not be the thing that has to survive the transition.
 */
@Composable
fun TabShell(
    current: AppTab,
    stack: SnapshotStateList<Any>,
    onSelect: (AppTab) -> Unit,
    onNew: () -> Unit,
    /**
     * True while something is layered over the shell (the create panel): back
     * belongs to that layer, not to the tabs. Without this, both handlers are
     * live at once and the press that should close the panel jumps to Inicio
     * behind it instead — the panel is not on the back stack, so nothing else
     * arbitrates between the two.
     */
    coveredByOverlay: Boolean = false,
    entries: EntryProviderScope<Any>.(bar: @Composable () -> Unit) -> Unit,
) {
    // Back from a tab returns to Inicio rather than closing the app: the
    // tabs' stack is one deep on purpose, so this is the only way that
    // expectation can be met.
    BackHandler(enabled = current != AppTab.Home && !coveredByOverlay) { onSelect(AppTab.Home) }
    var barHeight by remember { mutableStateOf(0.dp) }
    val density = LocalDensity.current
    // Which way along the bar the move went. Latched the moment `current`
    // changes and then left alone: it has to stay put for the whole
    // animation, and anything recomputed from `previous == current` would
    // flip back to "forward" on the very next recomposition — mid-flight.
    val previous = remember { mutableStateOf(current) }
    val forward = remember { mutableStateOf(true) }
    if (previous.value != current) {
        forward.value = current.ordinal > previous.value.ordinal
        previous.value = current
    }
    val drift = with(density) { TabDrift.roundToPx() }
    val spacer: @Composable () -> Unit = { Spacer(Modifier.height(barHeight)) }
    Box(modifier = Modifier.fillMaxSize()) {
        NavDisplay(
            backStack = stack,
            // Nothing to pop: the stack is always one entry deep. Leaving a
            // tab is the bar's job, and leaving the shell is the outer
            // stack's.
            onBack = {},
            entryProvider = entryProvider { entries(spacer) },
            transitionSpec = { tabTransform(forward.value, drift) },
            popTransitionSpec = { tabTransform(forward.value, drift) },
            predictivePopTransitionSpec = { tabTransform(forward.value, drift) },
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .onSizeChanged { barHeight = with(density) { it.height.toDp() } },
        ) {
            AppBottomBar(current = current, onSelect = onSelect, onNew = onNew)
        }
    }
}

/**
 * The outgoing tab leaves quickly and the incoming one is held back a beat,
 * so the two never wash over each other at half opacity — a plain cross-fade
 * on two dense pages of rows reads as a smear.
 *
 * The move is horizontal only: the page slides in from the side the tab it
 * belongs to sits on in the bar, and the one leaving goes the other way at
 * half the distance, so the pair reads as one strip shifting rather than two
 * cards swapping. No scale — a page that grows or shrinks is claiming depth,
 * and the four tabs are all at the same depth.
 *
 * Going back is quicker than going forward: you already know what's there.
 */
private fun tabTransform(forward: Boolean, drift: Int) = if (forward) {
    (
        fadeIn(tween(220, delayMillis = 60, easing = Decelerate)) +
            slideInHorizontally(tween(340, delayMillis = 60, easing = Decelerate)) { drift }
        ) togetherWith (
        fadeOut(tween(110, easing = Accelerate)) +
            slideOutHorizontally(tween(220, easing = Accelerate)) { -drift / 2 }
        )
} else {
    (
        fadeIn(tween(190, delayMillis = 45, easing = Decelerate)) +
            slideInHorizontally(tween(300, delayMillis = 45, easing = Decelerate)) { -drift }
        ) togetherWith (
        fadeOut(tween(100, easing = Accelerate)) +
            slideOutHorizontally(tween(200, easing = Accelerate)) { drift / 2 }
        )
}

/**
 * Larger now that it is the *only* signal of direction — with the scale gone
 * 16 px was a twitch. Still far short of a full-width slide, which would put
 * a whole screen's worth of travel between two siblings.
 */
private val TabDrift = 40.dp

private val Decelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
private val Accelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
