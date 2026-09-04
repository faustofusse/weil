package ar.fausto.weil

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.ComposeUIViewController
import kotlinx.coroutines.launch

fun MainViewController() = ComposeUIViewController { DatabaseScreen() }

/**
 * Owns the database's life: opening an embedded replica does a network
 * handshake + write delegation, so it must not run on the main thread.
 * Shows a loading state until the Database is ready, and an error + retry
 * button if it fails.
 */
@Composable
fun DatabaseScreen() {
    var database by remember { mutableStateOf<Database?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableStateOf(0) }

    LaunchedEffect(attempt) {
        database = try {
            withDbContext {
                IOSDatabase(
                    path = defaultDatabasePath(),
                    url = LibsqlConfig.URL,
                    authToken = LibsqlConfig.AUTH_TOKEN,
                )
            }
        } catch (t: Throwable) {
            error = t.message ?: t.toString()
            null
        }
    }

    MaterialTheme {
        val db = database
        when {
            error != null -> Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Failed to open database: $error")
                Button(onClick = {
                    error = null
                    attempt++
                }) { Text("Retry") }
            }
            db != null -> DatabaseDemo(db)
            else -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        }
    }
}

@Composable
private fun DatabaseDemo(database: Database) {
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("tap to query") }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Button(onClick = {
            scope.launch {
                text = try {
                    withDbContext {
                        // Embedded replica: pull latest frames from the server, then read locally.
                        database.sync()
                        database.query("select * from cuentas;", emptyMap()).toList().toString()
                    }
                } catch (t: Throwable) {
                    "error: ${t.message}"
                }
            }
        }) {
            Text(text)
        }
    }
}
