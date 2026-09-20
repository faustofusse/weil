package ar.fausto.weil

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import weil.app.sharedui.generated.resources.Res
import weil.app.sharedui.generated.resources.emails_empty
import weil.app.sharedui.generated.resources.emails_no_subject
import weil.app.sharedui.generated.resources.emails_title
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
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AppTopBar(
                title = stringResource(Res.string.emails_title),
                onNavigateBack = onNavigateBack,
                actions = {
                    // Pull to refresh, like every other list; the only thing
                    // the header still has to say is that a sync failed.
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
        PullToRefreshBox(
            isRefreshing = state.pullRefreshing,
            onRefresh = { state.sync() },
            modifier = Modifier.fillMaxSize().padding(innerPadding),
        ) {
            when {
                state.isInitialLoading && state.items.isEmpty() -> {
                    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                        repeat(EMAIL_SKELETON_COUNT) {
                            EmailCardSkeleton()
                            Spacer(Modifier.height(10.dp))
                        }
                    }
                }
                state.items.isEmpty() -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            stringResource(Res.string.emails_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                else -> {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
                    ) {
                        // Day runs, same as the journal and the notification
                        // list: mail arrives in bursts, and the date was
                        // being repeated on every row to say so.
                        var previous: DayGroup? = null
                        state.items.forEach { email ->
                            val group = dayGroup(email.receivedAt)
                            if (group != previous) {
                                previous = group
                                item(key = "day-${group.key}") { DayHeader(group, top = 8.dp) }
                            }
                            item(key = email.id) {
                                EmailRow(email = email, onOpen = { onOpenEmail(email.id) })
                            }
                        }
                        if (state.hasMore && state.isLoadingMore) {
                            item(key = "skeleton-footer") {
                                Column {
                                    EmailCardSkeleton()
                                    Spacer(Modifier.height(10.dp))
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

/**
 * The shared slab with a mail glyph: the subject is the title (it is what a
 * receipt is recognized by), the sender the dim line under it, the hour on
 * the right — the day is already the header above the run.
 */
@Composable
private fun EmailRow(email: Email, onOpen: () -> Unit) {
    AppListRow(
        icon = Icons.Filled.Email,
        paint = AccountPaint(
            tint = MaterialTheme.colorScheme.background,
            ink = MaterialTheme.colorScheme.inverseSurface,
        ),
        title = email.subject?.censored() ?: stringResource(Res.string.emails_no_subject),
        subtitle = email.fromEmail.censored(),
        onClick = onOpen,
        trailing = {
            Text(
                timeShort(email.receivedAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}
