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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
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

/** Mirrors NotificationCard's layout. */
@Composable
fun NotificationCardSkeleton() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SkeletonBox(modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                SkeletonBox(modifier = Modifier.width(120.dp).height(16.dp))
            }
            Spacer(Modifier.height(8.dp))
            SkeletonBox(modifier = Modifier.fillMaxWidth(0.85f).height(14.dp))
            Spacer(Modifier.height(6.dp))
            SkeletonBox(modifier = Modifier.fillMaxWidth(0.65f).height(14.dp))
            Spacer(Modifier.height(8.dp))
            SkeletonBox(modifier = Modifier.width(110.dp).height(11.dp))
        }
    }
}

/** Mirrors EmailCard's layout. */
@Composable
fun EmailCardSkeleton() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            SkeletonBox(modifier = Modifier.fillMaxWidth(0.6f).height(16.dp))
            Spacer(Modifier.height(4.dp))
            SkeletonBox(modifier = Modifier.fillMaxWidth(0.9f).height(14.dp))
            Spacer(Modifier.height(4.dp))
            SkeletonBox(modifier = Modifier.width(110.dp).height(11.dp))
        }
    }
}
