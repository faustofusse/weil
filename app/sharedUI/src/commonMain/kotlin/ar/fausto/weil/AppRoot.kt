package ar.fausto.weil

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import androidx.navigationevent.NavigationEventTransitionState
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import io.github.alexzhirkevich.qrose.rememberQrCodePainter
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import weil.app.sharedui.generated.resources.Res
import weil.app.sharedui.generated.resources.broker_adjust_payee
import weil.app.sharedui.generated.resources.action_cancel
import weil.app.sharedui.generated.resources.app_title
import weil.app.sharedui.generated.resources.login_approved
import weil.app.sharedui.generated.resources.login_approved_creating
import weil.app.sharedui.generated.resources.login_approve_hint
import weil.app.sharedui.generated.resources.login_back
import weil.app.sharedui.generated.resources.login_continue
import weil.app.sharedui.generated.resources.login_passkey_rejected
import weil.app.sharedui.generated.resources.login_continue_after_approval
import weil.app.sharedui.generated.resources.login_expires_in
import weil.app.sharedui.generated.resources.login_joining
import weil.app.sharedui.generated.resources.login_new_qr
import weil.app.sharedui.generated.resources.login_pair_entry
import weil.app.sharedui.generated.resources.login_pairing_expired
import weil.app.sharedui.generated.resources.login_pairing_qr_content
import weil.app.sharedui.generated.resources.login_qr_not_invite
import weil.app.sharedui.generated.resources.login_qr_not_invite_but_request
import weil.app.sharedui.generated.resources.login_qr_unavailable
import weil.app.sharedui.generated.resources.login_scan_invite
import weil.app.sharedui.generated.resources.login_show_request_qr
import weil.app.sharedui.generated.resources.login_subtitle
import weil.app.sharedui.generated.resources.qr_pay_handoff_failed
import weil.app.sharedui.generated.resources.qr_pay_no_wallet

/** Transition specs copied from the old finance app's NavDisplay setup. */
private val slideIn = { full: Int -> (full * 0.4f).toInt() }
private val slideOut = { full: Int -> (full * -0.2f).toInt() }

/**
 * The bottom bar's own exit/entry, played while a detail screen is pushed
 * over the tabs.
 *
 * It drops straight down past its own bottom edge instead of riding the
 * shell sideways: the bar is not part of the page that is leaving, it is the
 * app's furniture, and furniture that slides off to the left reads as if the
 * whole app had moved. Leaving is quick and linear-ish (it should be gone
 * before the incoming screen has settled, so it never hangs over a screen
 * that has no tabs); coming back is slower and decelerated, arriving a beat
 * after the tabs it belongs to are already on screen.
 */
private val BarExit = tween<Float>(180, easing = FastOutSlowInEasing)
private val BarEnter = tween<Float>(300, delayMillis = 40, easing = FastOutSlowInEasing)

/**
 * Extra travel beyond the bar's own height, to clear the create button that
 * straddles its top edge: translating by the height alone leaves the disc's
 * bottom sliver stuck to the bottom of the screen.
 */
private val BarFabClearance = 34.dp

@Composable
fun RootScreen(
    graph: AppGraph,
    /**
     * Route pushed on top of Home on first composition while signed in.
     * Only the desktop shot harness uses it, to screenshot inner screens
     * headlessly; real entry points never set it.
     */
    initialRoute: Any? = null,
    /**
     * Opens the create panel on first composition. Same as [initialRoute]:
     * the panel is an overlay rather than a route, so the harness needs its
     * own way to ask for it.
     */
    openCreate: Boolean = false,
    /** Tab the shell starts on. Harness-only, like [initialRoute]. */
    startTab: AppTab = AppTab.Home,
    /**
     * Palette to start on, overriding the default. Harness-only: the real
     * app takes it from the synced `settings` row, which the shot's
     * sandboxed session has no way to have written.
     */
    initialTheme: AppTheme? = null,
) {
    val authState by graph.auth.state.collectAsState()
    val scope = rememberCoroutineScope()
    val themeState = remember { AppThemeState(initialTheme ?: AppTheme.Menta) }

    // The palette is a settings row, not device state, so it has to be read
    // back once there is a session to read it from. Until then (and if the
    // read fails, or names a theme this build doesn't have) the default
    // stands: an unreadable preference must not block the splash.
    val loggedInNow = authState is AuthState.LoggedIn
    LaunchedEffect(loggedInNow) {
        if (loggedInNow && initialTheme == null) {
            val stored = runCatching { graph.settings.all()[THEME_KEY] }.getOrNull()
            AppTheme.byId(stored)?.let { themeState.theme = it }
        }
    }

    // Emails have no listener: the worker writes them into the user's
    // database server-side, so the device only meets them on a sync. Opening
    // the app is the sweep's trigger — trying harder (a background worker,
    // a poll) would be a second scheduling problem for a path whose whole cost
    // is two model calls per receipt. Failures are swallowed: an unreachable
    // worker must not colour the first screen.
    LaunchedEffect(loggedInNow) {
        if (loggedInNow) runCatching { graph.autoRecord.sweepEmails() }
    }

    // The theme wraps every state, splash included, so nothing renders unthemed.
    // The state rides in a CompositionLocal: a future settings screen swaps
    // themes with one assignment, no prop drilling.
    CompositionLocalProvider(LocalAppThemeState provides themeState) {
        // System chrome follows the palette, not the system's dark mode.
        SystemBarsEffect(themeState.theme.dark)
        FinanceTheme(themeState.theme) {
            AnimatedContent(
                targetState = authState is AuthState.Restoring,
                transitionSpec = {
                    val enter = fadeIn(tween(260, easing = FastOutSlowInEasing)) +
                        scaleIn(initialScale = 0.97f, animationSpec = tween(260, easing = FastOutSlowInEasing))
                    val exit = fadeOut(tween(160)) +
                        scaleOut(targetScale = 0.97f, animationSpec = tween(160))
                    enter togetherWith exit
                },
                label = "authState",
            ) { restoring ->
                if (restoring) {
                    SplashScreen()
                } else {
                    // The login screen lives in the back stack: sign-in/out swap the
                    // stack between [LoginRoute] and [HomeRoute], and NavDisplay
                    // animates that replace with the shared transition specs.
                    val loggedIn = authState is AuthState.LoggedIn
                    val ledgerState = remember(loggedIn) {
                        LedgerState(graph.accounts, graph.ledger, graph.settings, graph.brokers, graph.officialRates)
                    }
                    // Hoisted above the nav host, same reasoning as [ledgerState]: the
                    // journal keeps its loaded page across a visit to a transaction and
                    // back, instead of re-fetching every time the screen re-enters
                    // composition.
                    val journalState = remember(loggedIn) {
                        JournalState(graph.ledger, graph.accounts)
                    }
                    // Home's already-loaded recent transactions paint the journal
                    // instantly on first visit — no skeleton for rows the app showed
                    // a moment ago on Home.
                    LaunchedEffect(ledgerState.recent) { journalState.seed(ledgerState.recent) }
                    // Same reasoning as [journalState]: surviving above the nav host
                    // means a visit to an email's/notification's detail screen and back
                    // doesn't re-fetch and re-show a skeleton.
                    val emailsState = remember(loggedIn) { EmailsState(graph.emails) }
                    val notificationsState = remember(loggedIn) { NotificationsState(graph.notifications) }
                    // Same reasoning: the suggestion bench costs two model
                    // calls and ~20 s, so stepping into the review screen and
                    // back must find the trace still there.
                    val suggestDebugState = remember(loggedIn) { SuggestDebugState(graph.suggestions) }
                    val chainState = remember(loggedIn) { ChainState(graph.chain, { graph.scanner }) }
                    // Home greets with the name and Profile edits it; one
                    // holder above the nav host keeps the two in step.
                    val userState = remember(loggedIn) { UserState(graph.chain, graph.settings) }
                    val whatsappState = remember(loggedIn) { WhatsappState(graph.whatsapp) }
                    val snackbarHostState = remember(loggedIn) { SnackbarHostState() }
                    val backStack = remember(loggedIn) {
                        mutableStateListOf<Any>(if (loggedIn) TabsRoute else LoginRoute).apply {
                            if (loggedIn && initialRoute != null) add(initialRoute)
                        }
                    }

                    // The tabs' own stack, nested inside the shell: always
                    // exactly one entry. A tab switch is a sideways move and
                    // a push is a step into something, and they must not
                    // animate alike — two nav hosts is what lets each have
                    // its own transition, and it keeps the bar out of the
                    // animation entirely (it's drawn by the shell, above the
                    // host, so it never fades with the content it commands).
                    val tabStack = remember(loggedIn) {
                        mutableStateListOf<Any>(
                            when (startTab) {
                                AppTab.Home -> HomeRoute
                                AppTab.Movements -> JournalRoute
                                AppTab.Categories -> CategoriesRoute
                                AppTab.Investments -> InvestmentsRoute
                            },
                        )
                    }
                    val currentTab = when (tabStack.lastOrNull()) {
                        JournalRoute -> AppTab.Movements
                        CategoriesRoute -> AppTab.Categories
                        InvestmentsRoute -> AppTab.Investments
                        else -> AppTab.Home
                    }

                    // The create panel is a layer over the app, not a
                    // destination: it rises out of the bar and leaves the
                    // header of whatever is behind it visible. Keeping it out
                    // of the back stack means dismissing a form never also
                    // pops the tab you were on.
                    var creating by remember(loggedIn) { mutableStateOf(openCreate && loggedIn) }

                    // Measured once the bar is laid out and handed down to
                    // the shell, which reserves exactly this much at the
                    // bottom of every tab. Guessing it would be wrong on any
                    // device whose navigation-bar inset isn't the one guessed.
                    val density = LocalDensity.current
                    var barHeight by remember { mutableStateOf(0.dp) }

                    fun navigate(route: Any) {
                        backStack.add(route)
                    }

                    fun pop() {
                        if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
                    }

                    // Tabs are roots, not pushes: selecting one replaces the
                    // nested stack's single entry, so the bar never
                    // accumulates a back trail of sideways moves (Inicio →
                    // Categorías → Inicio would otherwise need two backs to
                    // leave).
                    fun selectTab(tab: AppTab) {
                        val route: Any = when (tab) {
                            AppTab.Home -> HomeRoute
                            AppTab.Movements -> JournalRoute
                            AppTab.Categories -> CategoriesRoute
                            AppTab.Investments -> InvestmentsRoute
                        }
                        if (tabStack.lastOrNull() == route) return
                        tabStack.clear()
                        tabStack.add(route)
                    }

                    // Home is only ever a tab, so it is written here as the
                    // shell's entry rather than as a pushable destination.
                    val homeTab: @Composable (@Composable () -> Unit) -> Unit = { bar ->
                    // Captured for the QR handoff, which runs
                    // in a callback, not in composition.
                    val handoffFailed = stringResource(Res.string.qr_pay_handoff_failed)
                    val noWallet = stringResource(Res.string.qr_pay_no_wallet)
                    // The classic list-shaped Home is still
                    // in HomeScreen.kt and takes the same
                    // arguments; swap the call to compare.
                    HomeDashboardScreen(
                        ledgerState = ledgerState,
                        userState = userState,
                        documents = { graph.documents },
                        onImportDocument = { navigate(ImportReviewRoute(it)) },
                        scanner = { graph.scanner },
                        // Hand off first — the user is standing
                        // at a counter — then write the row from
                        // the coroutine. The amount is a
                        // placeholder (the QR has none); the
                        // wallet's push completes it later.
                        // Nothing is recorded when the wallet
                        // never opened: no handoff, no payment.
                        onPayWithQr = { qr ->
                            val wallet = graph.wallet
                            val opened = wallet?.payWithMercadoPago(qr.raw) == true
                            // The category guess starts with the
                            // handoff, not after the write: the
                            // user is inside the wallet for ten
                            // seconds or more, so a few hundred
                            // milliseconds of model spend for
                            // free. It never blocks the row.
                            val guess = if (opened) {
                                scope.async {
                                    runCatching { graph.qrPayments.guessCategory(qr) }.getOrNull()
                                }
                            } else {
                                null
                            }
                            scope.launch {
                                // The payload outlives the
                                // handoff in one synced row, so a
                                // failure in a shop is still
                                // debuggable at home.
                                runCatching {
                                    graph.settings.set(
                                        QR_PAY_LAST_KEY,
                                        "${epochMillis()}|${if (opened) "ok" else "fail"}|${qr.raw}",
                                    )
                                }
                                if (opened) {
                                    val recorded = runCatching { graph.qrPayments.record(qr) }
                                        .onSuccess { ledgerState.refresh() }
                                        .onFailure { Feedback.show(it.message ?: it.toString()) }
                                        .getOrNull()
                                    // The row already exists and
                                    // is correct without this;
                                    // the guess only moves it off
                                    // the fallback category, so
                                    // every failure stays silent.
                                    val picked = guess?.await()?.accountId
                                    if (recorded != null && picked != null) {
                                        val moved = runCatching {
                                            graph.qrPayments.applyCategory(recorded, picked)
                                        }.getOrDefault(false)
                                        if (moved) ledgerState.refresh()
                                    }
                                }
                            }
                            if (!opened) {
                                Feedback.show(if (wallet == null) noWallet else handoffFailed)
                            }
                        },
                        onNavigateToTree = { navigate(AccountsTreeRoute) },
                        onNewTransaction = { kind -> navigate(TransactionQuickRoute(kind)) },
                        onNavigateToNotifications = { navigate(NotificationsRoute) },
                        onNavigateToEmails = { navigate(EmailsRoute) },
                        // "Ver todo" switches to the Movimientos tab rather
                        // than pushing Journal on top of Home: it's the same
                        // screen the bar already opens, so landing there
                        // should feel like the tab lighting up, not a page
                        // you now have to back out of to reach the tab bar's
                        // own Movimientos.
                        onNavigateToJournal = { selectTab(AppTab.Movements) },
                        onNavigateToAccount = { navigate(AccountDetailRoute(it)) },
                        onOpenTransaction = { navigate(TransactionDetailRoute(it)) },
                        onNavigateToAddAccount = { navigate(AccountAddRoute(AccountType.Asset)) },
                        onNavigateToProfile = { navigate(ProfileRoute) },
                        bottomBar = bar,
                    )
                    }

                    // Journal and Categorías are each both a tab and
                    // a pushable destination, so each is written once here:
                    // `bar` is the shell's reserved strip when it renders as
                    // a tab and null when pushed (which is what makes the
                    // screen show a back arrow instead).
                    val journalScreen: @Composable ((@Composable () -> Unit)?) -> Unit = { bar ->
                        JournalScreen(
                            state = journalState,
                            onNavigateBack = { pop() },
                            bottomBar = bar,
                            onNavigateToNew = { navigate(TransactionNewRoute()) },
                            onOpenTransaction = { navigate(TransactionDetailRoute(it)) },
                        )
                    }
                    val categoriesScreen: @Composable (@Composable () -> Unit) -> Unit = { bar ->
                        CategoriesScreen(
                            ledgerState = ledgerState,
                            // A category opens its own screen (subcategory
                            // chips + movements), not the asset register.
                            onNavigateToAccount = { navigate(CategoryDetailRoute(it)) },
                            bottomBar = bar,
                        )
                    }

                    // Documents shared into the app from outside (Android
                    // ACTION_SEND) land here; anything that arrived before the
                    // UI existed is replayed on subscribe. Only while signed
                    // in — the review screen needs an authenticated session.
                    if (loggedIn) {
                        androidx.compose.runtime.DisposableEffect(Unit) {
                            SharedImportInbox.observe { document ->
                                if (backStack.lastOrNull() !is ImportReviewRoute) {
                                    backStack.add(ImportReviewRoute(document))
                                }
                            }
                            onDispose { SharedImportInbox.stopObserving() }
                        }
                    }

                    Feedback.host = snackbarHostState
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background,
                    ) {
                        Box {
                        NavDisplay(
                            backStack = backStack,
                            onBack = { pop() },
                            entryProvider = entryProvider {
                                entry<LoginRoute> {
                                    LoginScreen(
                                        onSignIn = { graph.auth.signIn() },
                                        onJoinChain = { id, token -> graph.auth.joinChain(id, token) },
                                        chain = graph.chain,
                                        scanner = { graph.scanner },
                                    )
                                }
                                entry<TabsRoute> {
                                    TabShell(
                                        current = currentTab,
                                        stack = tabStack,
                                        onSelect = { selectTab(it) },
                                        barHeight = barHeight,
                                        coveredByOverlay = creating,
                                    ) { bar ->
                                        entry<HomeRoute> { homeTab(bar) }
                                        entry<JournalRoute> { journalScreen(bar) }
                                        entry<CategoriesRoute> { categoriesScreen(bar) }
                                        entry<InvestmentsRoute> {
                                            InvestmentsScreen(
                                                iol = graph.iol,
                                                brokers = graph.brokers,
                                                onReviewImport = { navigate(it) },
                                                bottomBar = bar,
                                            )
                                        }
                                    }
                                }
                                entry<CategoriesRoute> { categoriesScreen {} }
                                entry<CategoryDetailRoute> { route ->
                                    CategoryDetailScreen(
                                        ledgerState = ledgerState,
                                        categoryId = route.id,
                                        onNavigateBack = { pop() },
                                        onOpenTransaction = { navigate(TransactionDetailRoute(it)) },
                                    )
                                }
                                entry<AccountsTreeRoute> {
                                    AccountsTreeScreen(
                                        ledgerState = ledgerState,
                                        onNavigateBack = { pop() },
                                        onNavigateToAccount = { navigate(AccountDetailRoute(it)) },
                                        onNavigateToAdd = { navigate(AccountAddRoute()) },
                                    )
                                }
                                entry<AccountAddRoute> { route ->
                                    AccountAddScreen(
                                        state = ledgerState,
                                        initialType = route.initialType,
                                        onSaved = { pop() },
                                        onNavigateBack = { pop() },
                                    )
                                }
                                entry<TransactionQuickRoute> { route ->
                                    TransactionQuickScreen(
                                        ledger = graph.ledger,
                                        accounts = graph.accounts,
                                        settings = graph.settings,
                                        kind = route.kind,
                                        suggester = graph.categories,
                                        onSaved = { pop() },
                                        onNavigateBack = { pop() },
                                    )
                                }
                                entry<JournalRoute> { journalScreen(null) }
                                entry<TransactionNewRoute> { route ->
                                    // The editor types an instrument at its scale, which
                                    // comes with the valuation; a cold start straight
                                    // here (no Home underneath) hasn't loaded it yet.
                                    LaunchedEffect(Unit) { if (!ledgerState.loaded) ledgerState.refresh() }
                                    TransactionEditScreen(
                                        ledger = graph.ledger,
                                        accounts = graph.accounts,
                                        editId = null,
                                        prefillAccountId = route.accountId,
                                        valuation = ledgerState.valuation,
                                        onSaved = { pop() },
                                        onNavigateBack = { pop() },
                                    )
                                }
                                entry<TransactionDetailRoute> { route ->
                                    // Quantities and the @ price need the valuation (scales);
                                    // a cold start straight here hasn't loaded it.
                                    LaunchedEffect(Unit) { if (!ledgerState.loaded) ledgerState.refresh() }
                                    TransactionDetailScreen(
                                        ledger = graph.ledger,
                                        accounts = graph.accounts,
                                        id = route.id,
                                        onNavigateBack = { pop() },
                                        onNavigateToEdit = { navigate(TransactionEditRoute(it)) },
                                        onNavigateToAccount = { navigate(AccountDetailRoute(it)) },
                                        onOpenNotification = { navigate(NotificationDetailRoute(it)) },
                                        onOpenEmail = { navigate(EmailDetailRoute(it)) },
                                        onOpenDocument = { navigate(DocumentRoute(it)) },
                                        onTrySuggestion = { navigate(TransactionSimilarRoute(route.id)) },
                                        valuation = ledgerState.valuation,
                                    )
                                }
                                entry<TransactionSimilarRoute> { route ->
                                    TransactionSimilarScreen(
                                        embeddings = graph.embeddings,
                                        ledger = graph.ledger,
                                        id = route.id,
                                        onNavigateBack = { pop() },
                                        onOpenTransaction = { navigate(TransactionDetailRoute(it)) },
                                        onOpenNotification = { navigate(NotificationDetailRoute(it)) },
                                        onOpenEmail = { navigate(EmailDetailRoute(it)) },
                                    )
                                }
                                entry<DocumentRoute> { route ->
                                    DocumentScreen(
                                        documents = graph.documentStore,
                                        docId = route.docId,
                                        onNavigateBack = { pop() },
                                    )
                                }
                                entry<TransactionEditRoute> { route ->
                                    // The editor types an instrument at its scale, which
                                    // comes with the valuation; a cold start straight
                                    // here (no Home underneath) hasn't loaded it yet.
                                    LaunchedEffect(Unit) { if (!ledgerState.loaded) ledgerState.refresh() }
                                    TransactionEditScreen(
                                        ledger = graph.ledger,
                                        accounts = graph.accounts,
                                        editId = route.id,
                                        valuation = ledgerState.valuation,
                                        onSaved = { pop() },
                                        onNavigateBack = { pop() },
                                    )
                                }
                                entry<AccountDetailRoute> { route ->
                                    // Title, balance, quantities and positions all come
                                    // from the ledger state; a cold start straight here
                                    // hasn't loaded it.
                                    LaunchedEffect(Unit) { if (!ledgerState.loaded) ledgerState.refresh() }
                                    AccountDetailScreen(
                                        ledgerState = ledgerState,
                                        accountId = route.id,
                                        initialCommodity = route.commodity,
                                        onNavigateBack = { pop() },
                                        onOpenTransaction = { navigate(TransactionDetailRoute(it)) },
                                        onNavigateToNew = { navigate(TransactionNewRoute(route.id)) },
                                    )
                                }
                                entry<ImportReviewRoute> { route ->
                                    ImportReviewScreen(
                                        source = ReviewSource.Document(route.document),
                                        imports = graph.imports,
                                        ledger = graph.ledger,
                                        accounts = graph.accounts,
                                        settings = graph.settings,
                                        onDone = { pop() },
                                        onNavigateBack = { pop() },
                                    )
                                }
                                entry<EmailsRoute> {
                                    EmailScreen(
                                        state = emailsState,
                                        onNavigateBack = { pop() },
                                        onOpenEmail = { navigate(EmailDetailRoute(it)) },
                                    )
                                }
                                entry<EmailDetailRoute> { route ->
                                    EmailDetailScreen(
                                        emails = graph.emails,
                                        id = route.id,
                                        onNavigateBack = { pop() },
                                        onTrySuggestion = {
                                            navigate(SuggestSourcesRoute(route.id, EventSource.Email))
                                        },
                                    )
                                }
                                entry<NotificationsRoute> {
                                    NotificationsScreen(
                                        state = notificationsState,
                                        onNavigateBack = { pop() },
                                        onOpenNotification = { navigate(NotificationDetailRoute(it)) },
                                    )
                                }
                                entry<NotificationDetailRoute> { route ->
                                    NotificationDetailScreen(
                                        notifications = graph.notifications,
                                        ledger = graph.ledger,
                                        accounts = graph.accounts,
                                        id = route.id,
                                        onNavigateBack = { pop() },
                                        onOpenTransaction = { navigate(TransactionDetailRoute(it)) },
                                        onTrySuggestion = { navigate(SuggestSourcesRoute(route.id)) },
                                    )
                                }
                                entry<SuggestSourcesRoute> { route ->
                                    SuggestSourcesScreen(
                                        embeddings = graph.embeddings,
                                        ledger = graph.ledger,
                                        id = route.id,
                                        source = route.source,
                                        onNavigateBack = { pop() },
                                        onTrySuggestion = {
                                            navigate(SuggestDebugRoute(route.id, route.source))
                                        },
                                        onOpenNotification = { navigate(NotificationDetailRoute(it)) },
                                        onOpenEmail = { navigate(EmailDetailRoute(it)) },
                                        onOpenTransaction = { navigate(TransactionDetailRoute(it)) },
                                    )
                                }
                                entry<SuggestDebugRoute> { route ->
                                    SuggestDebugScreen(
                                        state = suggestDebugState,
                                        id = route.id,
                                        source = route.source,
                                        onNavigateBack = { pop() },
                                        onReview = { candidate ->
                                            navigate(
                                                SuggestedReviewRoute(
                                                    candidate = candidate,
                                                    ref = route.id,
                                                    title = candidate.payee.ifBlank { "Movimiento" },
                                                    source = route.source,
                                                ),
                                            )
                                        },
                                    )
                                }
                                entry<SuggestedReviewRoute> { route ->
                                    ImportReviewScreen(
                                        source = ReviewSource.Suggested(
                                            candidate = route.candidate,
                                            kind = route.source,
                                            ref = route.ref,
                                            title = route.title,
                                        ),
                                        imports = graph.imports,
                                        ledger = graph.ledger,
                                        accounts = graph.accounts,
                                        settings = graph.settings,
                                        onDone = { pop() },
                                        onNavigateBack = { pop() },
                                    )
                                }
                                // Pushed from the account icon in Inicio's top
                                // bar; no longer a tab, so it always wears the
                                // back arrow (bottomBar = null).
                                entry<BrokerImportRoute> { route ->
                                    val adjustPayee = stringResource(Res.string.broker_adjust_payee, route.brokerName)
                                    BrokerImportScreen(
                                        route = route,
                                        ledger = graph.ledger,
                                        apply = { plan ->
                                            when (route.provider) {
                                                IOL_PROVIDER -> graph.iol.apply(plan)
                                                else -> graph.brokers.apply(plan)
                                            }.also { ledgerState.refresh() }
                                        },
                                        adjust = { difference ->
                                            graph.brokers.adjustOpening(route.accounts, difference, adjustPayee)
                                                .also { ledgerState.refresh() }
                                        },
                                        onDone = { pop() },
                                        onNavigateBack = { pop() },
                                    )
                                }
                                entry<ProfileRoute> {
                                    ProfileScreen(
                                        chain = graph.chain,
                                        chainState = chainState,
                                        whatsappState = whatsappState,
                                        embeddings = graph.embeddings,
                                        userState = userState,
                                        settings = graph.settings,
                                        onNavigateBack = { pop() },
                                        onSignOut = { scope.launch { graph.auth.signOut() } },
                                    )
                                }
                            },
                            // Every move on *this* stack is a step into
                            // something; sideways moves happen a level down,
                            // inside the shell, with their own spec.
                            transitionSpec = {
                                (slideInHorizontally(initialOffsetX = slideIn) + fadeIn()) togetherWith
                                    (slideOutHorizontally(targetOffsetX = slideOut) + fadeOut())
                            },
                            popTransitionSpec = {
                                (slideInHorizontally(initialOffsetX = slideOut) + fadeIn()) togetherWith
                                    (slideOutHorizontally(targetOffsetX = slideIn) + fadeOut())
                            },
                            predictivePopTransitionSpec = {
                                (slideInHorizontally(initialOffsetX = slideOut) + fadeIn()) togetherWith
                                    (slideOutHorizontally(targetOffsetX = slideIn) + fadeOut())
                            },
                        )
                        // The bar lives here, over the nav host rather than
                        // inside the tab shell, so a push slides the page
                        // away underneath it while the bar itself drops out
                        // the bottom — and comes back up when you return.
                        // Only the tabs own it: three levels into a stack it
                        // would be promising a "you are here" it can't keep.
                        if (loggedIn) {
                            // Translated rather than added and removed: kept
                            // in the composition it stays measured (the
                            // shell's spacer depends on that height) and
                            // nothing clips the create button, which is drawn
                            // outside the bar's own bounds.
                            val barShown = backStack.lastOrNull() is TabsRoute

                            // Predictive back moves the content by *seeking*
                            // NavDisplay's transition with the finger, so a
                            // bar animated on its own timer would sit still
                            // through the drag and then jump when the pop
                            // lands. The gesture is readable without stealing
                            // it: the dispatcher publishes progress on a
                            // shared flow (NavDisplay still owns the handler —
                            // registering one here would take the gesture away
                            // from it and the content would stop animating).
                            val dispatcher =
                                LocalNavigationEventDispatcherOwner.current?.navigationEventDispatcher
                            val gestureState: StateFlow<NavigationEventTransitionState> =
                                remember(dispatcher) {
                                    dispatcher?.transitionState
                                        ?: MutableStateFlow(NavigationEventTransitionState.Idle)
                                }
                            val gesture by gestureState.collectAsState()
                            // Only a drag that would uncover the tabs moves
                            // the bar: deeper in a stack the pop reveals
                            // another barless screen, and on a tab the
                            // gesture goes to the shell's own handler.
                            val uncoversTabs = !barShown &&
                                backStack.getOrNull(backStack.lastIndex - 1) is TabsRoute
                            val dragged = gesture as? NavigationEventTransitionState.InProgress
                            val dragging = dragged != null && uncoversTabs
                            val progress = dragged?.latestEvent?.progress ?: 0f
                            // One Animatable for both paths, so the release
                            // continues from wherever the finger left the bar
                            // instead of restarting from off-screen.
                            val shown = remember { Animatable(if (barShown) 1f else 0f) }
                            LaunchedEffect(barShown, dragging, progress) {
                                if (dragging) {
                                    // Eased, not raw: NavDisplay seeks its
                                    // transition with the same progress and
                                    // the spec's easing is applied inside, so
                                    // a linear bar would drift ahead of the
                                    // page it belongs to.
                                    shown.snapTo(FastOutSlowInEasing.transform(progress))
                                } else {
                                    shown.animateTo(
                                        if (barShown) 1f else 0f,
                                        if (barShown) BarEnter else BarExit,
                                    )
                                }
                            }
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .offset(y = (barHeight + BarFabClearance) * (1f - shown.value))
                                    .onSizeChanged {
                                        barHeight = with(density) { it.height.toDp() }
                                    },
                            ) {
                                AppBottomBar(
                                    current = currentTab,
                                    onSelect = { selectTab(it) },
                                    onNew = { creating = true },
                                )
                            }
                        }
                        if (loggedIn) {
                            TransactionSheet(
                                visible = creating,
                                state = ledgerState,
                                suggester = graph.categories,
                                onDismiss = { creating = false },
                                // "Ver más" hands the same intent to the full
                                // editor, which is where a date, a note or a
                                // third posting live.
                                onMore = {
                                    creating = false
                                    navigate(TransactionNewRoute())
                                },
                                onSaved = {
                                    creating = false
                                    // The row is already on screen (the panel
                                    // wrote it optimistically); these only
                                    // reconcile with the server afterwards.
                                    ledgerState.refresh()
                                    journalState.refresh()
                                },
                            )
                        }
                        SnackbarHost(
                            hostState = snackbarHostState,
                            modifier = Modifier.align(Alignment.BottomCenter),
                        )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SplashScreen() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f))
    }
}

@Composable
fun LoginScreen(
    onSignIn: suspend () -> Unit,
    onJoinChain: suspend (chainId: String, token: String?) -> Unit,
    chain: ChainRepository,
    scanner: () -> QrScanner?,
) {
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val rejectedMessage = stringResource(Res.string.login_passkey_rejected)

    fun launchAction(action: suspend () -> Unit) {
        scope.launch {
            busy = true
            error = null
            try {
                action()
            } catch (_: PasskeyCancelled) {
            } catch (_: PasskeyRejected) {
                error = rejectedMessage
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                error = e.message ?: e.toString()
            } finally {
                busy = false
            }
        }
    }

    // Subtle entrance: title first, button right after.
    val titleFade by animateFloatAsState(
        targetValue = 1f,
        animationSpec = tween(400, delayMillis = 50, easing = FastOutSlowInEasing),
        label = "titleFade",
    )
    val buttonFade by animateFloatAsState(
        targetValue = 1f,
        animationSpec = tween(400, delayMillis = 180, easing = FastOutSlowInEasing),
        label = "buttonFade",
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                stringResource(Res.string.app_title),
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.graphicsLayer(alpha = titleFade),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(Res.string.login_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
                modifier = Modifier.graphicsLayer(alpha = titleFade),
            )
            Spacer(Modifier.height(40.dp))
            Button(
                onClick = { launchAction(onSignIn) },
                enabled = !busy,
                shape = RoundedCornerShape(24.dp),
                contentPadding = PaddingValues(horizontal = 28.dp, vertical = 14.dp),
                modifier = Modifier.graphicsLayer(alpha = buttonFade),
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text(stringResource(Res.string.login_continue))
                }
            }
            Spacer(Modifier.height(20.dp))
            Text(
                error ?: "",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            PairingSection(
                chain = chain,
                scanner = scanner,
                onJoinChain = onJoinChain,
            )
        }
    }
}

/**
 * Device-pairing entry points on the login screen: scan an invite QR shown by
 * an already-signed-in device, or show a request QR for that device to scan.
 */
@Composable
private fun PairingSection(
    chain: ChainRepository,
    scanner: () -> QrScanner?,
    onJoinChain: suspend (chainId: String, token: String?) -> Unit,
) {
    var optionsOpen by remember { mutableStateOf(false) }
    var request by remember { mutableStateOf<ChainRequest?>(null) }
    var expiresAt by remember { mutableStateOf(0L) }
    var status by remember { mutableStateOf("waiting") }
    var localError by remember { mutableStateOf<String?>(null) }
    var joining by remember { mutableStateOf(false) }
    var joinAttempt by remember { mutableStateOf(0) }
    var now by remember { mutableStateOf(epochMillis()) }
    val scope = rememberCoroutineScope()
    val qrUnavailable = stringResource(Res.string.login_qr_unavailable)
    val notAnInvite = stringResource(Res.string.login_qr_not_invite)
    val isARequest = stringResource(Res.string.login_qr_not_invite_but_request)
    val cancelLabel = stringResource(Res.string.action_cancel)

    fun showRequestQr() {
        scope.launch {
            localError = null
            try {
                val created = chain.request()
                request = created
                expiresAt = epochMillis() + created.expiresIn * 1000
                status = "waiting"
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                localError = e.message ?: e.toString()
            }
        }
    }

    fun scanInvite() {
        scope.launch {
            localError = null
            val qr = scanner()
            if (qr == null) {
                localError = qrUnavailable
                return@launch
            }
            try {
                val raw = qr.scan() ?: return@launch
                val link = ChainLink.parse(raw, AuthConfig.SLUG)
                when {
                    link == null -> localError = notAnInvite
                    link.action != ChainLink.Action.Join ->
                        localError = isARequest
                    else -> {
                        joining = true
                        try {
                            onJoinChain(link.id, link.token)
                        } catch (_: PasskeyCancelled) {
                        } catch (e: Throwable) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            localError = e.message ?: e.toString()
                        } finally {
                            joining = false
                        }
                    }
                }
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                localError = e.message ?: e.toString()
            }
        }
    }

    // One-second ticker while a request QR is on screen.
    LaunchedEffect(request?.id) {
        if (request == null) return@LaunchedEffect
        while (true) {
            now = epochMillis()
            delay(1000)
        }
    }

    // Poll the request until an existing device approves or it expires.
    LaunchedEffect(request?.id) {
        val current = request ?: return@LaunchedEffect
        while (isActive) {
            try {
                when (chain.requestStatus(current.id).status) {
                    "approved" -> {
                        status = "approved"
                        return@LaunchedEffect
                    }
                    "expired" -> {
                        status = "expired"
                        return@LaunchedEffect
                    }
                }
            } catch (_: Throwable) {
                // transient network errors — keep polling until expiry
            }
            delay(2000)
        }
    }

    // Join (auto on first approval; retried from the Continue button).
    LaunchedEffect(status, joinAttempt) {
        val current = request ?: return@LaunchedEffect
        if (status != "approved") return@LaunchedEffect
        joining = true
        try {
            onJoinChain(current.id, null)
            // Success flips the auth state; the back stack swaps to home.
        } catch (_: PasskeyCancelled) {
            // user aborted the ceremony — Continue retries
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            localError = e.message ?: e.toString()
        } finally {
            joining = false
        }
    }

    fun reset() {
        request = null
        status = "waiting"
        localError = null
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        localError?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
        }

        val current = request
        when {
            current == null && !optionsOpen -> {
                TextButton(onClick = { optionsOpen = true }) {
                    Text(stringResource(Res.string.login_pair_entry))
                }
            }
            current == null -> {
                if (scanner() != null) {
                    TextButton(onClick = { scanInvite() }, enabled = !joining) {
                        Text(
                            if (joining) stringResource(Res.string.login_joining)
                            else stringResource(Res.string.login_scan_invite),
                        )
                    }
                }
                TextButton(onClick = { showRequestQr() }, enabled = !joining) {
                    Text(stringResource(Res.string.login_show_request_qr))
                }
                TextButton(onClick = {
                    optionsOpen = false
                    localError = null
                }) {
                    Text(stringResource(Res.string.login_back))
                }
            }
            status == "expired" -> {
                Text(
                    stringResource(Res.string.login_pairing_expired),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                )
                TextButton(onClick = { showRequestQr() }) {
                    Text(stringResource(Res.string.login_new_qr))
                }
                TextButton(onClick = { reset() }) {
                    Text(cancelLabel)
                }
            }
            else -> {
                if (status == "approved") {
                    Text(
                        if (joining) stringResource(Res.string.login_approved_creating)
                        else stringResource(Res.string.login_approved),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                    )
                    if (!joining) {
                        TextButton(onClick = { joinAttempt++ }) {
                            Text(stringResource(Res.string.login_continue_after_approval))
                        }
                    }
                } else {
                    Image(
                        painter = rememberQrCodePainter(current.url),
                        contentDescription = stringResource(Res.string.login_pairing_qr_content),
                        // White backing is theme-independent: scanners need max contrast.
                        modifier = Modifier
                            .padding(horizontal = 48.dp, vertical = 8.dp)
                            .background(Color.White, RoundedCornerShape(12.dp))
                            .padding(10.dp),
                    )
                    Text(
                        stringResource(Res.string.login_approve_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
                        textAlign = TextAlign.Center,
                    )
                    val remaining = ((expiresAt - now) / 1000).coerceAtLeast(0)
                    Text(
                        stringResource(
                            Res.string.login_expires_in,
                            "${remaining / 60}:${(remaining % 60).toString().padStart(2, '0')}",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                TextButton(onClick = { reset() }) {
                    Text(cancelLabel)
                }
            }
        }
    }
}
