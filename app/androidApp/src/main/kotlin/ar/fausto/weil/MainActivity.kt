package ar.fausto.weil

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
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

        val graph = AppGraph(
            store = AndroidSecureStore(applicationContext),
            passkeys = AndroidPasskeys(this),
            dbContext = dbDispatcher,
            dbFactory = { userId, url, token ->
                AndroidDatabase(applicationContext, "databases/$userId/local.db", url, token)
            },
        )

        setContent {
            RootScreen(graph)
        }
    }
}
