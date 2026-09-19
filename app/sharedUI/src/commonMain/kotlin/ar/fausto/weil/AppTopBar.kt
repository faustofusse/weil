package ar.fausto.weil

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * The header every root screen wears: one height, one title style, one
 * trailing action slot. The four tabs used to each bring their own — Home a
 * headline scrolling inside its list, the rest a stock `TopAppBar` — so the
 * title jumped size and baseline on every tab switch, which made the swap
 * look like a page load rather than a change of pane.
 *
 * Not `TopAppBar`: its 64.dp centered `titleLarge` can't carry the display
 * greeting the design asks for, and its surface tint on scroll is a second
 * background color on a page that has exactly one.
 */
@Composable
fun AppTopBar(
    title: String,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    // The app draws edge to edge, so the header owns the status-bar inset:
    // it's the topmost thing on every root screen, and without this the
    // greeting sat *under* the clock.
    Column(modifier = modifier.fillMaxWidth().statusBarsPadding()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .height(TopBarHeight)
                // end is tighter than start: the trailing slot holds icon
                // buttons, whose own 12.dp of internal padding makes up the
                // rest.
                .padding(start = 20.dp, end = 8.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.headlineMedium,
                // headlineMedium is Bold app-wide (it's also the balance
                // hero's weight); the header overrides it to Light on its
                // own so that doesn't move the hero figure's weight too.
                fontWeight = FontWeight.Light,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            actions()
        }
    }
}

/** Shared so a screen's scrolling content can reason about the header. */
val TopBarHeight = 72.dp
