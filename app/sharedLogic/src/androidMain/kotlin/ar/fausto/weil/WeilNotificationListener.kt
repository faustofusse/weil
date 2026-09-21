package ar.fausto.weil

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Captures every posted notification (no filtering, matching the old finance
 * app) and records it into the user's Turso DB through the shared [AppGraph].
 *
 * Each capture also triggers a **silent** suggestion run: the suggest
 * pipeline (read → retrieval → Jev) goes over the notification in the
 * background and parks its candidate in the `suggestions` table
 * ([SuggestionsInboxRepository]). Nothing is shown, nothing is written to the
 * ledger — the rows wait for the inbox screen, where the user reviews them.
 * The run is best-effort: offline or signed out, it fails and is not retried
 * (the captured row stays, and the template inbox is the net under it).
 */
class WeilNotificationListener : NotificationListenerService() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * One suggestion run at a time. Bank pushes arrive in bursts (often with
     * the matching email a second later) and each run is two worker calls and
     * a vector search — in parallel they would only queue at the worker.
     */
    private val suggestMutex = Mutex()

    /**
     * Content of the alerts a suggestion already ran for, with when. Banks
     * update and repost the same notification (progress, group summaries),
     * and a repost is a new capture row with a new id, so the row id cannot
     * dedupe this — the text can. The window matters: two real purchases of
     * the same amount at the same store produce the same sentence, and those
     * arrive hours apart, not seconds. Process-local on purpose; a restart
     * just pays for one extra read, and `unique(kind, ref)` plus the
     * `event_key` collapse in `pending()` absorb what slips through.
     */
    private val recentlySuggested = object : LinkedHashMap<String, Long>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?) = size > 200
    }

    override fun onStartCommand(intent: android.content.Intent?, flags: Int, startId: Int): Int =
        START_STICKY

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        requestRebind(android.content.ComponentName(this, WeilNotificationListener::class.java))
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notification = sbn ?: return
        val packageName = notification.packageName
        val extras = notification.notification?.extras
        val title = extras?.getCharSequence("android.title")?.toString() ?: "No title"
        val text = extras?.getCharSequence("android.text")?.toString() ?: "No text"
        val category = notification.notification?.category
        val postTime = notification.postTime

        scope.launch {
            try {
                val graph = AndroidGraphHolder.graph ?: return@launch
                val app = lookupAppInfo(AndroidGraphHolder.appContext ?: return@launch, packageName)
                val id = graph.notifications.record(
                    packageName = packageName,
                    title = title,
                    text = text,
                    category = category,
                    postTime = postTime,
                    app = app,
                )
                suggestSilently(graph, id, packageName, title, text)
            } catch (e: Exception) {
                Log.w(TAG, "failed to record notification from $packageName: ${e.message}")
            }
        }
    }

    /**
     * Runs the suggestion pipeline for one fresh capture and parks the
     * result, saying nothing either way. Our own notifications are excluded —
     * the capture-everything rule stands for the record, but reading our own
     * UI back to ourselves would be a bill for nothing.
     */
    private suspend fun suggestSilently(
        graph: AppGraph,
        id: String,
        packageName: String,
        title: String,
        text: String,
    ) {
        if (packageName == applicationContext.packageName) return
        if (text.isBlank()) return
        val key = "$packageName|$title|$text"
        val now = epochMillis()
        synchronized(recentlySuggested) {
            val seen = recentlySuggested[key]
            if (seen != null && now - seen < REPOST_WINDOW_MS) return
            recentlySuggested[key] = now
        }
        suggestMutex.withLock {
            try {
                val trace = graph.suggestions.traceNotification(id)
                graph.suggestionInbox.record(id, trace)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "suggestion run failed: ${e.message}")
            }
        }
    }

    private companion object {
        const val TAG = "WeilNotificationListener"

        /** Two identical texts inside this window are a repost, not two purchases. */
        const val REPOST_WINDOW_MS = 10L * 60 * 1000
    }
}
