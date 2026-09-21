@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package ar.fausto.weil

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import weil.app.sharedui.generated.resources.Res
import weil.app.sharedui.generated.resources.account_add_title
import weil.app.sharedui.generated.resources.app_title
import weil.app.sharedui.generated.resources.home_accounts_title
import weil.app.sharedui.generated.resources.home_add_first_account
import weil.app.sharedui.generated.resources.home_no_accounts_yet
import weil.app.sharedui.generated.resources.home_no_recent
import weil.app.sharedui.generated.resources.home_pay_qr
import weil.app.sharedui.generated.resources.home_recent_title
import weil.app.sharedui.generated.resources.home_see_all
import weil.app.sharedui.generated.resources.import_menu
import weil.app.sharedui.generated.resources.import_no_picker
import weil.app.sharedui.generated.resources.import_unsupported
import weil.app.sharedui.generated.resources.more_options
import weil.app.sharedui.generated.resources.new_transaction
import weil.app.sharedui.generated.resources.open_account_tree
import weil.app.sharedui.generated.resources.open_emails
import weil.app.sharedui.generated.resources.open_journal
import weil.app.sharedui.generated.resources.open_notifications
import weil.app.sharedui.generated.resources.open_profile

/**
 * Home: net worth, the user's asset accounts ("Cuentas") and the latest
 * movements. The full tree lives in [AccountsTreeScreen] and the secondary
 * destinations sit in the overflow menu. The FAB opens the quick entry on
 * Gasto (the most frequent kind); the kind can be switched there.
 */
@Composable
fun HomeScreen(
    ledgerState: LedgerState,
    documents: () -> DocumentPicker?,
    onImportDocument: (PickedDocument) -> Unit,
    /** Resolved lazily (needs a foreground activity); null hides QR pay. */
    scanner: () -> QrScanner? = { null },
    onPayWithQr: (QrPayment) -> Unit = {},
    onNavigateToProfile: () -> Unit,
    onNavigateToNotifications: () -> Unit,
    onNavigateToEmails: () -> Unit,
    onNavigateToJournal: () -> Unit,
    onNavigateToTree: () -> Unit,
    onNewTransaction: (TxnKind) -> Unit,
    onNavigateToAccount: (id: String) -> Unit,
    onOpenTransaction: (id: String) -> Unit,
    onNavigateToAddAccount: () -> Unit,
) {
    if (!ledgerState.loaded) {
        LaunchedEffect(Unit) { ledgerState.refresh() }
    }
    val scope = rememberCoroutineScope()
    val noPickerMessage = stringResource(Res.string.import_no_picker)
    val qrScanner = scanner()
    val unsupportedMessage = stringResource(Res.string.import_unsupported)

    // Manual counterpart of the Android share target: pick a receipt/statement
    // from storage and hand it to the same review screen.
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

    // Scan a merchant QR, record the expense, hand the payload to the wallet.
    // A payload we can't parse still goes through with empty prefills: the
    // handoff doesn't depend on *us* understanding the QR.
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
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.app_title)) },
                actions = {
                    IconButton(onClick = onNavigateToJournal) {
                        Icon(Icons.Filled.MenuBook, contentDescription = stringResource(Res.string.open_journal))
                    }
                    IconButton(onClick = onNavigateToProfile) {
                        Icon(Icons.Filled.AccountCircle, contentDescription = stringResource(Res.string.open_profile))
                    }
                    Box {
                        var menuOpen by remember { mutableStateOf(false) }
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(Res.string.more_options))
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            OverflowItem(Icons.Filled.AccountTree, stringResource(Res.string.open_account_tree)) {
                                menuOpen = false
                                onNavigateToTree()
                            }
                            OverflowItem(Icons.Filled.Notifications, stringResource(Res.string.open_notifications)) {
                                menuOpen = false
                                onNavigateToNotifications()
                            }
                            OverflowItem(Icons.Filled.Email, stringResource(Res.string.open_emails)) {
                                menuOpen = false
                                onNavigateToEmails()
                            }
                            OverflowItem(Icons.Filled.DocumentScanner, stringResource(Res.string.import_menu)) {
                                menuOpen = false
                                importDocument()
                            }
                            if (qrScanner != null) {
                                OverflowItem(Icons.Filled.QrScan, stringResource(Res.string.home_pay_qr)) {
                                    menuOpen = false
                                    payWithQr(qrScanner)
                                }
                            }
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            // Plain + , not "Gasto": the quick sheet it opens can record any
            // of the three kinds, so labelling the button with the default
            // one was a promise the screen doesn't keep.
            FloatingActionButton(
                onClick = { onNewTransaction(TxnKind.Expense) },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(Res.string.new_transaction))
            }
        },
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = ledgerState.pullRefreshing,
            onRefresh = { ledgerState.refresh(userInitiated = true) },
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            val assets = ledgerState.tree.filter { it.account.type == AccountType.Asset }
            val nodes = remember(ledgerState.tree) { ledgerState.tree.flatMap { it.selfAndDescendants } }
            // Leaf names, not full paths: a one-line movement row has room for
            // "Efectivo → Comida", not for "Activos:Efectivo → Gastos:Comida".
            val names = remember(nodes) { nodes.associate { it.account.id to it.account.name.censored() } }
            val types = remember(nodes) { nodes.associate { it.account.id to it.account.type } }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                // Bottom room so the FAB never covers the last row.
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
            ) {
                item(key = "net-worth") { NetWorthCard(ledgerState) }

                item(key = "accounts-header") {
                    SectionHeader(
                        title = stringResource(Res.string.home_accounts_title),
                        trailing = {
                            SectionHeaderButton(
                                icon = Icons.Filled.Add,
                                contentDescription = stringResource(Res.string.account_add_title),
                                onClick = onNavigateToAddAccount,
                            )
                        },
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
                    val rows = buildList { addSection(assets, ledgerState.expandedIds, 0) }
                        .filterIsInstance<NodeRow>()
                    // One decision for the whole card: no sub-accounts anywhere
                    // means no fold column, so names start at the card edge.
                    val foldable = rows.any { it.node.children.isNotEmpty() }
                    itemsIndexed(rows, key = { _, row -> row.key }) { index, row ->
                        NodeRowView(
                            state = ledgerState,
                            row = row,
                            onToggle = { ledgerState.toggleExpanded(it) },
                            onOpen = onNavigateToAccount,
                            skin = rowSkin(first = index == 0, last = index == rows.lastIndex),
                            toggleSlot = foldable,
                            // Long-press still opens the same sheet; see the
                            // parameter's doc for why Home drops the ⋮.
                            actions = false,
                        )
                    }
                }

                item(key = "recent-header") {
                    // No divider: the day labels now live inside the card, so
                    // the section already starts on a clean rounded edge. A
                    // rule here stacked a third separation mark on top of the
                    // header and the first day label.
                    SectionHeader(
                        title = stringResource(Res.string.home_recent_title),
                        trailing = {
                            // A text button, not the accounts header's filled
                            // circle: that circle is the weight reserved for
                            // "this creates something". Going to the journal
                            // is navigation and shouldn't shout as loud.
                            TextButton(onClick = onNavigateToJournal) {
                                Text(stringResource(Res.string.home_see_all))
                            }
                        },
                    )
                }
                val recentItems = ledgerState.recent
                if (recentItems.isEmpty() && ledgerState.loaded) {
                    item(key = "recent-empty") {
                        EmptyHint(title = stringResource(Res.string.home_no_recent))
                    }
                }
                // No day grouping here at all: five rows don't need to be cut
                // into runs, and a day label sitting above the first row of a
                // run read as a caption belonging to that one movement rather
                // than as a heading over several. Each row carries its own
                // date instead; the journal, where runs are long, keeps the
                // day-header grouping.
                itemsIndexed(recentItems, key = { _, tx -> "recent-${tx.id}" }) { index, tx ->
                    TransactionRow(
                        tx = tx,
                        names = names,
                        types = types,
                        onOpen = { onOpenTransaction(tx.id) },
                        skin = rowSkin(first = index == 0, last = index == recentItems.lastIndex),
                        dateLabel = dayLabel(dayGroup(tx.date)),
                    )
                }
            }
        }
    }

}

@Composable
private fun OverflowItem(
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
 * Net worth hero: every commodity side by side, on the page background rather
 * than in a card, starting at the same left margin as the section headers
 * below it so the screen keeps a single reading edge. No "Patrimonio" label —
 * the biggest number on the screen, sitting above everything else, doesn't
 * need to be told what it is.
 */
@Composable
private fun NetWorthCard(state: LedgerState) {
    val lines = LedgerState.netWorth(state.tree, state.leafTotals).entries.sortedByDescending { abs(it.value) }
    var pickerOpen by remember { mutableStateOf(false) }
    // No card here on purpose: this is the top of the page, not one section
    // among others, so it sits straight on the screen background instead of
    // competing with the tonal cards below it for "boxed" attention.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // Long-press opens the net-worth picker: no visible button, this
            // is a settings affordance and the hero shouldn't advertise it.
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
                onLongClick = { pickerOpen = true },
            )
            // start inset matches SectionHeader's, so "Patrimonio", "Cuentas"
            // and "Movimientos" all start on the same vertical edge.
            .padding(top = 20.dp, bottom = 4.dp, start = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // All commodities share one row instead of stacking a line per
            // currency: a multi-currency ledger reads as one figure with
            // siblings beside it, not a list growing downward.
            if (lines.isEmpty()) {
                Text(
                    if (state.loaded) "${Money.DEFAULT_COMMODITY} ${formatMinorUnits(0)}" else "—",
                    style = MaterialTheme.typography.displaySmall,
                )
            }
            FlowRow(
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(24.dp),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp),
                modifier = Modifier.weight(1f),
            ) {
                lines.forEach { (commodity, minor) ->
                    Column {
                        Text(
                            commodity,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            formatMinorUnits(minor),
                            style = MaterialTheme.typography.headlineMedium,
                            color = amountColor(minor),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            // Only while there's nothing to show yet, or the user asked for a
            // refresh directly — a silent background sync after data is
            // already on screen doesn't get an animation to announce it. It
            // used to sit beside the label; with the label gone it trails the
            // figures instead.
            if (state.busy && (!state.loaded || state.pullRefreshing)) {
                Spacer(Modifier.width(12.dp))
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        state.error?.let { ErrorBanner(it, modifier = Modifier.padding(top = 14.dp)) }
    }
    if (pickerOpen) {
        NetWorthPickerSheet(state = state, onDismiss = { pickerOpen = false })
    }
}

/**
 * Section title with optional trailing content (totals, actions). Every
 * caller shares this one title style and trailing-button shape so sibling
 * sections read as the same kind of thing; [divider] draws the boundary
 * above a section that follows another one directly (the grouped cards
 * still carry the separation *within* a section, so the first header on a
 * screen — right after a hero card — leaves it off).
 */
@Composable
internal fun SectionHeader(
    title: String,
    divider: Boolean = false,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        if (divider) {
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                modifier = Modifier.padding(top = 20.dp),
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            // end inset mirrors the account rows' trailing icon column, so a
            // header action lines up with the rows underneath it.
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    top = if (divider) 16.dp else 24.dp,
                    bottom = 8.dp,
                    start = 4.dp,
                    end = 8.dp,
                )
                .heightIn(min = 32.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            trailing()
        }
    }
}

/**
 * The one trailing control every [SectionHeader] uses: a 32.dp tonal circle.
 * Accounts gets a "+", Recent gets a chevron — same button, same size, same
 * colors, different verb.
 */
@Composable
private fun SectionHeaderButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    FilledTonalIconButton(
        onClick = onClick,
        modifier = Modifier.size(32.dp),
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(18.dp))
    }
}

/** Centered empty state on a tonal card: a muted title and a single call to action. */
@Composable
internal fun EmptyHint(
    title: String,
    action: String? = null,
    onAction: () -> Unit = {},
) {
    Surface(
        shape = RoundedCornerShape(GroupRadius),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (action != null) {
                Spacer(Modifier.height(12.dp))
                FilledTonalButton(onClick = onAction) { Text(action) }
            }
        }
    }
}
