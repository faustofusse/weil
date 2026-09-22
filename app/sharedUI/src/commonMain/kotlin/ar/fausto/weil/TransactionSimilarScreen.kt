@file:OptIn(ExperimentalMaterial3Api::class)

package ar.fausto.weil

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import weil.app.sharedui.generated.resources.Res
import weil.app.sharedui.generated.resources.similar_emails
import weil.app.sharedui.generated.resources.similar_link_done
import weil.app.sharedui.generated.resources.similar_notifications
import weil.app.sharedui.generated.resources.similar_transactions
import weil.app.sharedui.generated.resources.txn_similar_title

/**
 * The lab bench for one transaction: the same vector-neighbour view a
 * captured notification or email gets from its own flask icon
 * ([SuggestSourcesScreen]), just read from the transaction's embedding
 * instead. Moved off [TransactionDetailScreen] on purpose — the read-only
 * ledger view is where you check what got recorded, not where you go
 * hunting for precedents.
 *
 * Linking writes provenance the same way [SuggestSourcesScreen] does: a
 * manual link is a labelled example for every future run of the matcher.
 */
@Composable
fun TransactionSimilarScreen(
    embeddings: EmbeddingsRepository,
    ledger: TransactionsRepository,
    id: String,
    onNavigateBack: () -> Unit,
    onOpenTransaction: (String) -> Unit = {},
    onOpenNotification: (String) -> Unit = {},
    onOpenEmail: (String) -> Unit = {},
) {
    var linkedRefs by remember(id) { mutableStateOf<Set<String>>(emptySet()) }
    val scope = rememberCoroutineScope()
    val linkedMessage = stringResource(Res.string.similar_link_done)

    suspend fun reloadLinks() {
        linkedRefs = ledger.sources(id).map { it.ref }.toSet()
    }

    fun link(kind: EventSource, ref: String) {
        scope.launch {
            try {
                ledger.associate(listOf(AssociateOp(id, listOf(TransactionSource(kind, ref)))))
                reloadLinks()
                Feedback.show(linkedMessage)
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                Feedback.show(e.message ?: e.toString())
            }
        }
    }

    androidx.compose.runtime.LaunchedEffect(id) { reloadLinks() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AppTopBar(
                title = stringResource(Res.string.txn_similar_title),
                onNavigateBack = onNavigateBack,
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            SimilarSection(
                embeddings = embeddings,
                title = stringResource(Res.string.similar_transactions),
                kind = EmbedKind.Transaction,
                id = id,
                onOpen = { onOpenTransaction(it.id) },
            )

            Spacer(Modifier.height(24.dp))
            SimilarSection(
                embeddings = embeddings,
                title = stringResource(Res.string.similar_notifications),
                kind = EmbedKind.Transaction,
                id = id,
                into = EmbedKind.Notification,
                onOpen = { onOpenNotification(it.id) },
                linkedRefs = linkedRefs,
                reloadKey = linkedRefs,
                onLink = { match -> link(EventSource.Notification, match.id) },
            )

            Spacer(Modifier.height(24.dp))
            SimilarSection(
                embeddings = embeddings,
                title = stringResource(Res.string.similar_emails),
                kind = EmbedKind.Transaction,
                id = id,
                into = EmbedKind.Email,
                onOpen = { onOpenEmail(it.id) },
                linkedRefs = linkedRefs,
                reloadKey = linkedRefs,
                onLink = { match -> link(EventSource.Email, match.id) },
            )

            Spacer(Modifier.height(32.dp))
        }
    }
}
