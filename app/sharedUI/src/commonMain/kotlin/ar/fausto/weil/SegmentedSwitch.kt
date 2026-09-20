package ar.fausto.weil

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * The app's one "pick one of these" control: a slate track with the chosen
 * option filled in darker.
 *
 * Extracted from the journal's kind filter so the notification list's
 * "todas / movimientos" reads as the same control rather than as a lone
 * Material `Switch` with a label beside it — a switch says "on/off for the
 * thing it sits next to", which is not what choosing between two views of a
 * list means.
 */
@Composable
internal fun <T> SegmentedSwitch(
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.secondary,
        shape = RoundedCornerShape(24.dp),
        modifier = modifier,
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            options.forEach { option ->
                val isSelected = option == selected
                Surface(
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.inverseSurface
                    } else {
                        Color.Transparent
                    },
                    contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Row(
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            // Not a ripple: the touch target is a text label
                            // inside a 44.dp pill, where a bounded ripple
                            // draws as a thin rectangle and reads as a glitch.
                            .fadeOnPress { onSelect(option) }
                            .heightIn(min = 44.dp)
                            .fillMaxWidth(),
                    ) {
                        Text(
                            label(option),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
            }
        }
    }
}
