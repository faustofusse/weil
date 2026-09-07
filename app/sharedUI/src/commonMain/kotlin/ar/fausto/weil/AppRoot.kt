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
import androidx.compose.runtime.Composable
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
                                )
                            }
                            entry<HomeRoute> {
                                HomeScreen(
                                    onNavigateToAccounts = { navigate(AccountsRoute) },
                                    onNavigateToNotifications = { navigate(NotificationsRoute) },
                                )
                            }
                            entry<NotificationsRoute> {
                                NotificationsScreen(
                                    notifications = graph.notifications,
                                    onNavigateBack = { pop() },
                                )
                            }
                            entry<AccountsRoute> {
                                AccountsScreen(
                                    accounts = graph.accounts,
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
        }
    }
}
