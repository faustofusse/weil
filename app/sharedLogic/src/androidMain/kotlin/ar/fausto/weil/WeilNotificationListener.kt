package ar.fausto.weil

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Captures every posted notification (no filtering, matching the old finance
 * app) and records it into the user's Turso DB through the shared [AppGraph].
 */
class WeilNotificationListener : NotificationListenerService() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

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
                graph.notifications.record(
                    packageName = packageName,
                    title = title,
                    text = text,
                    category = category,
                    postTime = postTime,
                    app = app,
                )
            } catch (e: Exception) {
                Log.w(TAG, "failed to record notification from $packageName: ${e.message}")
            }
        }
    }

    private companion object {
        const val TAG = "WeilNotificationListener"
    }
}
