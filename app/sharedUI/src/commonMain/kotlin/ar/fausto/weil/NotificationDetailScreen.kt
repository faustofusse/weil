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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import weil.app.sharedui.generated.resources.Res
import weil.app.sharedui.generated.resources.notification_detail_title
import weil.app.sharedui.generated.resources.notification_related
import weil.app.sharedui.generated.resources.notification_related_none
import weil.app.sharedui.generated.resources.notifications_app_icon_content
import weil.app.sharedui.generated.resources.notifications_category
import weil.app.sharedui.generated.resources.suggest_debug_action

/**
 * Vista completa de una notificación capturada, con sus transacciones
 * relacionadas.
 *
 * "Relacionadas" es el provenance hecho lista: las filas del ledger que ya
 * llevan esta notificación como origen (`transaction_sources`). Uno o cero en
 * la práctica, pero la tabla es many-to-many y la lista miente menos que un
 * supuesto. Las parecidas —los vecinos por vector, con sus botones de
 * vincular— viven en el laboratorio («Probar sugerencia», el ícono del top
 * bar), que es donde son útiles: ahí son visibles como entrada de la pipeline
 * antes de que exista la fila que vincular.
 */
@Composable
fun NotificationDetailScreen(
    notifications: NotificationsRepository,
    ledger: TransactionsRepository,
    accounts: AccountsRepository,
    id: String,
    onNavigateBack: () -> Unit,
    onOpenTransaction: (String) -> Unit,
    /** Opens the suggestion lab for this notification. */
    onTrySuggestion: (() -> Unit)? = null,
) {
    var item by remember(id) { mutableStateOf<NotificationItem?>(null) }
    var related by remember(id) { mutableStateOf<List<Transaction>>(emptyList()) }
    var names by remember(id) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var types by remember(id) { mutableStateOf<Map<String, AccountType>>(emptyMap()) }
    var loaded by remember(id) { mutableStateOf(false) }

    LaunchedEffect(id) {
        // One notification links to at most one transaction in practice, but
        // the table is many-to-many, so ask which transactions already carry
        // this ref rather than assuming.
        val linked = ledger.transactionsForSource(EventSource.Notification, id)
        related = linked.mapNotNull { ledger.get(it) }
        val nodes = accounts.tree().flatMap { it.selfAndDescendants }
        names = nodes.associate { it.account.id to it.account.name }
        types = nodes.associate { it.account.id to it.account.type }
        item = notifications.get(id)
        loaded = true
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AppTopBar(
                title = stringResource(Res.string.notification_detail_title),
                onNavigateBack = onNavigateBack,
                actions = {
                    // The suggestion lab: the trace of the whole
                    // notification→transaction path plus the raw neighbours,
                    // never the ledger.
                    onTrySuggestion?.let { open ->
                        IconButton(onClick = open) {
                            Icon(
                                Icons.Filled.Science,
                                contentDescription = stringResource(Res.string.suggest_debug_action),
                            )
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
            Text(
                stringResource(Res.string.notification_related),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(8.dp))
            Surface(
                shape = RoundedCornerShape(RowRadius),
                color = rowTint(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (related.isEmpty()) {
                    Text(
                        stringResource(Res.string.notification_related_none),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                    )
                } else {
                    Column {
                        related.forEachIndexed { index, tx ->
                            if (index > 0) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(start = 16.dp),
                                    // Same ink as the slab, one step stronger —
                                    // the divider treatment of every other
                                    // list-on-a-slab in the app.
                                    color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.12f),
                                )
                            }
                            TransactionRow(
                                tx = tx,
                                names = names,
                                types = types,
                                onOpen = { onOpenTransaction(tx.id) },
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}
