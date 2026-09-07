package ar.fausto.weil

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun HomeScreen(
    onNavigateToAccounts: () -> Unit,
    onNavigateToNotifications: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Finance") },
                actions = {
                    IconButton(onClick = onNavigateToAccounts) {
                        Icon(Icons.Filled.AccountCircle, contentDescription = "View accounts")
                    }
                    IconButton(onClick = onNavigateToNotifications) {
                        Icon(Icons.Filled.Notifications, contentDescription = "View notifications")
                    }
                },
            )
        },
    ) { innerPadding ->
        val access = notificationAccess
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
        ) {
            if (access != null) {
                val granted by access.enabled.collectAsState()
                Text(
                    text = if (granted) {
                        "✓ Notification access: GRANTED"
                    } else {
                        "✗ Notification access: NOT GRANTED"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(16.dp))
                if (!granted) {
                    Button(onClick = { access.openSettings() }) {
                        Text("Grant Notification Access")
                    }
                }
            } else {
                Box(Modifier.fillMaxSize())
            }
        }
    }
}
