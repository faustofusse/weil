package ar.fausto.weil

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The Rust SQL parser overflows the ~1MB stack of coroutine worker threads
 * (SIGSEGV inside libsql_sqlite3_parser). Route every DB call through a
 * single thread with a main-thread-sized (16MB) stack — also required
 * because [AndroidDatabase] only ever exposes one native connection; a
 * second thread touching it concurrently isn't safe (see
 * [AndroidGraphHolder]'s "one AndroidDatabase per file" invariant), so real
 * parallelism between UI reads and the notification listener's background
 * `sync()` is not an option.
 *
 * What *is* safe: reordering the queue. Both dispatchers below share this
 * one thread; [dbDispatcher] (mutations, `sync()`) is low priority and
 * [dbReadDispatcher] (pure reads) is high priority, so a fast UI read
 * queued behind an already-*queued* background sync jumps ahead of it. A
 * sync that's already *running* still has to finish first — reordering a
 * FIFO queue can't preempt a native call in flight on the only thread
 * allowed to touch the connection.
 */
private class PriorityRunnable(private val priority: Int, private val seq: Long, private val delegate: Runnable) :
    Runnable, Comparable<PriorityRunnable> {
    override fun run() = delegate.run()
    // Higher priority first; ties broken by submission order (FIFO).
    override fun compareTo(other: PriorityRunnable): Int {
        val byPriority = other.priority - priority
        return if (byPriority != 0) byPriority else seq.compareTo(other.seq)
    }
}

private val dbThreadSeq = AtomicLong()

private val dbExecutor = ThreadPoolExecutor(
    1,
    1,
    0L,
    TimeUnit.MILLISECONDS,
    PriorityBlockingQueue(),
    { runnable -> Thread(null, runnable, "weil-db", 16L * 1024 * 1024).apply { isDaemon = true } },
)

private fun dbDispatcherWithPriority(priority: Int): CoroutineDispatcher = object : CoroutineDispatcher() {
    override fun dispatch(context: CoroutineContext, block: Runnable) {
        dbExecutor.execute(PriorityRunnable(priority, dbThreadSeq.incrementAndGet(), block))
    }
}

/** Mutations and `sync()` — the calls that may legitimately take a while. */
val dbDispatcher: CoroutineDispatcher = dbDispatcherWithPriority(priority = 0)

/** Pure reads (tree/page/balances/counts) — jumps ahead of a queued sync. */
val dbReadDispatcher: CoroutineDispatcher = dbDispatcherWithPriority(priority = 10)

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
                // The Google code scanner also needs a foreground activity;
                // scanning only ever starts from profile/login screens.
                qrScanner = { AndroidQrScanner(requireNotNull(currentActivity) { "no foreground activity" }) },
                // SAF picker: also activity-scoped, only opened from Home.
                documentPicker = {
                    AndroidDocumentPicker(requireNotNull(currentActivity) { "no foreground activity" })
                },
                // Wallet handoff is a plain intent from the app context, so
                // there's no foreground-activity dance here.
                wallet = { AndroidWalletLauncher(this) },
                dbContext = dbDispatcher,
                dbReadContext = dbReadDispatcher,
                dbFactory = { userId, url, token ->
                    // "turso.db" (not the old "local.db"): the new Turso sync
                    // engine derives its rewrite/metadata sidecars from the path,
                    // so it must not collide with the old libsql replica's files.
                    AndroidDatabase(this, "databases/$userId/turso.db", url, token)
                },
            )
        }
        AndroidNotificationAccess.start(this)
        IolSyncWorker.schedule(this)

        // Warm the DB connection off the main thread: native lib load,
        // engine create/connect and (skipped when already applied) schema
        // setup all happen here instead of blocking the first screen query.
        // Errors are swallowed — the first real query retries normally.
        if (AndroidGraphHolder.graph?.auth?.state?.value is AuthState.LoggedIn) {
            CoroutineScope(dbDispatcher + SupervisorJob()).launch {
                runCatching { AndroidGraphHolder.graph?.accounts?.tree() }
            }
        }
    }
}
