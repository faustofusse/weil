package ar.fausto.weil

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object AndroidNotificationAccess : NotificationAccess {

    private val _enabled = MutableStateFlow(false)
    override val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private var observer: ContentObserver? = null

    fun start(context: Context) {
        if (observer != null) return
        val appContext = context.applicationContext
        _enabled.value = isEnabled(appContext)

        val contentObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                super.onChange(selfChange)
                _enabled.value = isEnabled(appContext)
            }
        }
        observer = contentObserver
        appContext.contentResolver.registerContentObserver(
            Settings.Secure.CONTENT_URI,
            true,
            contentObserver,
        )
    }

    private fun isEnabled(context: Context): Boolean {
        val listeners = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners",
        )
        return listeners?.contains(component(context).flattenToString()) ?: false
    }

    private fun component(context: Context) =
        ComponentName(context, WeilNotificationListener::class.java)

    override fun openSettings() {
        val context = AndroidGraphHolder.appContext ?: return
        context.startActivity(
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

actual val notificationAccess: NotificationAccess? = AndroidNotificationAccess
