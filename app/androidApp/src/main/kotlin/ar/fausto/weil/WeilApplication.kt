package ar.fausto.weil

import android.app.Activity
import android.app.Application
import android.os.Bundle
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

/**
 * The Rust SQL parser overflows the ~1MB stack of coroutine worker threads
 * (SIGSEGV inside libsql_sqlite3_parser). Route every DB call through this
 * dispatcher: a single thread with a main-thread-sized (16MB) stack.
 */
val dbDispatcher: CoroutineDispatcher =
    Executors.newSingleThreadExecutor { runnable ->
        Thread(null, runnable, "weil-db", 16L * 1024 * 1024).apply { isDaemon = true }
    }.asCoroutineDispatcher()

class WeilApplication : Application() {

    private var currentActivity: Activity? = null

    val graph: AppGraph
        get() = requireNotNull(AndroidGraphHolder.graph) { "graph not initialized" }

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                currentActivity = activity
            }

            override fun onActivityStopped(activity: Activity) {
                if (currentActivity === activity) currentActivity = null
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })

        AndroidGraphHolder.start(this) {
            AppGraph(
                store = AndroidSecureStore(this),
                // CredentialManager needs a foreground activity; sign-in only
                // ever happens while one is front (service never calls it).
                passkeys = { AndroidPasskeys(requireNotNull(currentActivity) { "no foreground activity" }) },
                dbContext = dbDispatcher,
                dbFactory = { userId, url, token ->
                    // "turso.db" (not the old "local.db"): the new Turso sync
                    // engine derives its rewrite/metadata sidecars from the path,
                    // so it must not collide with the old libsql replica's files.
                    AndroidDatabase(this, "databases/$userId/turso.db", url, token)
                },
            )
        }
        AndroidNotificationAccess.start(this)
    }
}
