package ar.fausto.weil

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import weil.app.sharedui.generated.resources.Res
import weil.app.sharedui.generated.resources.similar_distance
import weil.app.sharedui.generated.resources.similar_link
import weil.app.sharedui.generated.resources.similar_linked
import weil.app.sharedui.generated.resources.similar_none
import weil.app.sharedui.generated.resources.similar_not_embedded
import weil.app.sharedui.generated.resources.similar_only_this_app
import weil.app.sharedui.generated.resources.similar_search

/**
 * "Parecidos a este": the neighbours of one row, in any of the three embedded
 * tables.
 *
 * The search is local SQL over stored vectors, so this section costs nothing
 * and works with the radio off. The one network call it can make is the
 * [EmbeddingsRepository.embedOne] escape hatch, behind the explicit "buscar
 * parecidas" button shown when the row being viewed has no vector — which
 * happens exactly when the candidate prefilter decided the message wasn't
 * about money, so the button doubles as a way to catch that being wrong.
 *
 * Amount and day are printed on every row on purpose: cosine says "these read
 * alike", never "these are the same event" (two Rappi orders are nearly
 * identical vectors and different purchases), so a human confirms every link.
 */
@Composable
fun SimilarSection(
    embeddings: EmbeddingsRepository,
    title: String,
    kind: EmbedKind,
    id: String,
    into: EmbedKind = kind,
    /** Offers the "solo esta app" filter; only meaningful for notifications. */
    allowPackageFilter: Boolean = false,
    onOpen: (SimilarItem) -> Unit = {},
    /** Null hides the per-row action; see the "vincular como origen" callers. */
    onLink: ((SimilarItem) -> Unit)? = null,
    /** Refs already attached, so a linked row shows as such instead of again. */
    linkedRefs: Set<String> = emptySet(),
    reloadKey: Any? = null,
) {
    var items by remember(kind, id, into) { mutableStateOf<List<SimilarItem>>(emptyList()) }
    var loading by remember(kind, id, into) { mutableStateOf(true) }
    var embedded by remember(kind, id, into) { mutableStateOf(true) }
    var samePackageOnly by remember(kind, id, into) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    suspend fun load() {
        loading = true
        try {
            items = embeddings.similar(kind, id, into = into, samePackageOnly = samePackageOnly)
            embedded = items.isNotEmpty() || embeddings.isEmbedded(kind, id)
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            items = emptyList()
        } finally {
            loading = false
        }
    }

    LaunchedEffect(kind, id, into, samePackageOnly, reloadKey) { load() }

    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Spacer(Modifier.height(8.dp))

    if (allowPackageFilter && into == EmbedKind.Notification) {
        FilterChip(
            selected = samePackageOnly,
            onClick = { samePackageOnly = !samePackageOnly },
            label = { Text(stringResource(Res.string.similar_only_this_app)) },
        )
        Spacer(Modifier.height(8.dp))
    }

    Surface(
        shape = RoundedCornerShape(GroupRadius),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        when {
            loading -> Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp))
            }

            !embedded -> Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(Res.string.similar_not_embedded),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = {
                        scope.launch {
                            loading = true
                            try {
                                embeddings.embedOne(kind, id)
                                load()
                            } catch (e: Throwable) {
                                if (e is CancellationException) throw e
                                loading = false
                                Feedback.show(e.message ?: e.toString())
                            }
                        }
                    },
                ) { Text(stringResource(Res.string.similar_search)) }
            }

            items.isEmpty() -> Text(
                stringResource(Res.string.similar_none),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
            )

            else -> Column {
                items.forEachIndexed { index, item ->
                    if (index > 0) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 16.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                        )
                    }
                    SimilarRow(
                        item = item,
                        onOpen = { onOpen(item) },
                        onLink = onLink,
                        linked = item.id in linkedRefs,
                    )
                }
            }
        }
    }
}

@Composable
private fun SimilarRow(
    item: SimilarItem,
    onOpen: () -> Unit,
    onLink: ((SimilarItem) -> Unit)?,
    linked: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen() }
            .heightIn(min = 64.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                item.title.ifBlank { item.subtitle },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.subtitle.isNotBlank() && item.title.isNotBlank()) {
                Text(
                    item.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    dayLabel(dayGroup(item.date)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(Res.string.similar_distance, formatDistance(item.distance)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
            }
        }
        val amount = item.amountMinor
        if (amount != null) {
            Text(
                formatMoney(amount, item.commodity ?: Money.DEFAULT_COMMODITY),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
        if (onLink != null) {
            if (linked) {
                Text(
                    stringResource(Res.string.similar_linked),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp),
                )
            } else {
                TextButton(onClick = { onLink(item) }) {
                    Text(stringResource(Res.string.similar_link))
                }
            }
        }
    }
}

/** Two decimals, formatted by hand: Kotlin common has no printf. */
internal fun formatDistance(value: Double): String {
    val hundredths = (value.coerceAtLeast(0.0) * 100).toLong()
    return "${hundredths / 100}.${(hundredths % 100).toString().padStart(2, '0')}"
}
