@file:OptIn(ExperimentalMaterial3Api::class)

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import weil.app.sharedui.generated.resources.Res
import weil.app.sharedui.generated.resources.notification_detail_title
import weil.app.sharedui.generated.resources.notifications_app_icon_content
import weil.app.sharedui.generated.resources.notifications_category
import weil.app.sharedui.generated.resources.similar_link_done
import weil.app.sharedui.generated.resources.similar_notifications
import weil.app.sharedui.generated.resources.similar_transactions
import weil.app.sharedui.generated.resources.suggest_debug_action

/**
 * Vista completa de una notificación capturada, y sus parecidas.
 *
 * The transactions listed here can be linked back as this notification's
 * origin, which is the point of the whole feature: the device has 184 document
 * sources, 6 from WhatsApp and **zero** from notifications, and every manual
 * link is one labelled example for the automatic pipeline that comes next —
 * collected while using the app, with no labelling screen.
 */
@Composable
fun NotificationDetailScreen(
    notifications: NotificationsRepository,
    ledger: TransactionsRepository,
    embeddings: EmbeddingsRepository,
    id: String,
    onNavigateBack: () -> Unit,
    onOpenNotification: (String) -> Unit,
    onOpenTransaction: (String) -> Unit,
    /** Opens the suggestion test bench for this notification. */
    onTrySuggestion: (() -> Unit)? = null,
) {
    var item by remember(id) { mutableStateOf<NotificationItem?>(null) }
    var loaded by remember(id) { mutableStateOf(false) }
    var linkedTo by remember(id) { mutableStateOf<Set<String>>(emptySet()) }
    val scope = rememberCoroutineScope()
    val linkedMessage = stringResource(Res.string.similar_link_done)

    suspend fun reloadLinks() {
        // One notification links to at most one transaction in practice, but
        // the table is many-to-many, so ask which transactions already carry
        // this ref rather than assuming.
        linkedTo = ledger.transactionsForSource(EventSource.Notification, id)
    }

    LaunchedEffect(id) {
        item = notifications.get(id)
        reloadLinks()
        loaded = true
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AppTopBar(
                title = stringResource(Res.string.notification_detail_title),
                onNavigateBack = onNavigateBack,
                actions = {
                    // A dry run of the notification→transaction path: it opens
                    // the trace, never the ledger.
                    onTrySuggestion?.let { open ->
                        TextButton(onClick = open) {
                            Text(stringResource(Res.string.suggest_debug_action))
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        val current = item
        if (!loaded || current == null) {
            Column(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(32.dp))
                if (!loaded) CircularProgressIndicator()
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(RowRadius),
                color = rowTint(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val bitmap = remember(current.appIcon) {
                            current.appIcon?.let { decodeIcon(it) }
                        }
                        if (bitmap != null) {
                            Image(
                                bitmap = bitmap,
                                contentDescription = stringResource(
                                    Res.string.notifications_app_icon_content,
                                    current.appName,
                                ),
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(current.appName.censored(), style = MaterialTheme.typography.titleMedium)
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(current.title.censored(), style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(6.dp))
                    Text(current.text.censored(), style = MaterialTheme.typography.bodyMedium)
                    current.category?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(Res.string.notifications_category, it),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        // "Miércoles, 16 de septiembre · 00:05" rather than
                        // the raw epoch-shaped stamp: the same day language
                        // the journal and the capture list are read in.
                        dayLabel(dayGroup(current.postTime)) + " · " + timeShort(current.postTime),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
            SimilarSection(
                embeddings = embeddings,
                title = stringResource(Res.string.similar_notifications),
                kind = EmbedKind.Notification,
                id = id,
                allowPackageFilter = true,
                onOpen = { onOpenNotification(it.id) },
            )

            Spacer(Modifier.height(24.dp))
            SimilarSection(
                embeddings = embeddings,
                title = stringResource(Res.string.similar_transactions),
                kind = EmbedKind.Notification,
                id = id,
                into = EmbedKind.Transaction,
                onOpen = { onOpenTransaction(it.id) },
                linkedRefs = linkedTo,
                reloadKey = linkedTo,
                onLink = { match ->
                    scope.launch {
                        try {
                            ledger.associate(
                                listOf(
                                    AssociateOp(
                                        transactionId = match.id,
                                        sources = listOf(
                                            TransactionSource(EventSource.Notification, id),
                                        ),
                                    ),
                                ),
                            )
                            reloadLinks()
                            Feedback.show(linkedMessage)
                        } catch (e: Throwable) {
                            if (e is CancellationException) throw e
                            Feedback.show(e.message ?: e.toString())
                        }
                    }
                },
            )
            Spacer(Modifier.height(32.dp))
        }
    }
}
