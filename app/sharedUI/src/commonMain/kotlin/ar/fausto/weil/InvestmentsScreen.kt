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
 * The investments tab (see `plans/inversiones-brokers.md`, section UI).
 *
 * For now: the three ways a broker gets in. InvertirOnline works — connect
 * with username and password (kept on this device only), then «Sincronizar»
 * fetches, plans and opens the review ([BrokerImportScreen]). IBKR's report
 * and custody statements are still «Pronto». Positions, value and the rest
 * of the tab come in phase 5.
 */
@Composable
fun InvestmentsScreen(
    iol: IolRepository,
    brokers: BrokersRepository,
    onReviewImport: (BrokerImportRoute) -> Unit,
    bottomBar: @Composable () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val soon = stringResource(Res.string.investments_soon_message)
    val wrongCredentials = stringResource(Res.string.iol_wrong_credentials)
    val upToDate = stringResource(Res.string.iol_up_to_date)
    // The secure store isn't observable; re-read after every change made here.
    var iolUser by remember { mutableStateOf(iol.username.takeIf { iol.hasCredentials }) }
    var connecting by remember { mutableStateOf(false) }
    var syncing by remember { mutableStateOf(false) }
    var confirmDisconnect by remember { mutableStateOf(false) }

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

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { AppTopBar(title = stringResource(Res.string.investments_title)) },
        bottomBar = bottomBar,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            // Same bottom air as Inicio: the create button straddles the
            // bar's top edge and would otherwise cover the last row.
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 56.dp),
        ) {
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
            item(key = "source-iol") {
                val user = iolUser
                AppListRow(
                    icon = Icons.Filled.TrendingUp,
                    paint = accountPaint(null),
                    title = stringResource(Res.string.investments_source_iol),
                    subtitle = user ?: stringResource(Res.string.investments_source_iol_hint),
                    onClick = { if (user == null) connecting = true else sync() },
                    onLongClick = if (user != null) ({ confirmDisconnect = true }) else null,
                ) {
                    if (user != null) {
                        if (syncing) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            TextButton(onClick = { sync() }) { Text(stringResource(Res.string.iol_sync)) }
                        }
                    }
                }
            }
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
