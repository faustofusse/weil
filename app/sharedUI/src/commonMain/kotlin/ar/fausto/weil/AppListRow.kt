package ar.fausto.weil

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * The row this app is made of: tinted slab, round icon on the left, a title
 * with an optional dim line under it, whatever the caller needs on the right.
 *
 * One component rather than three that look alike — a movement, a category
 * and a subcategory are the same gesture (a named thing you tap to open) and
 * used to be drawn by three pieces of code that drifted apart by a couple of
 * dp and a font weight each time one of them was touched. What differs
 * between them is genuinely only the trailing slot: an amount, a pencil,
 * nothing.
 */
@Composable
internal fun AppListRow(
    /** Null draws no disc at all: the subcategory rows inside a category,
     *  where every row would wear the same glyph and say nothing. */
    icon: ImageVector?,
    paint: AccountPaint,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    titleColor: Color = Color.Unspecified,
    /** Overridden only to mark selection; the default is the shared tint. */
    container: Color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.10f),
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp)
            .clip(RoundedCornerShape(18.dp))
            // The tile slate at 10%: a tint of the same ink the account tiles
            // are made of, so the list belongs to them instead of introducing
            // a fourth grey. An outline would have been a fifth edge on a
            // page that already has four card shapes.
            .background(container)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        // Filled disc, not an outline: on the tinted row an outline read as a
        // hole in the background rather than an icon. Smaller than the
        // default avatar (40.dp) so it reads as a marker next to the title,
        // not a second focal point competing with it.
        if (icon != null) {
            AccountAvatar(icon = icon, container = paint.tint, content = paint.ink, size = 32.dp)
            Spacer(Modifier.width(12.dp))
        }
        // `end` inset, not a Spacer after the column: the title is what gets
        // ellipsized when space runs out, and without a reserved gap it ran
        // straight into the amount.
        Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = titleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing()
    }
}
