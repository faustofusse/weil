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
import androidx.compose.foundation.verticalScroll
import weil.app.sharedui.generated.resources.broker_ibkr_api_status
import weil.app.sharedui.generated.resources.broker_rebuild
import weil.app.sharedui.generated.resources.broker_rebuild_action
import weil.app.sharedui.generated.resources.broker_rebuild_body
import weil.app.sharedui.generated.resources.broker_rebuild_title
import weil.app.sharedui.generated.resources.investments_mep_rate
import weil.app.sharedui.generated.resources.broker_ibkr_change_token
import weil.app.sharedui.generated.resources.broker_ibkr_connect_api
import weil.app.sharedui.generated.resources.broker_connect_here
import weil.app.sharedui.generated.resources.ibkr_api_body
import weil.app.sharedui.generated.resources.ibkr_api_title
import weil.app.sharedui.generated.resources.ibkr_connect_action
import weil.app.sharedui.generated.resources.ibkr_disconnect_body
import weil.app.sharedui.generated.resources.ibkr_disconnect_title
import weil.app.sharedui.generated.resources.ibkr_query_id
import weil.app.sharedui.generated.resources.ibkr_token
import weil.app.sharedui.generated.resources.ibkr_up_to_date
import weil.app.sharedui.generated.resources.ibkr_wrong_token
import weil.app.sharedui.generated.resources.investments_alert_token
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.collectAsState
import weil.app.sharedui.generated.resources.broker_change_credentials
import weil.app.sharedui.generated.resources.broker_ibkr_status
import weil.app.sharedui.generated.resources.broker_import_report
import weil.app.sharedui.generated.resources.ibkr_help_body
import weil.app.sharedui.generated.resources.ibkr_help_title
import weil.app.sharedui.generated.resources.ibkr_pick_file
import weil.app.sharedui.generated.resources.broker_connected_as
import weil.app.sharedui.generated.resources.broker_see_holdings
import weil.app.sharedui.generated.resources.broker_see_movements
import weil.app.sharedui.generated.resources.broker_sync_now
import weil.app.sharedui.generated.resources.home_see_all
import weil.app.sharedui.generated.resources.investments_alert_differences
import weil.app.sharedui.generated.resources.investments_alert_password
import weil.app.sharedui.generated.resources.investments_alert_review
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
    ibkr: IbkrRepository,
    brokers: BrokersRepository,
    onReviewImport: (BrokerImportRoute) -> Unit,
    onOpenAccount: (AccountDetailRoute) -> Unit,
    onOpenTransaction: (id: String) -> Unit,
    onOpenInstrument: (commodity: String) -> Unit,
    bottomBar: @Composable () -> Unit = {},
    /** Opens the platform picker for an IBKR report; AppRoot routes what comes back. */
    pickReport: suspend () -> Unit = {},
    /** Harness-only: opens the first broker's sheet once connections load. */
    openFirstBroker: Boolean = false,
    /** Harness-only: opens IBKR's how-to/connect sheet. */
    openIbkrHelp: Boolean = false,
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
    /** True from a pull until the syncs it started are done; see the PullToRefreshBox. */
    var pulling by remember { mutableStateOf(false) }
    var confirmRebuild by remember { mutableStateOf(false) }
    // IBKR's token, like IOL's password: this device's only, re-read after changes here.
    var ibkrQuery by remember { mutableStateOf(ibkr.queryId.takeIf { ibkr.hasToken }) }
    var ibkrSyncing by remember { mutableStateOf(false) }
    var confirmIbkrDisconnect by remember { mutableStateOf(false) }
    val wrongToken = stringResource(Res.string.ibkr_wrong_token)
    val ibkrUpToDate = stringResource(Res.string.ibkr_up_to_date)
    // The broker whose sheet is open (sync, see, change credentials, disconnect).
    var managing by remember { mutableStateOf<BrokerConnection?>(null) }
    var ibkrHelp by remember { mutableStateOf(openIbkrHelp) }
    fun importReport() {
        scope.launch {
            try {
                pickReport()
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Feedback.show(e.message ?: e.toString())
            }
        }
    }
    val autoSync by iol.lastAutoSync.collectAsState()
    val ibkrAutoSync by ibkr.lastAutoSync.collectAsState()

    var connections by remember { mutableStateOf<List<BrokerConnection>?>(null) }
    var holdings by remember { mutableStateOf<Map<String, Map<String, HeldPosition>>>(emptyMap()) }
    var movements by remember { mutableStateOf<List<HoldingMovement>>(emptyList()) }
    var recent by remember { mutableStateOf<List<Transaction>>(emptyList()) }

    // A cold start straight into this tab has no tree nor valuation yet.
    LaunchedEffect(Unit) { if (!ledgerState.loaded) ledgerState.refresh() }

    suspend fun load() {
        val found = runCatching { brokers.connections() }.getOrNull() ?: return
        holdings = found.associate { it.provider to ledger.holdings(it.accounts.holdings) }
        movements = ledger.holdingMovements(found.map { it.accounts.holdings })
        // Every account a broker books into on the asset side: its cash
        // accounts and its holdings (the income/expense/equity branches are
        // shared by all brokers and would drag in unrelated rows).
        val ids = found.flatMap { it.accounts.cash.values + it.accounts.holdings }.distinct()
        val txIds = ledger.register(subtreeIds = ids, limit = RECENT_SCAN)
            .map { it.posting.transactionId }.distinct().take(RECENT_SHOWN)
        val byId = ledger.getAll(txIds)
        recent = txIds.mapNotNull { byId[it] }
        if (openFirstBroker && connections == null) managing = found.firstOrNull()
        connections = found
    }

    LaunchedEffect(ledgerState.tree) { load() }
    // Opening the tab is an occasion to sync, like launching the app;
    // autoSync throttles itself, so switching tabs doesn't hammer IOL.
    LaunchedEffect(Unit) {
        iol.autoSync()
        ibkr.autoSync()
    }

    /** Opens the review for an auto sync's [plan]; the alert is settled by looking at it. */
    fun review(provider: String, plan: BrokerPlan) {
        scope.launch {
            val accounts = brokers.accountsFor(provider) ?: return@launch
            if (provider == IBKR_PROVIDER) ibkr.clearAutoSync() else iol.clearAutoSync()
            onReviewImport(BrokerImportRoute(provider.uppercase(), provider, plan, accounts, brokers.scales()))
        }
    }

    /** Plans a fetched IBKR report: the review, or a «todo al día». */
    fun showIbkr(preview: IbkrPreview) {
        val plan = preview.plan
        if (plan.transactions.isEmpty() && plan.differences.isEmpty() && plan.issues.isEmpty()) {
            Feedback.show(ibkrUpToDate)
        } else {
            scope.launch {
                onReviewImport(BrokerImportRoute("IBKR", IBKR_PROVIDER, plan, preview.accounts, brokers.scales()))
            }
        }
    }

    fun syncIbkr() {
        if (ibkrSyncing) return
        ibkrSyncing = true
        scope.launch {
            try {
                showIbkr(ibkr.sync())
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Feedback.show(if (e is IbkrAuthException) wrongToken else e.message ?: e.toString())
            } finally {
                ibkrSyncing = false
            }
        }
    }
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

    /** The whole IOL history planned again, to replace what earlier imports wrote; always reviewed. */
    fun rebuild() {
        if (syncing) return
        syncing = true
        scope.launch {
            try {
                val plan = iol.rebuildPreview()
                val accounts = brokers.accountsFor(IOL_PROVIDER) ?: error("IOL accounts missing after preview")
                onReviewImport(BrokerImportRoute("IOL", IOL_PROVIDER, plan, accounts, brokers.scales()))
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
    val display = ledgerState.investmentsDisplay
    val positions = remember(holdings, movements, valuation, display) {
        valuation.positions(consolidateHoldings(holdings.values.toList()), display, movements)
    }
    val nodes = remember(ledgerState.tree) { ledgerState.tree.flatMap { it.selfAndDescendants } }
    val names = remember(nodes) { nodes.associate { it.account.id to it.account.name.censored() } }
    val types = remember(nodes) { nodes.associate { it.account.id to it.account.type } }
    val parents = remember(nodes) { nodes.associate { it.account.id to it.account.parentId } }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AppTopBar(title = stringResource(Res.string.investments_title)) {
                if (iolUser != null || ibkrQuery != null) {
                    if (syncing || ibkrSyncing) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(horizontal = 14.dp).size(20.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        IconButton(onClick = {
                            if (iolUser != null) sync()
                            if (ibkrQuery != null) syncIbkr()
                        }) {
                            Icon(Icons.Filled.Refresh, contentDescription = stringResource(Res.string.iol_sync))
                        }
                    }
                }
            }
        },
        bottomBar = bottomBar,
    ) { innerPadding ->
        // The pull indicator answers the gesture only. A sync started any
        // other way (the top-bar icon, opening the tab, a broker's sheet)
        // already shows in the top bar, and a second spinner over the list
        // would announce work nobody pulled for — same rule as the journal.
        LaunchedEffect(pulling, syncing, ibkrSyncing, ledgerState.pullRefreshing) {
            if (!syncing && !ibkrSyncing && !ledgerState.pullRefreshing) pulling = false
        }
        PullToRefreshBox(
            isRefreshing = pulling,
            onRefresh = {
                pulling = true
                ledgerState.refresh(userInitiated = true)
                if (iolUser != null) sync()
                if (ibkrQuery != null) syncIbkr()
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
                        // By commodity, so a display in one currency can value a
                        // CEDEAR at its line in that currency, as the rows do.
                        val holdingsByCommodity = remember(connected, ledgerState.leafTotals) {
                            val sum = mutableMapOf<String, Long>()
                            for (c in connected) {
                                for (id in c.accounts.cash.values + c.accounts.holdings) {
                                    ledgerState.leafTotals[id]?.forEach { (k, v) -> sum[k] = (sum[k] ?: 0L) + v }
                                }
                            }
                            sum.filterValues { it != 0L }
                        }
                        val money = remember(holdingsByCommodity, valuation) { valuation.value(holdingsByCommodity) }
                        // All in pesos or all in MEP converts at the MEP rate,
                        // which is then the one line worth saying; the
                        // official one only restates the currencies as they are.
                        val shown = remember(holdingsByCommodity, valuation, display) {
                            valuation.valueInDisplay(holdingsByCommodity, display)
                        }
                        InvestmentsHero(
                            money = shown,
                            gains = remember(positions) { unrealizedTotals(positions) },
                            converted = remember(money, valuation, ledgerState.netWorthCurrency, display) {
                                if (display == InvestmentsDisplay.Original) {
                                    valuation.convert(money, ledgerState.netWorthCurrency, epochMillis())
                                } else {
                                    null
                                }
                            },
                            mep = valuation.mep?.takeIf { display != InvestmentsDisplay.Original && shown != money },
                            hidden = ledgerState.amountsHidden,
                        )
                    }
                    autoSync?.let { result ->
                        item(key = "alert-iol") {
                            AutoSyncAlert(
                                broker = "IOL",
                                result = result,
                                onReview = { review(IOL_PROVIDER, it) },
                                onReconnect = { connecting = true },
                            )
                        }
                    }
                    ibkrAutoSync?.let { result ->
                        item(key = "alert-ibkr") {
                            AutoSyncAlert(
                                broker = "IBKR",
                                result = result,
                                onReview = { review(IBKR_PROVIDER, it) },
                                onReconnect = { ibkrHelp = true },
                            )
                        }
                    }
                    item(key = "brokers-header") { SectionHeader(title = stringResource(Res.string.investments_brokers)) }
                    items(connected, key = { "broker-${it.provider}" }) { c ->
                        val holdingsId = c.accounts.holdings
                        val rootId = parents[holdingsId]
                        val title = (rootId?.let { names[it] } ?: names[holdingsId]).orEmpty()
                        val value = (c.accounts.cash.values + holdingsId)
                            .flatMap { ledgerState.leafTotals[it].orEmpty().entries }
                            .groupBy({ it.key }, { it.value })
                            .mapValues { it.value.sum() }
                            .let { valuation.valueInDisplay(it, display) }
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
                            onClick = { managing = c },
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
                                // The instrument across every broker holding it.
                                onSelect = { commodity -> commodity?.let { onOpenInstrument(it) } },
                            )
                        }
                    }
                    if (recent.isNotEmpty()) {
                        item(key = "recent-header") {
                            // The full list is a broker's register with its
                            // subaccounts; with several brokers there is no
                            // one account that holds them all, and each
                            // broker's sheet opens its own.
                            val only = connected.singleOrNull()
                            val root = only?.let { parents[it.accounts.holdings] }
                            SectionHeader(title = stringResource(Res.string.investments_recent)) {
                                if (root != null) {
                                    SeeAllLink(stringResource(Res.string.home_see_all)) {
                                        onOpenAccount(AccountDetailRoute(root, subtree = true))
                                    }
                                }
                            }
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
                if (connections != null && connected.none { it.provider == IBKR_PROVIDER }) {
                    item(key = "source-ibkr") {
                        AppListRow(
                            icon = Icons.Filled.TrendingUp,
                            paint = accountPaint(null),
                            title = stringResource(Res.string.investments_source_ibkr),
                            subtitle = stringResource(Res.string.investments_source_ibkr_hint),
                            onClick = { ibkrHelp = true },
                        )
                    }
                }
                if (connections != null) {
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
            initialUsername = iol.username.orEmpty(),
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

    managing?.let { c ->
        val isIol = c.provider == IOL_PROVIDER
        val rootId = parents[c.accounts.holdings]
        BrokerSheet(
            title = (rootId?.let { names[it] } ?: names[c.accounts.holdings]).orEmpty(),
            status = when {
                !isIol && ibkrQuery != null -> stringResource(Res.string.broker_ibkr_api_status, ibkrQuery!!)
                !isIol -> stringResource(Res.string.broker_ibkr_status)
                iolUser != null -> stringResource(Res.string.broker_connected_as, iolUser!!)
                else -> stringResource(Res.string.investments_connect_here)
            },
            connectLabel = when {
                isIol && iolUser != null -> stringResource(Res.string.broker_change_credentials)
                isIol -> stringResource(Res.string.broker_connect_here)
                ibkrQuery != null -> stringResource(Res.string.broker_ibkr_change_token)
                else -> stringResource(Res.string.broker_ibkr_connect_api)
            },
            disconnectLabel = stringResource(if (isIol) Res.string.iol_disconnect_title else Res.string.ibkr_disconnect_title),
            freshness = freshness(c.syncedAt),
            onSync = when {
                isIol && iolUser != null -> ({ managing = null; sync() })
                !isIol && ibkrQuery != null -> ({ managing = null; syncIbkr() })
                else -> null
            },
            onImport = if (c.provider == IBKR_PROVIDER) ({ managing = null; importReport() }) else null,
            onRebuild = if (isIol && iolUser != null) ({ managing = null; confirmRebuild = true }) else null,
            onHoldings = { managing = null; onOpenAccount(AccountDetailRoute(c.accounts.holdings)) },
            onMovements = rootId?.let { root -> { managing = null; onOpenAccount(AccountDetailRoute(root, subtree = true)) } },
            onConnect = { managing = null; if (isIol) connecting = true else ibkrHelp = true },
            onDisconnect = when {
                isIol && iolUser != null -> ({ managing = null; confirmDisconnect = true })
                !isIol && ibkrQuery != null -> ({ managing = null; confirmIbkrDisconnect = true })
                else -> null
            },
            onDismiss = { managing = null },
        )
    }

    if (ibkrHelp) {
        IbkrHelpSheet(
            initialQuery = ibkr.queryId.orEmpty(),
            wrongToken = wrongToken,
            onPick = { ibkrHelp = false; importReport() },
            onConnect = { token, query -> ibkr.connect(token, query) },
            onConnected = { preview ->
                ibkrHelp = false
                ibkrQuery = ibkr.queryId
                showIbkr(preview)
            },
            onDismiss = { ibkrHelp = false },
        )
    }

    if (confirmIbkrDisconnect) {
        AlertDialog(
            onDismissRequest = { confirmIbkrDisconnect = false },
            title = { Text(stringResource(Res.string.ibkr_disconnect_title)) },
            text = { Text(stringResource(Res.string.ibkr_disconnect_body)) },
            confirmButton = {
                TextButton(onClick = {
                    ibkr.disconnect()
                    ibkrQuery = null
                    confirmIbkrDisconnect = false
                }) { Text(stringResource(Res.string.iol_disconnect_action)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmIbkrDisconnect = false }) { Text(stringResource(Res.string.action_cancel)) }
            },
        )
    }

    if (confirmRebuild) {
        AlertDialog(
            onDismissRequest = { confirmRebuild = false },
            title = { Text(stringResource(Res.string.broker_rebuild_title, "InvertirOnline")) },
            text = { Text(stringResource(Res.string.broker_rebuild_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmRebuild = false
                    rebuild()
                }) { Text(stringResource(Res.string.broker_rebuild_action)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRebuild = false }) { Text(stringResource(Res.string.action_cancel)) }
            },
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
    /** The MEP rate the values were converted at, when they were. */
    mep: PriceQuote?,
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
        mep?.let { rate ->
            Text(
                stringResource(
                    Res.string.investments_mep_rate,
                    formatPrice(rate.price, rate.quoteCommodity, exact = true),
                    shortDate(rate.at),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = ink.copy(alpha = 0.75f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp),
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
 * What an unattended sync left for a person, as one tappable card: new
 * movements that need review, the ledger disagreeing with the broker (never
 * settled on its own, decision 5), or a password the broker stopped taking.
 * A clean sync shows nothing — its snackbar already said so.
 */
@Composable
private fun AutoSyncAlert(
    broker: String,
    result: BrokerAutoSync,
    onReview: (BrokerPlan) -> Unit,
    onReconnect: () -> Unit,
) {
    val (text, onClick, error) = when (result) {
        is BrokerAutoSync.NeedsReview -> Triple(
            stringResource(Res.string.investments_alert_review, broker, result.plan.transactions.size),
            { onReview(result.plan) },
            false,
        )
        is BrokerAutoSync.Applied -> {
            if (result.differences.isEmpty()) return
            Triple(
                stringResource(Res.string.investments_alert_differences, broker, result.differences.size),
                // Already written: the review only has the differences left.
                { onReview(result.plan.copy(transactions = emptyList())) },
                false,
            )
        }
        BrokerAutoSync.WrongCredentials -> Triple(
            stringResource(if (broker == "IBKR") Res.string.investments_alert_token else Res.string.investments_alert_password),
            onReconnect,
            true,
        )
        is BrokerAutoSync.Failed -> return
    }
    val container = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.tertiaryContainer
    val ink = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onTertiaryContainer
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(container)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Icon(Icons.Filled.Warning, contentDescription = null, tint = ink, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = ink, modifier = Modifier.weight(1f))
        Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = ink, modifier = Modifier.size(20.dp))
    }
}

/**
 * One broker's sheet: which account this device is connected as, when it
 * last synced, and what can be done with it. Replaces the connect prompt a
 * tap used to open, so a connected broker is managed rather than
 * re-entered; a broker connected only on another device offers connecting
 * here (credentials never sync).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BrokerSheet(
    title: String,
    /** «Conectado como fausto», «Sin conectar en este dispositivo», how IBKR updates. */
    status: String,
    /** «Cambiar usuario o contraseña», «Conectar por API», «Cambiar token»… */
    connectLabel: String,
    disconnectLabel: String,
    freshness: String,
    onSync: (() -> Unit)?,
    onImport: (() -> Unit)?,
    /** Plans the whole history again, to replace what was imported (IOL). */
    onRebuild: (() -> Unit)? = null,
    onHoldings: () -> Unit,
    onMovements: (() -> Unit)?,
    onConnect: () -> Unit,
    onDisconnect: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Text(
                    status,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    freshness,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider(modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
            onSync?.let { SheetAction(Icons.Filled.Refresh, stringResource(Res.string.broker_sync_now), onClick = it) }
            onImport?.let { SheetAction(Icons.Filled.DocumentScanner, stringResource(Res.string.broker_import_report), onClick = it) }
            SheetAction(Icons.Filled.TrendingUp, stringResource(Res.string.broker_see_holdings), onClick = onHoldings)
            onMovements?.let { SheetAction(Icons.Filled.ListAlt, stringResource(Res.string.broker_see_movements), onClick = it) }
            SheetAction(Icons.Filled.Person, connectLabel, onClick = onConnect)
            onRebuild?.let { SheetAction(Icons.Filled.Refresh, stringResource(Res.string.broker_rebuild), onClick = it) }
            onDisconnect?.let {
                SheetAction(
                    Icons.Filled.Logout,
                    disconnectLabel,
                    color = MaterialTheme.colorScheme.error,
                    onClick = it,
                )
            }
        }
    }
}

@Composable
private fun SheetAction(
    icon: ImageVector,
    label: String,
    color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(16.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = color)
    }
}

/**
 * How to get IBKR's data in: the Flex query set up once, then its file.
 * The steps are the whole feature from the user's side (IBKR offers
 * individual accounts no API a phone can use), so they are spelled out.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IbkrHelpSheet(
    initialQuery: String,
    wrongToken: String,
    onPick: () -> Unit,
    onConnect: suspend (token: String, query: String) -> IbkrPreview,
    onConnected: (IbkrPreview) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var token by remember { mutableStateOf("") }
    var query by remember { mutableStateOf(initialQuery) }
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
                .verticalScroll(androidx.compose.foundation.rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            Text(stringResource(Res.string.ibkr_help_title), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(10.dp))
            Text(
                stringResource(Res.string.ibkr_help_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            Button(onClick = onPick, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(Res.string.ibkr_pick_file))
            }
            Spacer(Modifier.height(24.dp))
            Text(stringResource(Res.string.ibkr_api_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(Res.string.ibkr_api_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = token,
                onValueChange = { token = it; error = null },
                label = { Text(stringResource(Res.string.ibkr_token)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it.filter { c -> c.isDigit() }; error = null },
                label = { Text(stringResource(Res.string.ibkr_query_id)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(12.dp))
            // Tonal: the file is the way that always works, the token the upgrade.
            androidx.compose.material3.FilledTonalButton(
                onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        try {
                            onConnected(onConnect(token, query))
                        } catch (e: Throwable) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            error = if (e is IbkrAuthException) wrongToken else e.message ?: e.toString()
                        } finally {
                            busy = false
                        }
                    }
                },
                enabled = !busy && token.isNotBlank() && query.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(Res.string.ibkr_connect_action))
                }
            }
        }
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
    initialUsername: String,
    onConnect: suspend (String, String) -> Unit,
    wrongCredentials: String,
    onConnected: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var username by remember { mutableStateOf(initialUsername) }
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
