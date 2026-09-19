package ar.fausto.weil

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import weil.app.sharedui.generated.resources.Res
import weil.app.sharedui.generated.resources.action_cancel
import weil.app.sharedui.generated.resources.icon_pick_title

/**
 * The round icon every account wears: the same 40.dp circle on Home's
 * movements, in the categories list and in the picker below, so picking one
 * shows exactly what will appear in the list.
 */
@Composable
fun AccountAvatar(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 40.dp,
    container: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    content: Color = MaterialTheme.colorScheme.onSurface,
    /** Ring instead of a filled disc, for use on an already tinted row. */
    outlined: Boolean = false,
) {
    Box(
        modifier = modifier
            .size(size)
            .background(container, CircleShape)
            .then(
                if (outlined) Modifier.border(1.5.dp, content, CircleShape) else Modifier,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(size * 0.5f))
    }
}

/**
 * The eight palette entries as a row of swatches, plus the "no color" one.
 * Inline rather than a second dialog: a color is judged against the icon it
 * sits behind, so hiding it one tap away would make the user pick blind.
 */
@Composable
fun ColorPickerRow(
    selected: String?,
    onPick: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
    ) {
        Swatch(
            tint = MaterialTheme.colorScheme.background,
            ink = MaterialTheme.colorScheme.inverseSurface,
            isSelected = AccountColor.of(selected) == null,
            onClick = { onPick(null) },
        )
        AccountColor.entries.forEach { entry ->
            Swatch(
                tint = entry.tint,
                ink = entry.ink,
                isSelected = entry.key == selected,
                onClick = { onPick(entry.key) },
            )
        }
    }
}

@Composable
private fun Swatch(tint: Color, ink: Color, isSelected: Boolean, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(36.dp)
            .background(tint, CircleShape)
            // The ring is the pair's own ink, so the selected swatch shows
            // both halves of what was picked — the tint alone doesn't say
            // what the glyph and the label will look like.
            .then(if (isSelected) Modifier.border(2.dp, ink, CircleShape) else Modifier)
            .clip(CircleShape)
            .clickable(onClick = onClick),
    ) {
        if (isSelected) {
            Icon(
                Icons.Filled.Check,
                contentDescription = null,
                tint = ink,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** Grid of the whole [AccountIcons] catalog; tapping one picks it and closes. */
@Composable
fun IconPickerDialog(
    selected: String?,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.icon_pick_title)) },
        text = {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(64.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().height(320.dp),
            ) {
                items(AccountIcons.catalog, key = { it.key }) { entry ->
                    val isSelected = entry.key == selected
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(2.dp)
                            .clickable { onPick(entry.key) },
                    ) {
                        AccountAvatar(
                            icon = entry.image,
                            size = 48.dp,
                            container = if (isSelected) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHighest
                            },
                            content = if (isSelected) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            modifier = if (isSelected) {
                                Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
                            } else {
                                Modifier
                            },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) }
        },
    )
}
