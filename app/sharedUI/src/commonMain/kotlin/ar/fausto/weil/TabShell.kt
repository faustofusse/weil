@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package ar.fausto.weil

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
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
    entries: EntryProviderScope<Any>.(bar: @Composable () -> Unit) -> Unit,
) {
    // Back from a tab returns to Inicio rather than closing the app: the
    // tabs' stack is one deep on purpose, so this is the only way that
    // expectation can be met.
    BackHandler(enabled = current != AppTab.Home) { onSelect(AppTab.Home) }
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
 * The two directions are not mirror images of each other, because they don't
 * mean the same thing. Rightwards along the bar **advances**: the new page
 * comes in small from the right and grows into place, the old one shrinks
 * away. Leftwards **returns**: the new page arrives slightly oversized from
 * the left and settles back down to rest, while the old one swells as it
 * leaves, the way something does when you step back from it. Mirroring a
 * single zoom-in would have made both directions say "forward", and then the
 * drift is the only thing distinguishing them — 16 px that you can miss.
 *
 * Going back is also quicker: you already know what's there.
 */
private fun tabTransform(forward: Boolean, drift: Int) = if (forward) {
    (
        fadeIn(tween(220, delayMillis = 60, easing = Decelerate)) +
            scaleIn(tween(340, delayMillis = 60, easing = Decelerate), initialScale = 0.965f) +
            slideInHorizontally(tween(340, delayMillis = 60, easing = Decelerate)) { drift }
        ) togetherWith (
        fadeOut(tween(110, easing = Accelerate)) +
            scaleOut(tween(220, easing = Accelerate), targetScale = 0.985f) +
            slideOutHorizontally(tween(220, easing = Accelerate)) { -drift / 2 }
        )
} else {
    (
        fadeIn(tween(190, delayMillis = 45, easing = Decelerate)) +
            scaleIn(tween(300, delayMillis = 45, easing = Decelerate), initialScale = 1.035f) +
            slideInHorizontally(tween(300, delayMillis = 45, easing = Decelerate)) { -drift }
        ) togetherWith (
        fadeOut(tween(100, easing = Accelerate)) +
            scaleOut(tween(200, easing = Accelerate), targetScale = 1.02f) +
            slideOutHorizontally(tween(200, easing = Accelerate)) { drift / 2 }
        )
}

/** Small enough to be felt rather than watched. */
private val TabDrift = 16.dp

private val Decelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
private val Accelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
