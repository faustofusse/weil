package ar.fausto.weil

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import weil.app.sharedui.generated.resources.Res
import weil.app.sharedui.generated.resources.nav_categories
import weil.app.sharedui.generated.resources.nav_home
import weil.app.sharedui.generated.resources.nav_movements
import weil.app.sharedui.generated.resources.nav_profile
import weil.app.sharedui.generated.resources.new_transaction

/** The four root destinations of the bottom bar. */
enum class AppTab { Home, Movements, Categories, Profile }

/** How far the create button rides above the bar's top edge. */
private val FabOverlap = 26.dp

/** Extra air between the create button's slot and the tabs flanking it. */
private val InnerTabGap = 26.dp

/**
 * Bottom navigation for the four root destinations **plus** the create
 * button, which is part of the bar rather than a Scaffold FAB: the design has
 * it straddling the bar's top edge, and a Scaffold FAB can only ever float
 * *above* the bottom bar. Owning both here also means the button is in the
 * same place on every root screen, which a per-screen FAB kept getting wrong.
 *
 * Every screen that *is* a root renders it; deeper screens don't, so the bar
 * never promises "you are here" from three levels down a stack.
 */
@Composable
fun AppBottomBar(current: AppTab, onSelect: (AppTab) -> Unit, onNew: () -> Unit) {
    // The Box is the bar's own height; the button is drawn outside it via a
    // negative offset (Compose doesn't clip to bounds), so the bar keeps
    // reserving exactly the space it occupies and screen content isn't
    // pushed down by a button that overlaps it.
    Box(modifier = Modifier.fillMaxWidth()) {
        Surface(
            // The mint of the balance hero: the two ends of the screen are
            // the same surface, which is what makes the page read as one
            // sheet with content floating on it.
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            shape = RoundedCornerShape(topStart = BarCorner, topEnd = BarCorner),
            tonalElevation = 0.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(top = 12.dp, bottom = 10.dp),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                TabItem(
                    AppTab.Home,
                    Icons.Filled.Home,
                    stringResource(Res.string.nav_home),
                    current,
                    onSelect,
                    Modifier.weight(1f),
                )
                TabItem(
                    AppTab.Movements,
                    Icons.Filled.ListAlt,
                    stringResource(Res.string.nav_movements),
                    current,
                    onSelect,
                    // The two inner tabs are pushed away from the middle so
                    // the create button gets clear air around it instead of
                    // crowding the labels either side of its gap.
                    Modifier.weight(1f).padding(end = InnerTabGap),
                )
                // The gap the create button sits in. Reserved as a real slot
                // instead of letting the button cover a tab: a destination
                // you can't hit is worse than a missing one.
                Spacer(Modifier.weight(0.55f))
                TabItem(
                    AppTab.Categories,
                    Icons.Filled.Category,
                    stringResource(Res.string.nav_categories),
                    current,
                    onSelect,
                    Modifier.weight(1f).padding(start = InnerTabGap),
                )
                TabItem(
                    AppTab.Profile,
                    Icons.Filled.Person,
                    stringResource(Res.string.nav_profile),
                    current,
                    onSelect,
                    Modifier.weight(1f),
                )
            }
        }
        FloatingActionButton(
            onClick = onNew,
            // Mid slate, filled: on the mint bar an outlined white disc read
            // as a hole punched in the bar rather than as the one button
            // that creates things.
            containerColor = MaterialTheme.colorScheme.secondary,
            contentColor = MaterialTheme.colorScheme.onSecondary,
            shape = CircleShape,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = -FabOverlap)
                .size(64.dp),
        ) {
            Icon(
                Icons.Filled.Add,
                contentDescription = stringResource(Res.string.new_transaction),
                modifier = Modifier.size(28.dp),
            )
        }
    }
}

@Composable
private fun TabItem(
    tab: AppTab,
    icon: ImageVector,
    label: String,
    current: AppTab,
    onSelect: (AppTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selected = tab == current
    // Selection is weight and opacity only — on a colored bar an underline or
    // a pill reads as a second control sitting under the icon.
    val color = if (selected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.55f)
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        // Equal shares of whatever is left beside the create button's gap,
        // rather than a fixed width: a fixed one truncated "Movimientos" on
        // narrow phones while leaving air around "Inicio".
        modifier = modifier
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onSelect(tab) }
            .padding(vertical = 2.dp),
    ) {
        Icon(icon, contentDescription = label, tint = color, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(4.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

/**
 * The bar's top radius, shared with [TransactionSheet]: the create panel is
 * supposed to look like the bar itself grew upwards, which only holds if the
 * two round their top edge by the same amount.
 */
val BarCorner = 28.dp
