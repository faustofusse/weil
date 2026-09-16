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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import weil.app.sharedui.generated.resources.Res
import weil.app.sharedui.generated.resources.action_back
import weil.app.sharedui.generated.resources.action_sync
import weil.app.sharedui.generated.resources.emails_no_subject
import weil.app.sharedui.generated.resources.emails_title_count
import weil.app.sharedui.generated.resources.emails_empty
import weil.app.sharedui.generated.resources.sync_error

// Enough rows to cover any screen height while loading; harmless past the
// fold since this placeholder Column doesn't scroll.
private const val EMAIL_SKELETON_COUNT = 16

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmailScreen(
    state: EmailsState,
    onNavigateBack: () -> Unit,
    onOpenEmail: (id: String) -> Unit,
) {
    val listState = rememberLazyListState()

    // No-ops after the first real load — coming back from an email's detail
    // re-enters composition without re-fetching or re-showing a skeleton.
    LaunchedEffect(Unit) { state.ensureLoaded() }

    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .collect { last ->
                if (last != null && last >= state.items.size - 10) state.loadMore()
            }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(Res.string.emails_title_count, state.totalCount))
                        if (state.syncError != null) {
                            Icon(
                                imageVector = Icons.Filled.Warning,
                                contentDescription = stringResource(Res.string.sync_error),
                                tint = MaterialTheme.colorScheme.error,
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
            when {
                state.isInitialLoading && state.items.isEmpty() -> {
                    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                        repeat(EMAIL_SKELETON_COUNT) {
                            EmailCardSkeleton()
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                }
                state.items.isEmpty() -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(stringResource(Res.string.emails_empty), style = MaterialTheme.typography.bodyLarge)
                    }
                }
                else -> {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    ) {
                        items(state.items, key = { it.id }) { email ->
                            EmailCard(email = email, onOpen = { onOpenEmail(email.id) })
                            Spacer(Modifier.height(8.dp))
                        }
                        if (state.hasMore) {
                            item(key = "skeleton-footer") {
                                Column {
                                    if (state.isLoadingMore) {
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
private fun EmailCard(email: Email, onOpen: () -> Unit) {
    val typography = MaterialTheme.typography

    Surface(
        onClick = onOpen,
        shape = RoundedCornerShape(GroupRadius),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
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
                text = email.subject ?: stringResource(Res.string.emails_no_subject),
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
