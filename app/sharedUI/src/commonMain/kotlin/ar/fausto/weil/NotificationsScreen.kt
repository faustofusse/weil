package ar.fausto.weil

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import weil.app.sharedui.generated.resources.Res
import weil.app.sharedui.generated.resources.action_back
import weil.app.sharedui.generated.resources.action_sync
import weil.app.sharedui.generated.resources.notifications_access_denied
import weil.app.sharedui.generated.resources.notifications_access_granted
import weil.app.sharedui.generated.resources.notifications_app_icon_content
import weil.app.sharedui.generated.resources.notifications_category
import weil.app.sharedui.generated.resources.notifications_grant
import weil.app.sharedui.generated.resources.notifications_time
import weil.app.sharedui.generated.resources.notifications_title_counts
import weil.app.sharedui.generated.resources.notifications_potential_transactions
import weil.app.sharedui.generated.resources.sync_error

// Enough rows to cover any screen height while loading; harmless past the
// fold since this placeholder Column doesn't scroll.
private const val NOTIFICATION_SKELETON_COUNT = 16

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsScreen(
    state: NotificationsState,
    onNavigateBack: () -> Unit,
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

    val typography = MaterialTheme.typography
    val colorScheme = MaterialTheme.colorScheme
    val shownCount = if (state.showOnlyTransactions) state.filteredCount else state.totalCount

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(Res.string.notifications_title_counts, shownCount, state.totalCount))
                        Spacer(Modifier.width(8.dp))
                        if (state.syncError != null) {
                            Icon(
                                imageVector = Icons.Filled.Warning,
                                contentDescription = stringResource(Res.string.sync_error),
                                tint = colorScheme.error,
                                modifier = Modifier.padding(start = 4.dp),
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(Res.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = { state.sync() }, enabled = !state.isSyncing) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(Res.string.action_sync))
                    }
                },
            )
        },
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = state.pullRefreshing,
            onRefresh = { state.sync() },
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
            ) {
                NotificationAccessBanner()
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(Res.string.notifications_potential_transactions), style = typography.bodyMedium)
                    Spacer(Modifier.width(8.dp))
                    Switch(
                        checked = state.showOnlyTransactions,
                        onCheckedChange = { state.toggleShowOnlyTransactions(it) },
                    )
                }
                Spacer(Modifier.height(16.dp))
                if (state.isInitialLoading && state.items.isEmpty()) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        repeat(NOTIFICATION_SKELETON_COUNT) {
                            NotificationCardSkeleton()
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                } else {
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                        items(state.items, key = { it.id }) { notificationItem ->
                            NotificationCard(notificationItem = notificationItem)
                            Spacer(Modifier.height(8.dp))
                        }
                        if (state.hasMore) {
                            item(key = "skeleton-footer") {
                                Column {
                                    if (state.isLoadingMore) {
                                        NotificationCardSkeleton()
                                        Spacer(Modifier.height(8.dp))
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
}

@Composable
private fun NotificationAccessBanner() {
    val access = notificationAccess ?: return
    val granted by access.enabled.collectAsState()

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(
                if (granted) Res.string.notifications_access_granted
                else Res.string.notifications_access_denied,
            ),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        if (!granted) {
            TextButton(onClick = { access.openSettings() }) {
                Text(stringResource(Res.string.notifications_grant))
            }
        }
    }
}

@Composable
private fun NotificationCard(notificationItem: NotificationItem) {
    val typography = MaterialTheme.typography

    Surface(
        shape = RoundedCornerShape(GroupRadius),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val bitmap = rememberIcon(notificationItem.appIcon)
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = stringResource(Res.string.notifications_app_icon_content, notificationItem.appName),
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    text = notificationItem.appName,
                    style = typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(text = notificationItem.title, style = typography.bodyMedium)
            Text(text = notificationItem.text, style = typography.bodyMedium)
            notificationItem.category?.let {
                Text(
                    text = stringResource(Res.string.notifications_category, it),
                    style = typography.bodySmall,
                )
            }
            Text(
                text = stringResource(Res.string.notifications_time, formatTimestamp(notificationItem.postTime)),
                style = typography.bodySmall,
            )
        }
    }
}

@Composable
private fun rememberIcon(bytes: ByteArray?): ImageBitmap? =
    remember(bytes) { bytes?.let { decodeIcon(it) } }
