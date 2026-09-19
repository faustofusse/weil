@file:OptIn(ExperimentalMaterial3Api::class)

package ar.fausto.weil

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import weil.app.sharedui.generated.resources.Res
import weil.app.sharedui.generated.resources.action_back
import weil.app.sharedui.generated.resources.action_delete
import weil.app.sharedui.generated.resources.action_edit
import weil.app.sharedui.generated.resources.action_undo
import weil.app.sharedui.generated.resources.editor_deleted_payee_fallback
import weil.app.sharedui.generated.resources.editor_transaction_deleted
import weil.app.sharedui.generated.resources.journal_more_postings
import weil.app.sharedui.generated.resources.similar_emails
import weil.app.sharedui.generated.resources.similar_link_done
import weil.app.sharedui.generated.resources.similar_notifications
import weil.app.sharedui.generated.resources.similar_transactions
import weil.app.sharedui.generated.resources.txn_detail_postings
import weil.app.sharedui.generated.resources.txn_detail_title
import weil.app.sharedui.generated.resources.txn_source_document
import weil.app.sharedui.generated.resources.txn_source_email
import weil.app.sharedui.generated.resources.txn_source_manual
import weil.app.sharedui.generated.resources.txn_source_notification
import weil.app.sharedui.generated.resources.txn_source_qr
import weil.app.sharedui.generated.resources.txn_source_whatsapp
import weil.app.sharedui.generated.resources.txn_sources_title

/**
 * Attaches a message to this transaction as one more origin.
 *
 * Cosine similarity is **not** a decision that these are the same event (two
 * Rappi orders are near-identical vectors), which is why this only ever runs
 * from an explicit tap on a row that shows its own amount and day.
 */
private suspend fun linkSource(
    transactionId: String,
    kind: EventSource,
    ref: String,
    ledger: TransactionsRepository,
    onDone: suspend () -> Unit,
) {
    try {
        ledger.associate(listOf(AssociateOp(transactionId, listOf(TransactionSource(kind, ref)))))
        onDone()
    } catch (e: Throwable) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        Feedback.show(e.message ?: e.toString())
    }
}

/** Nombre visible de cada puerta de entrada; `EventSource` vive sin traducir. */
@Composable
private fun sourceLabel(kind: EventSource): String = stringResource(
    when (kind) {
        EventSource.Document -> Res.string.txn_source_document
        EventSource.Notification -> Res.string.txn_source_notification
        EventSource.Email -> Res.string.txn_source_email
        EventSource.WhatsApp -> Res.string.txn_source_whatsapp
        EventSource.Qr -> Res.string.txn_source_qr
        EventSource.Manual -> Res.string.txn_source_manual
    },
)

/**
 * Vista de solo lectura de una transacción. El botón de editar lleva al editor completo.
 */
@Composable
fun TransactionDetailScreen(
    ledger: TransactionsRepository,
    accounts: AccountsRepository,
    embeddings: EmbeddingsRepository,
    id: String,
    onNavigateBack: () -> Unit,
    onNavigateToEdit: (id: String) -> Unit,
    onNavigateToAccount: (id: String) -> Unit,
    onOpenTransaction: (id: String) -> Unit = {},
    onOpenNotification: (id: String) -> Unit = {},
    onOpenEmail: (id: String) -> Unit = {},
) {
    var tx by remember { mutableStateOf<Transaction?>(null) }
    // Where this transaction came from. A single purchase legitimately has a
    // push alert, a mail receipt and a statement row, so this is a list.
    var sources by remember { mutableStateOf<List<StoredSource>>(emptyList()) }
    var paths by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var types by remember { mutableStateOf<Map<String, AccountType>>(emptyMap()) }
    var loaded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val undoLabel = stringResource(Res.string.action_undo)
    val deletedMessage = stringResource(Res.string.editor_transaction_deleted)
    val deletedPayee = stringResource(Res.string.editor_deleted_payee_fallback)
    val linkedMessage = stringResource(Res.string.similar_link_done)
    // Refs already attached to this transaction, so the neighbour lists show
    // "vinculada" instead of offering the same link twice.
    val linkedRefs = sources.map { it.ref }.toSet()

    suspend fun load() {
        try {
            val fetched = ledger.get(id)
            if (fetched == null) {
                onNavigateBack()
                return
            }
            tx = fetched
            sources = ledger.sources(id)
            paths = accountPaths(accounts)
            types = accountTypes(accounts)
            loaded = true
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            error = e.message ?: e.toString()
            loaded = true
        }
    }

    LaunchedEffect(id) { load() }

    // Recargar cuando el ledger cambie (p.ej., editado desde otra pantalla).
    LaunchedEffect(Unit) {
        ledger.changes.collect {
            val fetched = ledger.get(id)
            if (fetched == null) {
                onNavigateBack()
            } else {
                tx = fetched
                sources = ledger.sources(id)
                paths = accountPaths(accounts)
                types = accountTypes(accounts)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.txn_detail_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(Res.string.action_back))
                    }
                },
                actions = {
                    TextButton(
                        onClick = {
                            scope.launch {
                                try {
                                    val stored = ledger.get(id)
                                    val draftsBackup = stored?.postings?.map {
                                        DraftPosting(
                                            it.accountId,
                                            formatMinorUnits(it.amountMinor),
                                            it.commodity,
                                        )
                                    }.orEmpty()
                                    ledger.delete(id)
                                    onNavigateBack()
                                    Feedback.undoable(deletedMessage, undoLabel) {
                                        ledger.add(
                                            stored?.date ?: epochMillis(),
                                            stored?.payee.orEmpty().ifBlank { deletedPayee },
                                            stored?.note,
                                            draftsBackup,
                                            timeKnown = stored?.timeKnown ?: true,
                                        )
                                    }
                                } catch (e: Throwable) {
                                    if (e is kotlinx.coroutines.CancellationException) throw e
                                    error = e.message ?: e.toString()
                                }
                            }
                        },
                    ) {
                        Icon(Icons.Filled.Delete, contentDescription = stringResource(Res.string.action_delete))
                    }
                },
            )
        },
        floatingActionButton = {
            if (tx != null) {
                ExtendedFloatingActionButton(
                    onClick = { onNavigateToEdit(id) },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ) {
                    Icon(Icons.Filled.Edit, contentDescription = null)
                    Spacer(Modifier.padding(start = 8.dp))
                    Text(stringResource(Res.string.action_edit))
                }
            }
        },
    ) { innerPadding ->
        if (!loaded) {
            // Skeleton / spinner mientras carga.
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator()
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                error?.let {
                    ErrorBanner(it, modifier = Modifier.padding(vertical = 8.dp))
                }

                val transaction = tx
                if (transaction != null) {
                    // Cabecera: payee + fecha/hora + nota.
                    Text(
                        transaction.payee.censored(),
                        style = MaterialTheme.typography.headlineSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    val dayText = dayLabel(dayGroup(transaction.date))
                    // Only the day is known for rows imported from a document.
                    val timeText = if (transaction.timeKnown) " " + timeShort(transaction.date) else ""
                    Text(
                        dayText + timeText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    transaction.note?.censored()?.takeIf { it.isNotBlank() }?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Spacer(Modifier.height(16.dp))

                    // Monto protagonista (flow).
                    val flow = flowOf(transaction, types)
                    if (flow != null) {
                        Text(
                            // Not a row in a list — the one figure on the
                            // page keeps its minus alongside the color.
                            formatMoney(flow.amountMinor, flow.commodity, signed = true),
                            style = MaterialTheme.typography.displaySmall,
                            color = flowColor(flow.direction),
                        )
                        // Línea origen → destino.
                        val from = flow.fromId?.let { paths[it] }
                        val to = flow.toId?.let { paths[it] }
                        val route = when {
                            from != null && to != null && from != to -> "$from $ROUTE_ARROW $to"
                            else -> from ?: to
                        }
                        if (route != null) {
                            Text(
                                route,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    Spacer(Modifier.height(24.dp))

                    // Partidas.
                    Text(
                        stringResource(Res.string.txn_detail_postings),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        shape = RoundedCornerShape(GroupRadius),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column {
                            transaction.postings.forEachIndexed { index, posting ->
                                if (index > 0) {
                                    androidx.compose.material3.HorizontalDivider(
                                        modifier = Modifier.padding(start = 16.dp),
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                                    )
                                }
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onNavigateToAccount(posting.accountId) }
                                        .heightIn(min = 56.dp)
                                        .padding(horizontal = 16.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        paths[posting.accountId] ?: posting.accountId,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text(
                                        // A posting line is the raw
                                        // double-entry view, where which side
                                        // of the book a leg sits on is the
                                        // whole point: signed, always.
                                        formatMoney(posting.amountMinor, posting.commodity, signed = true),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = postingColor(types[posting.accountId], posting.amountMinor),
                                        modifier = Modifier.padding(start = 12.dp),
                                    )
                                }
                            }
                        }
                    }

                    // Totales por moneda (solo si hay más de una).
                    val totals = transaction.postings.groupBy({ it.commodity }, { it.amountMinor })
                        .mapValues { (_, amounts) -> amounts.sum() }
                    if (totals.size > 1) {
                        Spacer(Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            totals.forEach { (commodity, amount) ->
                                Text(
                                    formatMoney(amount, commodity, signed = true),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    if (sources.isNotEmpty()) {
                        Spacer(Modifier.height(24.dp))
                        Text(
                            stringResource(Res.string.txn_sources_title),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(8.dp))
                        Surface(
                            shape = RoundedCornerShape(GroupRadius),
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column {
                                sources.forEachIndexed { index, origin ->
                                    if (index > 0) {
                                        androidx.compose.material3.HorizontalDivider(
                                            modifier = Modifier.padding(start = 16.dp),
                                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                                        )
                                    }
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(min = 48.dp)
                                            .padding(horizontal = 16.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            sourceLabel(origin.kind),
                                            style = MaterialTheme.typography.bodyMedium,
                                            modifier = Modifier.weight(1f),
                                        )
                                        // When it was linked, which is not the
                                        // transaction's date: a statement
                                        // imported in September can attach to
                                        // a movement from August.
                                        Text(
                                            dayLabel(dayGroup(origin.createdAt)),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Vecinos por similitud (offline: los vectores ya están
                    // guardados). Los de otras tablas se pueden vincular como
                    // origen, que es lo que llena el corpus de ejemplos.
                    Spacer(Modifier.height(24.dp))
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
                        onLink = { match ->
                            scope.launch {
                                linkSource(id, EventSource.Notification, match.id, ledger) {
                                    sources = ledger.sources(id)
                                    Feedback.show(linkedMessage)
                                }
                            }
                        },
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
                        onLink = { match ->
                            scope.launch {
                                linkSource(id, EventSource.Email, match.id, ledger) {
                                    sources = ledger.sources(id)
                                    Feedback.show(linkedMessage)
                                }
                            }
                        },
                    )

                    // Padding inferior para que el FAB no tape contenido.
                    Spacer(Modifier.height(80.dp))
                }
            }
        }
    }
}
