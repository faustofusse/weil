@file:OptIn(ExperimentalMaterial3Api::class)

package ar.fausto.weil

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import weil.app.sharedui.generated.resources.suggest_debug_action
import weil.app.sharedui.generated.resources.suggest_sources_title

/**
 * Las fuentes de una sugerencia: los vecinos por vector en las tres tablas
 * embebidas — notificaciones, emails y transacciones — de la captura que se
 * está mirando, sea un aviso o un mail.
 *
 * Es la antesala del laboratorio: lo que la etapa de recuperación le da al
 * matcher y a Jev, crudo y con sus propios botones de vincular. El botón de
 * arriba corre la pipeline completa sobre esta captura y abre el trazo
 * (`SuggestDebugScreen`), donde estos mismos vecinos aparecen resumidos como
 * precedentes — mirarlos acá primero es mirar qué tenía disponible antes de
 * preguntarle a los modelos qué hizo con ellos.
 *
 * Vincular escribe provenance — la única escritura de esta pantalla — porque
 * un vínculo manual es un ejemplo etiquetado para todas las corridas
 * futuras, incluidas las silenciosas del listener.
 */
@Composable
fun SuggestSourcesScreen(
    embeddings: EmbeddingsRepository,
    ledger: TransactionsRepository,
    id: String,
    /**
     * Which door the captured message came through. It decides both which
     * table the vectors are read from and which kind of provenance a
     * «vincular» writes — the screen is otherwise identical for a push alert
     * and for an email receipt.
     */
    source: EventSource = EventSource.Notification,
    onNavigateBack: () -> Unit,
    /** Runs the full suggestion pipeline and opens its trace. */
    onTrySuggestion: () -> Unit,
    onOpenNotification: (String) -> Unit = {},
    onOpenEmail: (String) -> Unit = {},
    onOpenTransaction: (String) -> Unit = {},
) {
    var linkedTo by remember(source, id) { mutableStateOf<Set<String>>(emptySet()) }
    // The row on screen lives in one of the two embedded message tables; its
    // neighbours of the same kind are the precedents the pipeline uses.
    val ownKind = if (source == EventSource.Email) EmbedKind.Email else EmbedKind.Notification
    val scope = rememberCoroutineScope()
    val linkedMessage = stringResource(Res.string.similar_link_done)

    suspend fun reloadLinks() {
        // The linking buttons live on the transactions section, and "linked"
        // means "already carries this message as a source" — the same one
        // question every other detail screen asks.
        linkedTo = ledger.transactionsForSource(source, id)
    }

    LaunchedEffect(source, id) { reloadLinks() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AppTopBar(
                title = stringResource(Res.string.suggest_sources_title),
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
            Button(
                onClick = onTrySuggestion,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(Res.string.suggest_debug_action))
            }

            Spacer(Modifier.height(24.dp))
            SimilarSection(
                embeddings = embeddings,
                title = stringResource(Res.string.similar_notifications),
                kind = ownKind,
                id = id,
                into = EmbedKind.Notification,
                // Only meaningful when the rows listed are notifications.
                allowPackageFilter = ownKind == EmbedKind.Notification,
                onOpen = { onOpenNotification(it.id) },
            )

            Spacer(Modifier.height(24.dp))
            SimilarSection(
                embeddings = embeddings,
                title = stringResource(Res.string.similar_emails),
                kind = ownKind,
                id = id,
                into = EmbedKind.Email,
                onOpen = { onOpenEmail(it.id) },
            )

            Spacer(Modifier.height(24.dp))
            SimilarSection(
                embeddings = embeddings,
                title = stringResource(Res.string.similar_transactions),
                kind = ownKind,
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
                                            TransactionSource(source, id),
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
