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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsScreen(
    notifications: NotificationsRepository,
    onNavigateBack: () -> Unit,
) {
    var items by remember { mutableStateOf(emptyList<NotificationItem>()) }
    var cursor by remember { mutableStateOf<NotificationCursor?>(null) }
    var hasMore by remember { mutableStateOf(true) }
    var isInitialLoading by remember { mutableStateOf(true) }
    var isLoadingMore by remember { mutableStateOf(false) }
    var isSyncing by remember { mutableStateOf(false) }
    var syncError by remember { mutableStateOf<String?>(null) }
    var totalCount by remember { mutableStateOf(0L) }
    var filteredCount by remember { mutableStateOf(0L) }
    var showOnlyTransactions by remember { mutableStateOf(false) }
    var initialized by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    suspend fun refreshCounts() {
        totalCount = notifications.count(onlyTransactions = false)
        filteredCount = notifications.count(onlyTransactions = true)
    }

    suspend fun loadFirst() {
        val page = notifications.page(onlyTransactions = showOnlyTransactions)
        items = page.items
        cursor = page.nextCursor
        hasMore = page.nextCursor != null
        refreshCounts()
    }

    fun reloadWindow() {
        scope.launch {
            try {
                val page = notifications.page(
                    limit = maxOf(items.size, LIST_PAGE_SIZE),
                    onlyTransactions = showOnlyTransactions,
                )
                items = page.items
                cursor = page.nextCursor
                hasMore = page.nextCursor != null
                refreshCounts()
            } catch (_: Throwable) {
            }
        }
    }

    fun sync() {
        scope.launch {
            isSyncing = true
            syncError = null
            try {
                notifications.syncNow()
                loadFirst()
            } catch (e: Throwable) {
                syncError = e.message ?: e.toString()
            } finally {
                isSyncing = false
            }
        }
    }

    fun loadMore() {
        val currentCursor = cursor ?: return
        if (isLoadingMore || !hasMore) return
        isLoadingMore = true
        scope.launch {
            try {
                val page = notifications.page(
                    before = currentCursor,
                    onlyTransactions = showOnlyTransactions,
                )
                items = items + page.items
                cursor = page.nextCursor
                hasMore = page.nextCursor != null
            } catch (e: Throwable) {
                syncError = e.message ?: e.toString()
            } finally {
                isLoadingMore = false
            }
        }
    }

    LaunchedEffect(Unit) {
        try {
            notifications.syncNow()
            loadFirst()
        } catch (e: Throwable) {
            syncError = e.message ?: e.toString()
        } finally {
            isInitialLoading = false
        }
        initialized = true
        notifications.changes.collect { reloadWindow() }
    }

    LaunchedEffect(showOnlyTransactions) {
        if (!initialized) return@LaunchedEffect
        syncError = null
        try {
            loadFirst()
        } catch (e: Throwable) {
            syncError = e.message ?: e.toString()
        }
    }

    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .collect { last ->
                if (last != null && last >= items.size - 10) loadMore()
            }
    }

    val typography = MaterialTheme.typography
    val colorScheme = MaterialTheme.colorScheme
    val shownCount = if (showOnlyTransactions) filteredCount else totalCount

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Notifications ($shownCount/$totalCount)")
                        Spacer(Modifier.width(8.dp))
                        if (syncError != null) {
                            Icon(
                                imageVector = Icons.Filled.Warning,
                                contentDescription = "Sync error",
                                tint = colorScheme.error,
                                modifier = Modifier.padding(start = 4.dp),
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Navigate back")
                    }
                },
                actions = {
                    IconButton(onClick = { sync() }, enabled = !isSyncing) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Sync notifications")
                    }
                },
            )
        },
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = isSyncing,
            onRefresh = { sync() },
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
                    Text("Potential transactions", style = typography.bodyMedium)
                    Spacer(Modifier.width(8.dp))
                    Switch(
                        checked = showOnlyTransactions,
                        onCheckedChange = { showOnlyTransactions = it },
                    )
                }
                Spacer(Modifier.height(16.dp))
                if (isInitialLoading) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        repeat(6) {
                            NotificationCardSkeleton()
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                } else {
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                        items(items, key = { it.id }) { notificationItem ->
                            NotificationCard(notificationItem = notificationItem)
                            Spacer(Modifier.height(8.dp))
                        }
                        if (hasMore) {
                            item(key = "skeleton-footer") {
                                Column {
                                    if (isLoadingMore) {
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
            text = if (granted) {
                "✓ Notification access: GRANTED"
            } else {
                "✗ Notification access: NOT GRANTED"
            },
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        if (!granted) {
            TextButton(onClick = { access.openSettings() }) {
                Text("Grant")
            }
        }
    }
}

@Composable
private fun NotificationCard(notificationItem: NotificationItem) {
    val typography = MaterialTheme.typography

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val bitmap = rememberIcon(notificationItem.appIcon)
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = "${notificationItem.appName} icon",
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
                Text(text = "Category: $it", style = typography.bodySmall)
            }
            Text(
                text = "Time: ${formatTimestamp(notificationItem.postTime)}",
                style = typography.bodySmall,
            )
        }
    }
}

@Composable
private fun rememberIcon(bytes: ByteArray?): ImageBitmap? =
    remember(bytes) { bytes?.let { decodeIcon(it) } }
