package ar.fausto.weil

import androidx.compose.animation.AnimatedContent
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import io.github.alexzhirkevich.qrose.rememberQrCodePainter
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Transition specs copied from the old finance app's NavDisplay setup. */
private val slideIn = { full: Int -> (full * 0.4f).toInt() }
private val slideOut = { full: Int -> (full * -0.2f).toInt() }

@Composable
fun RootScreen(graph: AppGraph) {
    val authState by graph.auth.state.collectAsState()
    val scope = rememberCoroutineScope()

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
            val accountsState = remember(graph.accounts) { AccountsState(graph.accounts) }
            val chainState = remember(loggedIn) { ChainState(graph.chain, { graph.scanner }) }
            val backStack = remember(loggedIn) {
                mutableStateListOf<Any>(if (loggedIn) HomeRoute else LoginRoute)
            }

            fun navigate(route: Any) {
                backStack.add(route)
            }

            fun pop() {
                if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
            }

            FinanceTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
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
                            entry<HomeRoute> {
                                HomeScreen(
                                    accountsState = accountsState,
                                    onNavigateToProfile = { navigate(ProfileRoute) },
                                    onNavigateToNotifications = { navigate(NotificationsRoute) },
                                )
                            }
                            entry<NotificationsRoute> {
                                NotificationsScreen(
                                    notifications = graph.notifications,
                                    onNavigateBack = { pop() },
                                )
                            }
                            entry<ProfileRoute> {
                                ProfileScreen(
                                    chainState = chainState,
                                    onNavigateBack = { pop() },
                                    onSignOut = { scope.launch { graph.auth.signOut() } },
                                )
                            }
                        },
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
                }
            }
        }
    }
}

/** Accent colors of the dark login canvas (background comes from the theme). */
private val LoginError = Color(0xFFCF6679)
private val LoginButtonContent = Color(0xFF311B92)

@Composable
private fun SplashScreen() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AppBackground),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(color = Color.White.copy(alpha = 0.7f))
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

    fun launchAction(action: suspend () -> Unit) {
        scope.launch {
            busy = true
            error = null
            try {
                action()
            } catch (_: PasskeyCancelled) {
            } catch (e: Throwable) {
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
            .background(AppBackground),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "Finance",
                style = MaterialTheme.typography.headlineLarge,
                color = Color.White,
                modifier = Modifier.graphicsLayer(alpha = titleFade),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Your accounts, one place",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.55f),
                modifier = Modifier.graphicsLayer(alpha = titleFade),
            )
            Spacer(Modifier.height(40.dp))
            Button(
                onClick = { launchAction(onSignIn) },
                enabled = !busy,
                shape = RoundedCornerShape(24.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFD0BCFF),
                    contentColor = LoginButtonContent,
                ),
                contentPadding = PaddingValues(horizontal = 28.dp, vertical = 14.dp),
                modifier = Modifier.graphicsLayer(alpha = buttonFade),
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = LoginButtonContent,
                    )
                } else {
                    Text("Continue with passkey")
                }
            }
            Spacer(Modifier.height(20.dp))
            Text(
                error ?: "",
                color = LoginError,
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

    fun showRequestQr() {
        scope.launch {
            localError = null
            try {
                val created = chain.request()
                request = created
                expiresAt = epochMillis() + created.expiresIn * 1000
                status = "waiting"
            } catch (e: Throwable) {
                localError = e.message ?: e.toString()
            }
        }
    }

    fun scanInvite() {
        scope.launch {
            localError = null
            val qr = scanner()
            if (qr == null) {
                localError = "QR scanning is not available on this device"
                return@launch
            }
            try {
                val raw = qr.scan() ?: return@launch
                val link = ChainLink.parse(raw, AuthConfig.SLUG)
                when {
                    link == null -> localError = "That QR code is not a device invite"
                    link.action != ChainLink.Action.Join ->
                        localError = "That QR code is a pairing request, not an invite"
                    else -> {
                        joining = true
                        try {
                            onJoinChain(link.id, link.token)
                        } catch (_: PasskeyCancelled) {
                        } catch (e: Throwable) {
                            localError = e.message ?: e.toString()
                        } finally {
                            joining = false
                        }
                    }
                }
            } catch (e: Throwable) {
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
                color = LoginError,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
        }

        val current = request
        when {
            current == null && !optionsOpen -> {
                TextButton(onClick = { optionsOpen = true }) {
                    Text("Pair with an existing account")
                }
            }
            current == null -> {
                if (scanner() != null) {
                    TextButton(onClick = { scanInvite() }, enabled = !joining) {
                        Text(if (joining) "Joining…" else "Scan invite QR")
                    }
                }
                TextButton(onClick = { showRequestQr() }, enabled = !joining) {
                    Text("Show QR to the other device")
                }
                TextButton(onClick = {
                    optionsOpen = false
                    localError = null
                }) {
                    Text("Back")
                }
            }
            status == "expired" -> {
                Text(
                    "Pairing request expired",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.7f),
                )
                TextButton(onClick = { showRequestQr() }) {
                    Text("New QR")
                }
                TextButton(onClick = { reset() }) {
                    Text("Cancel")
                }
            }
            else -> {
                if (status == "approved") {
                    Text(
                        if (joining) "Approved — creating your passkey…" else "Approved",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.7f),
                    )
                    if (!joining) {
                        TextButton(onClick = { joinAttempt++ }) {
                            Text("Continue")
                        }
                    }
                } else {
                    Image(
                        painter = rememberQrCodePainter(current.url),
                        contentDescription = "Pairing request QR code",
                        modifier = Modifier
                            .padding(horizontal = 48.dp, vertical = 8.dp)
                            .background(Color.White, RoundedCornerShape(12.dp))
                            .padding(10.dp),
                    )
                    Text(
                        "Approve from the signed-in device: Profile → Approve device",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.55f),
                        textAlign = TextAlign.Center,
                    )
                    val remaining = ((expiresAt - now) / 1000).coerceAtLeast(0)
                    Text(
                        "Expires in ${remaining / 60}:${(remaining % 60).toString().padStart(2, '0')}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.55f),
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                TextButton(onClick = { reset() }) {
                    Text("Cancel")
                }
            }
        }
    }
}
