package ar.fausto.weil

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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
fun HomeScreen(
    accounts: AccountsRepository,
    onNavigateToProfile: () -> Unit,
    onNavigateToNotifications: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Finance") },
                actions = {
                    IconButton(onClick = onNavigateToNotifications) {
                        Icon(Icons.Filled.Notifications, contentDescription = "View notifications")
                    }
                    IconButton(onClick = onNavigateToProfile) {
                        Icon(Icons.Filled.AccountCircle, contentDescription = "View profile")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
        ) {
            AccountsSection(accounts = accounts)
        }
    }
}

/** Compact accounts CRUD block living on the home screen. */
@Composable
private fun AccountsSection(
    accounts: AccountsRepository,
) {
    var items by remember { mutableStateOf<List<Account>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var newName by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<Account?>(null) }
    var editName by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

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

    LaunchedEffect(Unit) {
        busy = true
        try {
            items = accounts.list()
        } catch (e: Throwable) {
            error = e.message ?: e.toString()
        } finally {
            busy = false
        }
    }

    Text(
        "Accounts",
        style = MaterialTheme.typography.titleMedium,
    )
    error?.let {
        Text(
            it,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(vertical = 4.dp),
        )
    }

    if (items.isEmpty() && !busy && error == null) {
        Text(
            "No accounts yet — add one below",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp),
        )
    } else {
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            items(items, key = { it.id }) { account ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        account.name,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .padding(vertical = 6.dp),
                    )
                    TextButton(onClick = {
                        editing = account
                        editName = account.name
                    }) {
                        Text("Edit")
                    }
                    TextButton(onClick = { mutate { accounts.delete(account.id) } }) {
                        Text("Delete")
                    }
                }
            }
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
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
