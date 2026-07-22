package com.gotcha.service

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.widget.Toast
import com.gotcha.data.SettingsRepository

/**
 * Tier 3 — NotificationListenerService.
 *
 * Reads incoming notifications, runs proactive entity detection (OTP, URLs, phone, email),
 * optionally auto-copies OTPs, and forwards discovered entities to the proactive engine.
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

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val context = applicationContext
        val settings = runCatching { SettingsRepository(context).load() }.getOrNull() ?: return
        if (!settings.proactiveEnabled || !settings.proactiveScanNotifications) return

        val pkg = sbn.packageName ?: ""
        if (settings.proactiveAppBlacklist.contains(pkg)) return

        val extras = sbn.notification?.extras ?: return
        val title = extras.getCharSequence("android.title")?.toString() ?: ""
        val text = extras.getCharSequence("android.text")?.toString() ?: ""
        val fullText = "$title $text".trim()
        if (fullText.isBlank()) return

        val entities = SmartActionDetector.detectAll(fullText, allowChat = false)
        if (entities.isEmpty()) return

        // Auto-copy OTP if enabled
        if (settings.proactiveAutoCopyOtp) {
            val otpEntity = entities.firstOrNull { it.type == EntityType.OTP }
            if (otpEntity != null) {
                val clipManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                clipManager?.setPrimaryClip(ClipData.newPlainText("OTP Code", otpEntity.normalizedValue))
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(
                        context,
                        "🔑 OTP ${otpEntity.normalizedValue} copied to clipboard",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

        // Forward to proactive engine
        AssistiveBallService.onProactiveEntitiesDiscovered(entities, pkg)
    }

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
