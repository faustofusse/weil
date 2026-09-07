package ar.fausto.weil

import kotlinx.coroutines.flow.StateFlow

/**
 * Notification-listener access state. Android only; null on platforms that
 * have no concept of notification access (the Home screen hides the section).
 */
interface NotificationAccess {
    val enabled: StateFlow<Boolean>
    fun openSettings()
}

expect val notificationAccess: NotificationAccess?
