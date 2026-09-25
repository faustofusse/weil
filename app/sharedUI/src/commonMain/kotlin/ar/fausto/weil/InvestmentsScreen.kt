package ar.fausto.weil

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import kotlin.math.abs
import kotlinx.coroutines.flow.merge
import weil.app.sharedui.generated.resources.investments_add
import weil.app.sharedui.generated.resources.investments_brokers
import weil.app.sharedui.generated.resources.investments_connect_here
import weil.app.sharedui.generated.resources.investments_never_synced
import weil.app.sharedui.generated.resources.investments_recent
import weil.app.sharedui.generated.resources.investments_synced_days
import weil.app.sharedui.generated.resources.investments_synced_hours
import weil.app.sharedui.generated.resources.investments_synced_minutes
import weil.app.sharedui.generated.resources.investments_synced_now
import weil.app.sharedui.generated.resources.investments_unrealized
import weil.app.sharedui.generated.resources.investments_value
import weil.app.sharedui.generated.resources.net_worth_official
import org.jetbrains.compose.resources.stringResource
import weil.app.sharedui.generated.resources.Res
import weil.app.sharedui.generated.resources.action_cancel
import weil.app.sharedui.generated.resources.investments_empty_body
import weil.app.sharedui.generated.resources.investments_empty_title
import weil.app.sharedui.generated.resources.investments_soon
import weil.app.sharedui.generated.resources.investments_soon_message
import weil.app.sharedui.generated.resources.investments_source_ibkr
import weil.app.sharedui.generated.resources.investments_source_ibkr_hint
import weil.app.sharedui.generated.resources.investments_source_iol
import weil.app.sharedui.generated.resources.investments_source_iol_hint
import weil.app.sharedui.generated.resources.investments_source_statement
import weil.app.sharedui.generated.resources.investments_source_statement_hint
import weil.app.sharedui.generated.resources.investments_title
import weil.app.sharedui.generated.resources.iol_connect_action
import weil.app.sharedui.generated.resources.iol_connect_body
import weil.app.sharedui.generated.resources.iol_connect_title
import weil.app.sharedui.generated.resources.iol_disconnect_action
import weil.app.sharedui.generated.resources.iol_disconnect_body
import weil.app.sharedui.generated.resources.iol_disconnect_title
import weil.app.sharedui.generated.resources.iol_password
import weil.app.sharedui.generated.resources.iol_sync
import weil.app.sharedui.generated.resources.iol_up_to_date
import weil.app.sharedui.generated.resources.iol_username
import weil.app.sharedui.generated.resources.iol_wrong_credentials

/**
 * The investments tab (plans/inversiones-brokers.md, section UI).
 *
 * With no broker connected: the ways one gets in. With one or more: what
 * the investments are worth (per currency, the official-dollar line and the
 * unrealized gain), one row per broker, the positions consolidated across
 * brokers, the latest movements, and the remaining ways in at the bottom.
 * Everything is read from the ledger — a broker connected on another device
 * shows here too; only syncing it needs the credentials on this one.
 *
 * Pull-to-refresh syncs the ledger and IOL: new movements open the review
 * ([BrokerImportScreen]), nothing new is a «todo al día».
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InvestmentsScreen(
    ledgerState: LedgerState,
    iol: IolRepository,
    brokers: BrokersRepository,
    onReviewImport: (BrokerImportRoute) -> Unit,
    onOpenAccount: (id: String, commodity: String?) -> Unit,
    onOpenTransaction: (id: String) -> Unit,
    bottomBar: @Composable () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val ledger = ledgerState.ledger
    val soon = stringResource(Res.string.investments_soon_message)
    val wrongCredentials = stringResource(Res.string.iol_wrong_credentials)
    val upToDate = stringResource(Res.string.iol_up_to_date)
    // The secure store isn't observable; re-read after every change made here.
    var iolUser by remember { mutableStateOf(iol.username.takeIf { iol.hasCredentials }) }
    var connecting by remember { mutableStateOf(false) }
    var syncing by remember { mutableStateOf(false) }
    var confirmDisconnect by remember { mutableStateOf(false) }

    var connections by remember { mutableStateOf<List<BrokerConnection>?>(null) }
    var holdings by remember { mutableStateOf<Map<String, Map<String, HeldPosition>>>(emptyMap()) }
    var recent by remember { mutableStateOf<List<Transaction>>(emptyList()) }

    // A cold start straight into this tab has no tree nor valuation yet.
    LaunchedEffect(Unit) { if (!ledgerState.loaded) ledgerState.refresh() }

    suspend fun load() {
        val found = runCatching { brokers.connections() }.getOrNull() ?: return
        holdings = found.associate { it.provider to ledger.holdings(it.accounts.holdings) }
        // Every account a broker books into on the asset side: its cash
        // accounts and its holdings (the income/expense/equity branches are
        // shared by all brokers and would drag in unrelated rows).
        val ids = found.flatMap { it.accounts.cash.values + it.accounts.holdings }.distinct()
        val txIds = ledger.register(subtreeIds = ids, limit = RECENT_SCAN)
            .map { it.posting.transactionId }.distinct().take(RECENT_SHOWN)
        val byId = ledger.getAll(txIds)
        recent = txIds.mapNotNull { byId[it] }
        connections = found
    }

    LaunchedEffect(ledgerState.tree) { load() }
    LaunchedEffect(Unit) {
        merge(ledger.changes, ledgerState.settings.changes).collect { load() }
    }

    /** Fetch + plan, then either the review or a «todo al día». */
    fun sync() {
        if (syncing) return
        syncing = true
        scope.launch {
            try {
                val plan = iol.preview()
                if (plan.transactions.isEmpty() && plan.differences.isEmpty() && plan.issues.isEmpty()) {
                    Feedback.show(upToDate)
                } else {
                    val accounts = brokers.accountsFor(IOL_PROVIDER) ?: error("IOL accounts missing after preview")
                    onReviewImport(BrokerImportRoute("IOL", IOL_PROVIDER, plan, accounts, brokers.scales()))
                }
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Feedback.show(if (e is IolAuthException) wrongCredentials else e.message ?: e.toString())
            } finally {
                syncing = false
            }
        }
    }

    val connected = connections.orEmpty()
    val valuation = ledgerState.valuation
    val positions = remember(holdings, valuation) {
        valuation.positions(consolidateHoldings(holdings.values.toList()))
    }
    val nodes = remember(ledgerState.tree) { ledgerState.tree.flatMap { it.selfAndDescendants } }
    val names = remember(nodes) { nodes.associate { it.account.id to it.account.name.censored() } }
    val types = remember(nodes) { nodes.associate { it.account.id to it.account.type } }
    val parents = remember(nodes) { nodes.associate { it.account.id to it.account.parentId } }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AppTopBar(title = stringResource(Res.string.investments_title)) {
                if (iolUser != null) {
                    if (syncing) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(horizontal = 14.dp).size(20.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        IconButton(onClick = { sync() }) {
                            Icon(Icons.Filled.Refresh, contentDescription = stringResource(Res.string.iol_sync))
                        }
                    }
                }
            }
        },
        bottomBar = bottomBar,
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = ledgerState.pullRefreshing || syncing,
            onRefresh = {
                ledgerState.refresh(userInitiated = true)
                if (iolUser != null) sync()
            },
            modifier = Modifier.fillMaxSize().padding(innerPadding),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                // Same bottom air as Inicio: the create button straddles the
                // bar's top edge and would otherwise cover the last row.
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 56.dp),
            ) {
                if (connections != null && connected.isEmpty()) {
                    item(key = "intro") {
                        Text(
                            stringResource(Res.string.investments_empty_title),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            stringResource(Res.string.investments_empty_body),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(20.dp))
                    }
                }
                if (connected.isNotEmpty()) {
                    item(key = "hero") {
                        // What the brokers' accounts are worth: their cash and
                        // their holdings, instruments at the last price.
                        val money = remember(connected, ledgerState.displayLeafTotals) {
                            val sum = mutableMapOf<String, Long>()
                            for (c in connected) {
                                for (id in c.accounts.cash.values + c.accounts.holdings) {
                                    ledgerState.displayLeafTotals[id]?.forEach { (k, v) -> sum[k] = (sum[k] ?: 0L) + v }
                                }
                            }
                            sum.filterValues { it != 0L }
                        }
                        InvestmentsHero(
                            money = money,
                            gains = remember(positions) { unrealizedTotals(positions) },
                            converted = remember(money, valuation, ledgerState.netWorthCurrency) {
                                valuation.convert(money, ledgerState.netWorthCurrency, epochMillis())
                            },
                            hidden = ledgerState.amountsHidden,
                        )
                    }
                    item(key = "brokers-header") { SectionHeader(title = stringResource(Res.string.investments_brokers)) }
                    items(connected, key = { "broker-${it.provider}" }) { c ->
                        val holdingsId = c.accounts.holdings
                        val rootId = parents[holdingsId]
                        val title = (rootId?.let { names[it] } ?: names[holdingsId]).orEmpty()
                        val value = (c.accounts.cash.values + holdingsId)
                            .flatMap { ledgerState.displayLeafTotals[it].orEmpty().entries }
                            .groupBy({ it.key }, { it.value })
                            .mapValues { it.value.sum() }
                            .filterValues { it != 0L }
                            .entries.sortedByDescending { abs(it.value) }
                        val isIol = c.provider == IOL_PROVIDER
                        AppListRow(
                            icon = Icons.Filled.TrendingUp,
                            paint = accountPaint(null),
                            title = title,
                            subtitle = when {
                                isIol && iolUser == null -> stringResource(Res.string.investments_connect_here)
                                else -> freshness(c.syncedAt)
                            },
                            onClick = {
                                if (isIol && iolUser == null) connecting = true else onOpenAccount(holdingsId, null)
                            },
                            onLongClick = if (isIol && iolUser != null) ({ confirmDisconnect = true }) else null,
                        ) {
                            Column(horizontalAlignment = Alignment.End) {
                                value.forEachIndexed { i, (commodity, minor) ->
                                    Text(
                                        maskedAmount(minor, commodity, ledgerState.amountsHidden),
                                        style = if (i == 0) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodySmall,
                                        fontWeight = if (i == 0) FontWeight.SemiBold else FontWeight.Normal,
                                        color = if (i == 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                    }
                    if (positions.isNotEmpty()) {
                        item(key = "positions") {
                            PositionsCard(
                                lines = positions,
                                selected = null,
                                // The broker that holds most of it; with one
                                // broker, simply its register for that instrument.
                                onSelect = { commodity ->
                                    commodity ?: return@PositionsCard
                                    val holder = connected.maxByOrNull {
                                        holdings[it.provider]?.get(commodity)?.quantityMinor ?: Long.MIN_VALUE
                                    }
                                    holder?.let { onOpenAccount(it.accounts.holdings, commodity) }
                                },
                            )
                        }
                    }
                    if (recent.isNotEmpty()) {
                        item(key = "recent-header") {
                            SectionHeader(title = stringResource(Res.string.investments_recent))
                        }
                        items(recent, key = { "tx-${it.id}" }) { tx ->
                            MovementRow(
                                tx = tx,
                                names = names,
                                types = types,
                                looks = ledgerState.looks,
                                hidden = ledgerState.amountsHidden,
                                onOpen = { onOpenTransaction(tx.id) },
                            )
                        }
                    }
                    item(key = "add-header") { SectionHeader(title = stringResource(Res.string.investments_add)) }
                }
                if (connections != null && connected.none { it.provider == IOL_PROVIDER }) {
                    item(key = "source-iol") {
                        AppListRow(
                            icon = Icons.Filled.TrendingUp,
                            paint = accountPaint(null),
                            title = stringResource(Res.string.investments_source_iol),
                            subtitle = stringResource(Res.string.investments_source_iol_hint),
                            onClick = { connecting = true },
                        )
                    }
                }
                if (connections != null) {
                    item(key = "source-ibkr") {
                        SoonRow(
                            icon = Icons.Filled.TrendingUp,
                            title = stringResource(Res.string.investments_source_ibkr),
                            hint = stringResource(Res.string.investments_source_ibkr_hint),
                            onClick = { Feedback.show(soon) },
                        )
                    }
                    item(key = "source-statement") {
                        SoonRow(
                            icon = Icons.Filled.DocumentScanner,
                            title = stringResource(Res.string.investments_source_statement),
                            hint = stringResource(Res.string.investments_source_statement_hint),
                            onClick = { Feedback.show(soon) },
                        )
                    }
                }
            }
        }
    }

    if (connecting) {
        IolConnectSheet(
            onConnect = { username, password -> iol.connect(username, password) },
            wrongCredentials = wrongCredentials,
            onConnected = {
                connecting = false
                iolUser = iol.username
                // The first sync is the reason anyone connects: go straight to it.
                sync()
            },
            onDismiss = { connecting = false },
        )
    }

    if (confirmDisconnect) {
        AlertDialog(
            onDismissRequest = { confirmDisconnect = false },
            title = { Text(stringResource(Res.string.iol_disconnect_title)) },
            text = { Text(stringResource(Res.string.iol_disconnect_body)) },
            confirmButton = {
                TextButton(onClick = {
                    iol.disconnect()
                    iolUser = null
                    confirmDisconnect = false
                }) { Text(stringResource(Res.string.iol_disconnect_action)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDisconnect = false }) { Text(stringResource(Res.string.action_cancel)) }
            },
        )
    }
}

/** How many register rows are scanned for [RECENT_SHOWN] distinct transactions (a trade has two). */
private const val RECENT_SCAN = 20
private const val RECENT_SHOWN = 5

/**
 * The tab's hero, Home's card language: the value per currency (largest
 * first, the rest smaller), the official-dollar line, and the unrealized
 * gain — computed on read, colored because a gain is the one figure here
 * whose sign is news.
 */
@Composable
private fun InvestmentsHero(
    money: Map<String, Long>,
    gains: List<UnrealizedTotal>,
    converted: ConvertedTotal?,
    hidden: Boolean,
) {
    val lines = money.entries.sortedByDescending { abs(it.value) }
    val ink = MaterialTheme.colorScheme.onPrimaryContainer
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(horizontal = 20.dp, vertical = 18.dp),
    ) {
        Text(stringResource(Res.string.investments_value), style = MaterialTheme.typography.titleMedium, color = ink)
        Spacer(Modifier.height(6.dp))
        val primary = lines.firstOrNull()
        Text(
            maskedAmount(primary?.value ?: 0L, primary?.key ?: Money.DEFAULT_COMMODITY, hidden),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        lines.drop(1).forEach { (commodity, minor) ->
            Text(
                maskedAmount(minor, commodity, hidden),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = ink.copy(alpha = 0.8f),
                maxLines = 1,
            )
        }
        gains.forEach { g ->
            Text(
                stringResource(Res.string.investments_unrealized, if (hidden) maskedAmount(g.gainMinor, g.commodity, true) else gainText(g.gainMinor, g.commodity, g.ratio)),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = when {
                    hidden || g.gainMinor == 0L -> ink.copy(alpha = 0.75f)
                    g.gainMinor > 0 -> MoneyColor.positive
                    else -> MoneyColor.negative
                },
                maxLines = 1,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        converted?.let { c ->
            Text(
                stringResource(
                    Res.string.net_worth_official,
                    maskedAmount(c.minor, c.commodity, hidden),
                    formatPrice(c.rate.price, c.rate.quoteCommodity, exact = true),
                    shortDate(c.rate.at),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = ink.copy(alpha = 0.75f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/** «Actualizado hace 5 min»: when the broker's last import was applied. */
@Composable
private fun freshness(syncedAt: Long?): String {
    if (syncedAt == null) return stringResource(Res.string.investments_never_synced)
    val minutes = (epochMillis() - syncedAt).coerceAtLeast(0) / 60_000
    return when {
        minutes < 1 -> stringResource(Res.string.investments_synced_now)
        minutes < 60 -> stringResource(Res.string.investments_synced_minutes, minutes.toInt())
        minutes < 48 * 60 -> stringResource(Res.string.investments_synced_hours, (minutes / 60).toInt())
        else -> stringResource(Res.string.investments_synced_days, (minutes / (60 * 24)).toInt())
    }
}

/**
 * Username and password, checked against IOL before anything is stored.
 * The copy says where they live because it is the first thing anyone
 * wonders when a finance app asks for a broker's password.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IolConnectSheet(
    onConnect: suspend (String, String) -> Unit,
    wrongCredentials: String,
    onConnected: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            Text(stringResource(Res.string.iol_connect_title), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(Res.string.iol_connect_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = username,
                onValueChange = { username = it; error = null },
                label = { Text(stringResource(Res.string.iol_username)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = password,
                onValueChange = { password = it; error = null },
                label = { Text(stringResource(Res.string.iol_password)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        try {
                            onConnect(username, password)
                            onConnected()
                        } catch (e: Throwable) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            error = if (e is IolAuthException) wrongCredentials else e.message ?: e.toString()
                        } finally {
                            busy = false
                        }
                    }
                },
                enabled = !busy && username.isNotBlank() && password.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text(stringResource(Res.string.iol_connect_action))
                }
            }
        }
    }
}

/** A way in that doesn't work yet: the app's standard row, with a dim «Pronto». */
@Composable
private fun SoonRow(
    icon: ImageVector,
    title: String,
    hint: String,
    onClick: () -> Unit,
) {
    AppListRow(
        icon = icon,
        // Neutral paint: a broker is not a category, and a color here would
        // claim an identity the account it creates doesn't have yet.
        paint = accountPaint(null),
        title = title,
        subtitle = hint,
        onClick = onClick,
    ) {
        Text(
            stringResource(Res.string.investments_soon),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
    }
}
