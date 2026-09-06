package ar.fausto.weil

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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

@Composable
fun RootScreen(graph: AppGraph) {
    MaterialTheme {
        val state by graph.auth.state.collectAsState()
        val scope = rememberCoroutineScope()
        when (val s = state) {
            AuthState.Restoring -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
            AuthState.LoggedOut -> LoginScreen(
                onSignIn = { graph.auth.signIn() },
            )
            is AuthState.LoggedIn -> AccountsScreen(
                accounts = graph.accounts,
                onSignOut = { scope.launch { graph.auth.signOut() } },
            )
        }
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Finance", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(32.dp))
        Button(
            onClick = { launchAction(onSignIn) },
            enabled = !busy,
        ) {
            Text("Continue with passkey")
        }
        if (busy) {
            Spacer(Modifier.height(16.dp))
            CircularProgressIndicator()
        }
        error?.let {
            Spacer(Modifier.height(16.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
fun AccountsScreen(
    accounts: AccountsRepository,
    onSignOut: () -> Unit,
) {
    var items by remember { mutableStateOf<List<Account>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var newName by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<Account?>(null) }
    var editName by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    fun load() {
        scope.launch {
            busy = true
            error = null
            try {
                items = accounts.list()
            } catch (e: Throwable) {
                error = e.message ?: e.toString()
            } finally {
                busy = false
            }
        }
    }

    fun mutate(action: suspend () -> Unit) {
        scope.launch {
            busy = true
            error = null
            try {
                action()
                items = accounts.list()
            } catch (e: Throwable) {
                error = e.message ?: e.toString()
            } finally {
                busy = false
            }
        }
    }

    LaunchedEffect(Unit) { load() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Accounts", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onSignOut) { Text("Sign out") }
        }

        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp))
        }

        if (busy && items.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else if (items.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Text("No accounts yet — add one below")
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                items(items, key = { it.id }) { account ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            account.name,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .weight(1f)
                                .clickable {
                                    editing = account
                                    editName = account.name
                                },
                        )
                        IconButton(onClick = {
                            editing = account
                            editName = account.name
                        }) {
                            Text("Edit")
                        }
                        IconButton(onClick = { mutate { accounts.delete(account.id) } }) {
                            Text("Delete")
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = newName,
                onValueChange = { newName = it },
                label = { Text("Account name") },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
            Button(
                onClick = {
                    val name = newName.trim()
                    mutate { accounts.add(name) }
                    newName = ""
                },
                enabled = newName.isNotBlank() && !busy,
                modifier = Modifier.padding(start = 8.dp),
            ) {
                Text("Add")
            }
        }
    }

    editing?.let { account ->
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Rename account") },
            text = {
                OutlinedTextField(
                    value = editName,
                    onValueChange = { editName = it },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val id = account.id
                        val name = editName.trim()
                        editing = null
                        if (name.isNotEmpty()) mutate { accounts.rename(id, name) }
                    },
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { editing = null }) { Text("Cancel") }
            },
        )
    }
}
