@file:OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package ar.fausto.weil

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import weil.app.sharedui.generated.resources.txn_detail_postings
import weil.app.sharedui.generated.resources.txn_detail_title
import weil.app.sharedui.generated.resources.txn_source_document
import weil.app.sharedui.generated.resources.txn_source_email
import weil.app.sharedui.generated.resources.txn_source_manual
import weil.app.sharedui.generated.resources.txn_source_notification
import weil.app.sharedui.generated.resources.txn_source_qr
import weil.app.sharedui.generated.resources.txn_source_unlink
import weil.app.sharedui.generated.resources.txn_source_unlinked
import weil.app.sharedui.generated.resources.txn_similar_action
import weil.app.sharedui.generated.resources.txn_source_broker
import weil.app.sharedui.generated.resources.txn_source_whatsapp
import weil.app.sharedui.generated.resources.txn_sources_title

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
        EventSource.Broker -> Res.string.txn_source_broker
    },
)

/**
 * Vista de solo lectura de una transacción. El botón de editar lleva al editor completo.
 */
@Composable
fun TransactionDetailScreen(
    ledger: TransactionsRepository,
    accounts: AccountsRepository,
    id: String,
    onNavigateBack: () -> Unit,
    onNavigateToEdit: (id: String) -> Unit,
    onNavigateToAccount: (id: String) -> Unit,
    onOpenNotification: (id: String) -> Unit = {},
    onOpenEmail: (id: String) -> Unit = {},
    onOpenDocument: (docId: String) -> Unit = {},
    /** Opens the vector-neighbour lab for this transaction. */
    onTrySuggestion: (() -> Unit)? = null,
    /** Instrument descriptions, so a MELI leg reads "13 MELI" and not as money. */
    valuation: Valuation = Valuation(),
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
    val unlinkLabel = stringResource(Res.string.txn_source_unlink)
    val unlinkedMessage = stringResource(Res.string.txn_source_unlinked)

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
                    onTrySuggestion?.let { open ->
                        IconButton(onClick = open) {
                            Icon(
                                Icons.Filled.Science,
                                contentDescription = stringResource(Res.string.txn_similar_action),
                            )
                        }
                    }
                    TextButton(
                        onClick = {
                            scope.launch {
                                try {
                                    val stored = ledger.get(id)
                                    val draftsBackup = stored?.postings?.map { it.toDraft() }.orEmpty()
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
                                        formatAmount(posting.amountMinor, posting.commodity, valuation, signed = true) +
                                            // Ledger's @: the price per unit, when it's an exchange.
                                            (unitPriceText(posting, valuation)?.let { " @ $it" } ?: ""),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = postingColor(types[posting.accountId], posting.amountMinor),
                                        modifier = Modifier.padding(start = 12.dp),
                                    )
                                }
                            }
                        }
                    }

                    // Totales por moneda, por *peso* (el costo cuando lo hay):
                    // una compra con costo cierra en cero y no muestra nada;
                    // sólo queda lo que de verdad no cancela, como un cambio
                    // de moneda viejo cargado sin costo.
                    val totals = transaction.postings.groupBy({ it.weight().commodity }, { it.weight().minorUnits })
                        .mapValues { (_, amounts) -> amounts.sum() }
                        .filterValues { it != 0L }
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
                        // Un toque abre el origen; el tacho de borrar el
                        // vínculo solo aparece tras un long-press ("armar"
                        // la fila), porque un tap perdido no debe poder
                        // desvincular una fuente por accidente.
                        var armedSource by remember { mutableStateOf<StoredSource?>(null) }
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
                                    // Solo las fuentes con pantalla propia son
                                    // navegables; WhatsApp/QR no tienen vista de
                                    // detalle a la que ir.
                                    val originRef = origin.ref
                                    val onOpenOrigin: (() -> Unit)? = when (origin.kind) {
                                        EventSource.Notification -> ({ onOpenNotification(originRef) })
                                        EventSource.Email -> ({ onOpenEmail(originRef) })
                                        EventSource.Document -> ({ onOpenDocument(originRef) })
                                        else -> null
                                    }
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .combinedClickable(
                                                onClick = { onOpenOrigin?.invoke() },
                                                onLongClick = {
                                                    armedSource = if (armedSource == origin) null else origin
                                                },
                                            )
                                            .heightIn(min = 48.dp)
                                            .padding(horizontal = 16.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        if (onOpenOrigin != null) {
                                            Icon(
                                                when (origin.kind) {
                                                    EventSource.Notification -> Icons.Filled.Notifications
                                                    EventSource.Document -> Icons.Filled.DocumentScanner
                                                    else -> Icons.Filled.Email
                                                },
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier
                                                    .padding(end = 12.dp)
                                                    .size(18.dp),
                                            )
                                        }
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
                                        if (onOpenOrigin != null) {
                                            Icon(
                                                Icons.Filled.ChevronRight,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier
                                                    .padding(start = 4.dp)
                                                    .size(18.dp),
                                            )
                                        }
                                        if (armedSource == origin) {
                                            IconButton(
                                                onClick = {
                                                    armedSource = null
                                                    scope.launch {
                                                        ledger.unlink(id, origin)
                                                        sources = ledger.sources(id)
                                                        Feedback.undoable(unlinkedMessage, undoLabel) {
                                                            ledger.associate(
                                                                listOf(
                                                                    AssociateOp(
                                                                        id,
                                                                        listOf(
                                                                            TransactionSource(
                                                                                origin.kind,
                                                                                origin.ref,
                                                                                origin.eventKey,
                                                                            ),
                                                                        ),
                                                                    ),
                                                                ),
                                                            )
                                                            sources = ledger.sources(id)
                                                        }
                                                    }
                                                },
                                                modifier = Modifier.size(36.dp),
                                            ) {
                                                Icon(
                                                    Icons.Filled.Close,
                                                    contentDescription = unlinkLabel,
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.size(18.dp),
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Padding inferior para que el FAB no tape contenido.
                    Spacer(Modifier.height(80.dp))
                }
            }
        }
    }
}

/**
 * Ledger's `@`: what one unit of [posting] cost ("13 MELI @ $ 24.558,91"),
 * from the stored total (`cost_minor`) over the quantity. At least 2
 * decimals, more the smaller the price: a letra's unit is a fraction of a
 * peso and rounding it to cents would say nothing.
 */
internal fun unitPriceText(posting: Posting, valuation: Valuation): String? {
    val cost = posting.costMinor ?: return null
    val costCommodity = posting.costCommodity ?: return null
    if (posting.amountMinor == 0L) return null
    val scale = valuation.commodities[posting.commodity]?.scale ?: 2
    val exact = Decimal.ofMinorUnits(kotlin.math.abs(cost), 2)
        .divide(Decimal.ofMinorUnits(kotlin.math.abs(posting.amountMinor), scale), 6)
    return formatPrice(exact, costCommodity)
}

/**
 * A price in [commodity] ("$ 24.558,91", "$ 106,251"): at least 2 decimals,
 * more the smaller the price: a letra's unit is a fraction of a peso and
 * rounding it to cents would say nothing. [exact] keeps every decimal the
 * price already has (a quote as the market states it, "$ 106,251") instead
 * of rounding a computed one.
 */
internal fun formatPrice(price: Decimal, commodity: String, exact: Boolean = false): String {
    val magnitude = price.abs()
    val decimals = when {
        exact -> maxOf(0, magnitude.scale)
        magnitude >= Decimal.of(100) -> 2
        magnitude >= Decimal.of(1) -> 4
        else -> 6
    }
    val plain = magnitude.rescale(decimals).stripTrailingZeros().toPlainString()
    val whole = plain.substringBefore('.')
    val frac = plain.substringAfter('.', "").padEnd(2, '0')
    val grouped = whole.reversed().chunked(3).joinToString(".").reversed()
    return "${if (price.signum < 0) "-" else ""}${currencySymbol(commodity)} $grouped,$frac"
}
