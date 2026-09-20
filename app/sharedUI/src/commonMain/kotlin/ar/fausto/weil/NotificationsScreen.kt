package ar.fausto.weil

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import weil.app.sharedui.generated.resources.Res
import weil.app.sharedui.generated.resources.notifications_access_denied
import weil.app.sharedui.generated.resources.notifications_app_icon_content
import weil.app.sharedui.generated.resources.notifications_count_line
import weil.app.sharedui.generated.resources.notifications_filter_all
import weil.app.sharedui.generated.resources.notifications_filter_movements
import weil.app.sharedui.generated.resources.notifications_grant
import weil.app.sharedui.generated.resources.notifications_title
import weil.app.sharedui.generated.resources.sync_error

// Enough rows to cover any screen height while loading; harmless past the
// fold since this placeholder Column doesn't scroll.
private const val NOTIFICATION_SKELETON_COUNT = 16

/** The two views of the list, as the segmented control sees them. */
private enum class NotificationFilter { All, Movements }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsScreen(
    state: NotificationsState,
    onNavigateBack: () -> Unit,
    onOpenNotification: (String) -> Unit = {},
) {
    val listState = rememberLazyListState()

    // No-ops after the first real load — returning to this screen re-enters
    // composition without re-fetching or re-showing a skeleton.
    LaunchedEffect(Unit) { state.ensureLoaded() }

    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .collect { last ->
                if (last != null && last >= state.items.size - 10) state.loadMore()
            }
    }

    val shownCount = if (state.showOnlyTransactions) state.filteredCount else state.totalCount

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AppTopBar(
                // The counts moved out of the title and into a line above the
                // list: "Notificaciones (22/21383)" in the header's display
                // size ellipsized on a phone, and the number is a footnote
                // about the list, not the name of the screen.
                title = stringResource(Res.string.notifications_title),
                onNavigateBack = onNavigateBack,
                actions = {
                    // No refresh button: pull-to-refresh covers it here as it
                    // does on every other list in the app.
                    if (state.syncError != null) {
                        Icon(
                            imageVector = Icons.Filled.Warning,
                            contentDescription = stringResource(Res.string.sync_error),
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(end = 12.dp),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            NotificationAccessBanner()
            SegmentedSwitch(
                options = NotificationFilter.entries,
                selected = if (state.showOnlyTransactions) {
                    NotificationFilter.Movements
                } else {
                    NotificationFilter.All
                },
                label = {
                    when (it) {
                        NotificationFilter.All -> stringResource(Res.string.notifications_filter_all)
                        NotificationFilter.Movements ->
                            stringResource(Res.string.notifications_filter_movements)
                    }
                },
                onSelect = {
                    state.toggleShowOnlyTransactions(it == NotificationFilter.Movements)
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            Text(
                stringResource(Res.string.notifications_count_line, shownCount, state.totalCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 20.dp, bottom = 4.dp),
            )
            PullToRefreshBox(
                isRefreshing = state.pullRefreshing,
                onRefresh = { state.sync() },
                modifier = Modifier.fillMaxSize().weight(1f),
            ) {
                if (state.isInitialLoading && state.items.isEmpty()) {
                    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                        repeat(NOTIFICATION_SKELETON_COUNT) {
                            NotificationCardSkeleton()
                            Spacer(Modifier.height(10.dp))
                        }
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
                            bottom = 32.dp,
                        ),
                    ) {
                        // Same day runs as the journal: a capture list is
                        // read by "when did this arrive", and the timestamp
                        // on every row was answering that 21.383 times.
                        var previous: DayGroup? = null
                        state.items.forEach { notificationItem ->
                            val group = dayGroup(notificationItem.postTime)
                            if (group != previous) {
                                previous = group
                                item(key = "day-${group.key}") { DayHeader(group, top = 8.dp) }
                            }
                            item(key = notificationItem.id) {
                                NotificationRow(
                                    notificationItem = notificationItem,
                                    onClick = { onOpenNotification(notificationItem.id) },
                                )
                            }
                        }
                        if (state.hasMore && state.isLoadingMore) {
                            item(key = "skeleton-footer") {
                                Column {
                                    NotificationCardSkeleton()
                                    Spacer(Modifier.height(10.dp))
                                    NotificationCardSkeleton()
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Only shown while access is missing: once granted, the banner is a line of
 * congratulation on top of every visit to a screen that is already visibly
 * full of notifications.
 */
@Composable
private fun NotificationAccessBanner() {
    val access = notificationAccess ?: return
    val granted by access.enabled.collectAsState()
    if (granted) return

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(RowRadius))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
    ) {
        Text(
            text = stringResource(Res.string.notifications_access_denied),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { access.openSettings() }) {
            Text(stringResource(Res.string.notifications_grant))
        }
    }
}

/**
 * The app's list slab, with the posting app's own icon in the disc: these
 * rows are told apart by which app sent them long before by what they say,
 * and that icon is the only thing on screen carrying the sender's brand.
 */
@Composable
private fun NotificationRow(notificationItem: NotificationItem, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp)
            .clip(RoundedCornerShape(RowRadius))
            .background(rowTint())
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        val bitmap = rememberIcon(notificationItem.appIcon)
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(32.dp)
                .background(MaterialTheme.colorScheme.background, CircleShape),
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = stringResource(
                        Res.string.notifications_app_icon_content,
                        notificationItem.appName,
                    ),
                    modifier = Modifier.size(20.dp),
                )
            } else {
                Icon(
                    Icons.Filled.Bolt,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.inverseSurface,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.weight(1f).padding(end = 10.dp),
        ) {
            Text(
                notificationItem.title.censored(),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                notificationItem.text.censored(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                // Two lines: bank notifications put the amount in the first
                // and the card/account in the second, and one line hid the
                // half that says whose money it was.
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                notificationItem.appName.censored(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            timeShort(notificationItem.postTime),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun rememberIcon(bytes: ByteArray?): ImageBitmap? =
    remember(bytes) { bytes?.let { decodeIcon(it) } }
