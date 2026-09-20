package ar.fausto.weil

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private const val PULSE_MILLIS = 900

/** Rounded placeholder bar that gently pulses between two surface alphas. */
@Composable
private fun SkeletonBox(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(PULSE_MILLIS),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "skeleton-alpha",
    )
    Box(
        modifier = modifier.background(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha),
            shape = RoundedCornerShape(6.dp),
        ),
    )
}

/** Mirrors NotificationRow's layout: disc, two lines, hour on the right. */
@Composable
fun NotificationCardSkeleton() {
    Surface(
        shape = RoundedCornerShape(RowRadius),
        color = rowTint(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            SkeletonBox(modifier = Modifier.size(32.dp))
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                SkeletonBox(modifier = Modifier.fillMaxWidth(0.55f).height(14.dp))
                Spacer(Modifier.height(6.dp))
                SkeletonBox(modifier = Modifier.fillMaxWidth(0.85f).height(12.dp))
                Spacer(Modifier.height(6.dp))
                SkeletonBox(modifier = Modifier.width(80.dp).height(10.dp))
            }
            Spacer(Modifier.width(12.dp))
            SkeletonBox(modifier = Modifier.width(32.dp).height(10.dp))
        }
    }
}

/** Mirrors EmailRow's layout: the same slab with one line less. */
@Composable
fun EmailCardSkeleton() {
    Surface(
        shape = RoundedCornerShape(RowRadius),
        color = rowTint(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            SkeletonBox(modifier = Modifier.size(32.dp))
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                SkeletonBox(modifier = Modifier.fillMaxWidth(0.8f).height(14.dp))
                Spacer(Modifier.height(6.dp))
                SkeletonBox(modifier = Modifier.fillMaxWidth(0.5f).height(12.dp))
            }
            Spacer(Modifier.width(12.dp))
            SkeletonBox(modifier = Modifier.width(32.dp).height(10.dp))
        }
    }
}

/** Mirrors JournalScreen's TransactionCard layout: same tonal surface, same two-posting-row shape. */
@Composable
fun TransactionCardSkeleton() {
    Surface(
        shape = RoundedCornerShape(GroupRadius),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth()) {
                SkeletonBox(modifier = Modifier.width(140.dp).height(16.dp))
                Spacer(Modifier.weight(1f))
                SkeletonBox(modifier = Modifier.width(36.dp).height(12.dp))
            }
            Spacer(Modifier.height(10.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                SkeletonBox(modifier = Modifier.width(150.dp).height(14.dp))
                Spacer(Modifier.weight(1f))
                SkeletonBox(modifier = Modifier.width(64.dp).height(14.dp))
            }
            Spacer(Modifier.height(6.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                SkeletonBox(modifier = Modifier.width(100.dp).height(14.dp))
                Spacer(Modifier.weight(1f))
                SkeletonBox(modifier = Modifier.width(64.dp).height(14.dp))
            }
        }
    }
}
