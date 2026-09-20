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
import androidx.compose.runtime.SideEffect
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
    // Which way the new tab lies in the bar. The tabs are siblings, so a
    // full slide would overstate the move — but a few pixels of drift in the
    // direction your thumb just travelled is the difference between "the
    // screen changed" and "I moved". Kept as plain state (not derived) so
    // the value the transition reads is the one from *before* the swap.
    var previous by remember { mutableStateOf(current) }
    val forward = current.ordinal >= previous.ordinal
    SideEffect { previous = current }
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
            transitionSpec = { tabTransform(forward, drift) },
            popTransitionSpec = { tabTransform(forward, drift) },
            predictivePopTransitionSpec = { tabTransform(forward, drift) },
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
 * on two dense pages of rows reads as a smear. What arrives also starts a
 * hair small and a few pixels off-centre and settles: the scale is what
 * makes it feel like the page came *forward* rather than simply appearing.
 */
private fun tabTransform(forward: Boolean, drift: Int) =
    (
        fadeIn(tween(220, delayMillis = 60, easing = Decelerate)) +
            scaleIn(tween(320, delayMillis = 60, easing = Decelerate), initialScale = 0.975f) +
            slideInHorizontally(tween(320, delayMillis = 60, easing = Decelerate)) {
                if (forward) drift else -drift
            }
        ) togetherWith (
        fadeOut(tween(110, easing = Accelerate)) +
            scaleOut(tween(220, easing = Accelerate), targetScale = 0.99f) +
            slideOutHorizontally(tween(220, easing = Accelerate)) {
                if (forward) -drift / 2 else drift / 2
            }
        )

/** Small enough to be felt rather than watched. */
private val TabDrift = 16.dp

private val Decelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
private val Accelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
