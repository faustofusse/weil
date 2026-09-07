package ar.fausto.weil

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmailScreen(
    emails: EmailsRepository,
    onNavigateBack: () -> Unit,
) {
    var items by remember { mutableStateOf(emptyList<Email>()) }
    var cursor by remember { mutableStateOf<EmailCursor?>(null) }
    var hasMore by remember { mutableStateOf(true) }
    var isInitialLoading by remember { mutableStateOf(true) }
    var isLoadingMore by remember { mutableStateOf(false) }
    var isSyncing by remember { mutableStateOf(false) }
    var syncError by remember { mutableStateOf<String?>(null) }
    var totalCount by remember { mutableStateOf(0L) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    suspend fun loadFirst() {
        val page = emails.page()
        items = page.items
        cursor = page.nextCursor
        hasMore = page.nextCursor != null
        totalCount = emails.count()
    }

    fun sync() {
        scope.launch {
            isSyncing = true
            syncError = null
            try {
                emails.syncNow()
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
                val page = emails.page(before = currentCursor)
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
            emails.syncNow()
            loadFirst()
        } catch (e: Throwable) {
            syncError = e.message ?: e.toString()
        } finally {
            isInitialLoading = false
        }
    }

    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .collect { last ->
                if (last != null && last >= items.size - 10) loadMore()
            }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Emails ($totalCount)")
                        if (syncError != null) {
                            Icon(
                                imageVector = Icons.Filled.Warning,
                                contentDescription = "Sync error",
                                tint = MaterialTheme.colorScheme.error,
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
                        Icon(Icons.Filled.Refresh, contentDescription = "Sync emails")
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
            when {
                isInitialLoading -> {
                    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                        repeat(8) {
                            EmailCardSkeleton()
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                }
                items.isEmpty() -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No emails yet :(", style = MaterialTheme.typography.bodyLarge)
                    }
                }
                else -> {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    ) {
                        items(items, key = { it.id }) { email ->
                            EmailCard(email = email)
                            Spacer(Modifier.height(8.dp))
                        }
                        if (hasMore) {
                            item(key = "skeleton-footer") {
                                Column {
                                    if (isLoadingMore) {
                                        EmailCardSkeleton()
                                        Spacer(Modifier.height(8.dp))
                                        EmailCardSkeleton()
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
private fun EmailCard(email: Email) {
    val typography = MaterialTheme.typography

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = email.fromEmail,
                style = typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = email.subject ?: "(No subject)",
                style = typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = formatTimestamp(email.receivedAt),
                style = typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
