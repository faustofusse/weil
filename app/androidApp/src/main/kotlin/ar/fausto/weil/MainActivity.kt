package ar.fausto.weil

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/**
 * libsql's native core recursively parses SQL with huge non-inlined Rust frames
 * and overflows the ~1MB stack of coroutine worker threads (SIGSEGV inside
 * libsql_sqlite3_parser). Route every libsql call through this dispatcher:
 * a single thread with a main-thread-sized (16MB) stack.
 */
private val dbDispatcher: CoroutineDispatcher =
    Executors.newSingleThreadExecutor { runnable ->
        Thread(null, runnable, "weil-db", 16L * 1024 * 1024).apply { isDaemon = true }
    }.asCoroutineDispatcher()

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            App2Screen()
        }
    }
}

/**
 * Owns the database's life: opening an embedded replica does a network
 * handshake + write delegation, so it must not run on the main thread.
 * Shows a loading state until the Database is ready, and an error + retry
 * button if it fails.
 */
@Composable
fun App2Screen() {
    val context = LocalContext.current.applicationContext

    var database by remember { mutableStateOf<Database?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableStateOf(0) }

    LaunchedEffect(attempt) {
        database = try {
            withContext(dbDispatcher) {
                AndroidDatabase(
                    context = context,
                    url = BuildConfig.LIBSQL_URL,
                    authToken = BuildConfig.LIBSQL_AUTH_TOKEN,
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
            db != null -> App2(db)
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
fun App2(database: Database) {
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("hoalaaa") }

    MaterialTheme {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Button(onClick = {
                scope.launch {
                    text = try {
                        withContext(dbDispatcher) {
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
}

private class FakeDatabase : Database {
    override fun sync() {}
    override fun execute(sql: String) {}
    override fun <T> query(sql: String, params: Map<String, Any>?, block: (Sequence<Row>) -> T): T =
        block(emptySequence())
}

@Preview
@Composable
fun AppAndroidPreview() {
    App2(database = FakeDatabase())
}