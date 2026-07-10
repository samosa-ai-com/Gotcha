package com.gotcha.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Tier 3 — NotificationListenerService.
 *
 * Once the user grants Notification access, this service can read and dismiss the
 * notifications of every app. Like the accessibility service it publishes a static
 * [instance] so the stateless [com.gotcha.tools.NotificationTool] can query the
 * live, bound service; when the user has not granted access the instance is null.
 */
class GotchaNotificationListenerService : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
    }

    override fun onListenerDisconnected() {
        instance = null
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    // We don't need to react to posts/removals; tools pull on demand.
    override fun onNotificationPosted(sbn: StatusBarNotification?) {}
    override fun onNotificationRemoved(sbn: StatusBarNotification?) {}

    /** Snapshot of the currently-active notifications (safe wrapper around the platform call). */
    fun currentNotifications(): Array<StatusBarNotification> =
        try {
            activeNotifications ?: emptyArray()
        } catch (_: Exception) {
            emptyArray()
        }

    /** Dismiss a single notification by its platform key. */
    fun dismiss(key: String) = cancelNotification(key)

    /** Dismiss all dismissible notifications. */
    fun dismissAll() = cancelAllNotifications()

    companion object {
        /** The live service instance while the user has granted access; null otherwise. */
        @Volatile
        var instance: GotchaNotificationListenerService? = null
            private set
    }
}
