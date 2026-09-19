@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package ar.fausto.weil

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FabPosition
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import weil.app.sharedui.generated.resources.Res
import weil.app.sharedui.generated.resources.account_add_title
import weil.app.sharedui.generated.resources.home_accounts_title
import weil.app.sharedui.generated.resources.home_add_first_account
import weil.app.sharedui.generated.resources.home_balance_hide
import weil.app.sharedui.generated.resources.home_balance_show
import weil.app.sharedui.generated.resources.home_balance_title
import weil.app.sharedui.generated.resources.home_greeting
import weil.app.sharedui.generated.resources.home_greeting_named
import weil.app.sharedui.generated.resources.home_no_accounts_yet
import weil.app.sharedui.generated.resources.home_no_recent
import weil.app.sharedui.generated.resources.home_pay_qr
import weil.app.sharedui.generated.resources.home_recent_title
import weil.app.sharedui.generated.resources.home_see_all
import weil.app.sharedui.generated.resources.import_menu
import weil.app.sharedui.generated.resources.import_no_picker
import weil.app.sharedui.generated.resources.import_unsupported
import weil.app.sharedui.generated.resources.inbox_menu
import weil.app.sharedui.generated.resources.more_options
import weil.app.sharedui.generated.resources.new_transaction
import weil.app.sharedui.generated.resources.open_account_tree
import weil.app.sharedui.generated.resources.open_emails
import weil.app.sharedui.generated.resources.open_journal
import weil.app.sharedui.generated.resources.open_notifications

/**
 * Home, dashboard layout: greeting, the total balance as one hero card, the
 * user's asset accounts as tiles, and the latest movements as separate
 * rounded rows with the account's own icon.
 *
 * The previous list-shaped Home is still in [HomeScreen] (HomeScreen.kt),
 * untouched, so the two can be compared side by side.
 */
@Composable
fun HomeDashboardScreen(
    ledgerState: LedgerState,
    userState: UserState,
    documents: () -> DocumentPicker?,
    onImportDocument: (PickedDocument) -> Unit,
    onNavigateToInbox: () -> Unit,
    /** Resolved lazily (needs a foreground activity); null hides QR pay. */
    scanner: () -> QrScanner? = { null },
    onPayWithQr: (QrPayment) -> Unit = {},
    onNavigateToNotifications: () -> Unit,
    onNavigateToEmails: () -> Unit,
    onNavigateToJournal: () -> Unit,
    onNavigateToTree: () -> Unit,
    onNewTransaction: (TxnKind) -> Unit,
    onNavigateToAccount: (id: String) -> Unit,
    onOpenTransaction: (id: String) -> Unit,
    onNavigateToAddAccount: () -> Unit,
    bottomBar: @Composable () -> Unit = {},
) {
    if (!ledgerState.loaded) {
        LaunchedEffect(Unit) { ledgerState.refresh() }
    }
    LaunchedEffect(Unit) { userState.load() }
    val scope = rememberCoroutineScope()
    val noPickerMessage = stringResource(Res.string.import_no_picker)
    val unsupportedMessage = stringResource(Res.string.import_unsupported)
    val qrScanner = scanner()

    // Same two handoffs as the classic Home; see HomeScreen.kt for why the
    // QR one records only after the wallet actually opened.
    fun importDocument() {
        scope.launch {
            val picker = documents()
            if (picker == null) {
                Feedback.show(noPickerMessage)
                return@launch
            }
            val document = try {
                picker.pick()
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Feedback.show(e.message ?: e.toString())
                null
            } ?: return@launch
            if (document.mimeType !in IMPORTABLE_MIME_TYPES) {
                Feedback.show(unsupportedMessage)
                return@launch
            }
            onImportDocument(document)
        }
    }

    fun payWithQr(qr: QrScanner) {
        scope.launch {
            val raw = try {
                qr.scan()
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Feedback.show(e.message ?: e.toString())
                null
            } ?: return@launch
            onPayWithQr(parseEmvcoQr(raw) ?: QrPayment(raw, null, null, null, null))
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AppTopBar(
                // "Hola, Agostina" once the name is known, a plain "Hola"
                // until then: the comma belongs to the name, so it can't be
                // baked into the greeting string.
                title = if (userState.name.isNullOrBlank()) {
                    stringResource(Res.string.home_greeting)
                } else {
                    stringResource(Res.string.home_greeting_named, userState.name!!)
                },
                actions = {
                    HomeOverflow(
                        onNavigateToNotifications = onNavigateToNotifications,
                        onNavigateToEmails = onNavigateToEmails,
                        onImport = { importDocument() },
                        onPayWithQr = qrScanner?.let { { payWithQr(it) } },
                        onNavigateToInbox = onNavigateToInbox,
                    )
                },
            )
        },
        // The create button lives *in* the bar (AppBottomBar), overlapping
        // its top edge — a Scaffold FAB can only float above it.
        bottomBar = bottomBar,
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = ledgerState.pullRefreshing,
            onRefresh = { ledgerState.refresh(userInitiated = true) },
            modifier = Modifier.fillMaxSize().padding(innerPadding),
        ) {
            val assets = remember(ledgerState.tree) {
                ledgerState.tree.filter { it.account.type == AccountType.Asset }
            }
            val nodes = remember(ledgerState.tree) { ledgerState.tree.flatMap { it.selfAndDescendants } }
            val names = remember(nodes) { nodes.associate { it.account.id to it.account.name.censored() } }
            val types = remember(nodes) { nodes.associate { it.account.id to it.account.type } }
            val icons = remember(nodes) { nodes.associate { it.account.id to it.account.icon } }
            val colors = remember(nodes) { nodes.associate { it.account.id to it.account.color } }
            // Computed in composition, not inside the LazyListScope builder
            // (which isn't composable and would redo the work on every pass).
            // Four at most: Home is a glance, and a fifth tile pushes the
            // movements off the first screen. "Ver todo" is right there.
            val accountPairs = remember(assets) { assets.take(4).chunked(2) }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                // The centered FAB straddles the bar's top edge, so the list
                // needs room for it *above* the bar inset the Scaffold
                // already applied — without it the last movement sits under
                // the button.
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 56.dp),
            ) {
                item(key = "balance") { BalanceHero(ledgerState) }

                item(key = "accounts-header") {
                    DashboardHeader(
                        title = stringResource(Res.string.home_accounts_title),
                        actionLabel = stringResource(Res.string.home_see_all),
                        onAction = onNavigateToTree,
                    )
                }
                if (assets.isEmpty() && ledgerState.loaded) {
                    item(key = "accounts-empty") {
                        EmptyHint(
                            title = stringResource(Res.string.home_no_accounts_yet),
                            action = stringResource(Res.string.home_add_first_account),
                            onAction = onNavigateToAddAccount,
                        )
                    }
                } else {
                    // Two per row, roots only: the tiles are a glance at "what
                    // do I have", and the full hierarchy is one tap away in
                    // the tree.
                    items(accountPairs, key = { it.first().account.id }) { pair ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                        ) {
                            pair.forEachIndexed { column, node ->
                                AccountTile(
                                    node = node,
                                    totals = ledgerState.totals[node.account.id].orEmpty(),
                                    hidden = ledgerState.amountsHidden,
                                    // Checkerboard of the two slates: a grid
                                    // of four identical dark rectangles reads
                                    // as one block, and alternating tone is
                                    // what separates them without a gap wide
                                    // enough to break the grid.
                                    dark = (accountPairs.indexOf(pair) + column) % 2 == 0,
                                    onClick = { onNavigateToAccount(node.account.id) },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            // Keeps a lone tile at half width instead of
                            // stretching it across the row, so the grid stays
                            // a grid.
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                    // No "new account" row here: the section's "Ver todo"
                    // leads to the tree, which is where accounts are managed.
                    // Home is a glance, not a form.
                }

                item(key = "recent-header") {
                    DashboardHeader(
                        title = stringResource(Res.string.home_recent_title),
                        actionLabel = stringResource(Res.string.home_see_all),
                        onAction = onNavigateToJournal,
                    )
                }
                val recentItems = ledgerState.recent
                if (recentItems.isEmpty() && ledgerState.loaded) {
                    item(key = "recent-empty") {
                        EmptyHint(title = stringResource(Res.string.home_no_recent))
                    }
                }
                items(recentItems, key = { "recent-${it.id}" }) { tx ->
                    MovementRow(
                        tx = tx,
                        names = names,
                        types = types,
                        icons = icons,
                        colors = colors,
                        hidden = ledgerState.amountsHidden,
                        onOpen = { onOpenTransaction(tx.id) },
                    )
                }
            }
        }
    }
}

/** The overflow that holds every secondary destination. */
@Composable
private fun HomeOverflow(
    onNavigateToNotifications: () -> Unit,
    onNavigateToEmails: () -> Unit,
    onImport: () -> Unit,
    onPayWithQr: (() -> Unit)?,
    onNavigateToInbox: () -> Unit,
) {
    Box {
        run {
            var menuOpen by remember { mutableStateOf(false) }
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(Res.string.more_options))
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                // Tree and Journal dropped from here: both are one tap away
                // already ("Ver todo" on Cuentas / Movimientos, and the
                // Movimientos tab), so they don't need a second door.
                MenuRow(Icons.Filled.Notifications, stringResource(Res.string.open_notifications)) {
                    menuOpen = false
                    onNavigateToNotifications()
                }
                MenuRow(Icons.Filled.Email, stringResource(Res.string.open_emails)) {
                    menuOpen = false
                    onNavigateToEmails()
                }
                MenuRow(Icons.Filled.DocumentScanner, stringResource(Res.string.import_menu)) {
                    menuOpen = false
                    onImport()
                }
                if (onPayWithQr != null) {
                    MenuRow(Icons.Filled.QrScan, stringResource(Res.string.home_pay_qr)) {
                        menuOpen = false
                        onPayWithQr()
                    }
                }
                MenuRow(Icons.Filled.Bolt, stringResource(Res.string.inbox_menu)) {
                    menuOpen = false
                    onNavigateToInbox()
                }
            }
        }
    }
}

/** Top of the list: the hero card sits right under the header. */
private val HeroTopGap = 4.dp

@Composable
private fun MenuRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = { Icon(icon, contentDescription = null) },
        onClick = onClick,
    )
}

/**
 * Total net worth on one tonal card: the dominant commodity big on the left,
 * every other one stacked to the right of a hairline. The eye masks every
 * amount on the screen at once (tiles and movements included) — hiding only
 * the total would be theatre while the accounts below spell it out.
 */
@Composable
private fun BalanceHero(state: LedgerState) {
    val lines = remember(state.tree, state.leafTotals) {
        LedgerState.netWorth(state.tree, state.leafTotals).entries.sortedByDescending { abs(it.value) }
    }
    val primary = lines.firstOrNull()
    val rest = lines.drop(1)
    val hidden = state.amountsHidden
    var pickerOpen by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            // Long-press still opens the net-worth picker, same as the
            // classic Home: which accounts count is a setting, not a button.
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
                onLongClick = { pickerOpen = true },
            )
            .padding(horizontal = 20.dp, vertical = 18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(Res.string.home_balance_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.weight(1f))
            if (state.busy && (!state.loaded || state.pullRefreshing)) {
                CircularProgressIndicator(
                    modifier = Modifier.padding(end = 8.dp).size(14.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            // Top-right corner of the card, level with the title — not
            // trailing it mid-row, where it read as part of the label
            // instead of as the card's own action button.
            IconButton(onClick = { state.toggleAmountsHidden() }, modifier = Modifier.size(28.dp)) {
                Icon(
                    if (hidden) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = stringResource(
                        if (hidden) Res.string.home_balance_show else Res.string.home_balance_hide,
                    ),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    maskedAmount(primary?.value ?: 0L, primary?.key ?: Money.DEFAULT_COMMODITY, hidden),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    // No red for a negative total here: this is a net worth,
                    // not a debit — the sign is already legible in the minus.
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    primary?.key ?: Money.DEFAULT_COMMODITY,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                )
            }
            if (rest.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .padding(horizontal = 14.dp)
                        .size(width = 1.dp, height = 40.dp)
                        .background(MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.3f)),
                )
                Column(horizontalAlignment = Alignment.Start) {
                    rest.forEach { (commodity, minor) ->
                        Text(
                            commodity,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                        )
                        Text(
                            maskedAmount(minor, commodity, hidden),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
        state.error?.let { ErrorBanner(it, modifier = Modifier.padding(top = 12.dp)) }
    }
    if (pickerOpen) {
        NetWorthPickerSheet(state = state, onDismiss = { pickerOpen = false })
    }
}

/** Section title with a plain "Ver todo ›" on the right. */
@Composable
private fun DashboardHeader(title: String, actionLabel: String, onAction: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(top = 22.dp, bottom = 10.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            // Same ink as the darker account tile, not the page's default
            // text color: "Cuentas"/"Movimientos" read as part of that same
            // slate family rather than as generic body text.
            color = MaterialTheme.colorScheme.inverseSurface,
            modifier = Modifier.weight(1f),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onAction)
                .padding(horizontal = 4.dp, vertical = 2.dp),
        ) {
            Text(
                actionLabel,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.inverseSurface,
            )
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.inverseSurface,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/**
 * One asset account as a filled tile: name and balance, nothing else. The
 * dark container is [inverseSurface] so a theme swap carries it — a hardcoded
 * slate would be the one thing on screen ignoring the palette.
 */
@Composable
private fun AccountTile(
    node: AccountNode,
    totals: Map<String, Long>,
    hidden: Boolean,
    dark: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val entry = totals.entries.maxByOrNull { abs(it.value) }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(
                if (dark) {
                    MaterialTheme.colorScheme.inverseSurface
                } else {
                    MaterialTheme.colorScheme.secondary
                },
            )
            .clickable(onClick = onClick)
            .heightIn(min = 84.dp)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // No icon here: an account tile is identified by its name ("Galicia
        // USD" vs "Galicia ARS"), which a generic wallet glyph can't
        // distinguish — it only stole width from the name that can.
        Text(
            node.account.name.censored(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.inverseOnSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            maskedAmount(entry?.value ?: 0L, entry?.key ?: Money.DEFAULT_COMMODITY, hidden),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            // No red for a negative balance here either — an account tile is
            // a total, not a debit; the minus already says overdrawn.
            color = MaterialTheme.colorScheme.inverseOnSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Amount with its currency symbol, or a fixed mask while the eye is closed.
 * The symbol is always shown, including for ARS: a bare number on a tile is
 * the one place a reader has nothing else to tell them it is money.
 */
internal fun maskedAmount(
    minor: Long,
    commodity: String,
    hidden: Boolean,
    // Only the compact movement row hides its sign (color carries it there,
    // same as every other transaction row in the app); the hero and the
    // tiles are numbers on their own, so they keep the minus.
    signed: Boolean = true,
): String =
    if (hidden) "${currencySymbol(commodity)} ••••••" else formatMoney(minor, commodity, signed = signed)
