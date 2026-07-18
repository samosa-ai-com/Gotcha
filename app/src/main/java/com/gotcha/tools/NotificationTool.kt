package com.gotcha.tools

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.provider.Settings
import android.view.KeyEvent
import com.gotcha.service.GotchaNotificationListenerService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tier 3 — reads/dismisses notifications from any app and controls media playback,
 * all through the [GotchaNotificationListenerService]. Media control uses
 * [MediaSessionManager], which itself requires the notification-listener grant.
 */
class NotificationTool(private val context: Context) {

    fun readNotifications(limit: Int): ToolResult {
        val service = requireService() ?: return notEnabled()
        val notifications = service.currentNotifications()
        if (notifications.isEmpty()) return ToolResult.ok("There are no active notifications.")
        val pm = context.packageManager
        val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        val out = notifications
            .sortedByDescending { it.postTime }
            .take(limit.coerceIn(1, 50))
            .joinToString("\n") { sbn ->
                val extras = sbn.notification.extras
                val title = extras.getCharSequence("android.title")?.toString()?.trim().orEmpty()
                val text = extras.getCharSequence("android.text")?.toString()?.trim().orEmpty()
                val app = try {
                    pm.getApplicationLabel(pm.getApplicationInfo(sbn.packageName, 0)).toString()
                } catch (_: Exception) {
                    sbn.packageName
                }
                val body = listOf(title, text).filter { it.isNotEmpty() }.joinToString(" — ")
                "- [${fmt.format(Date(sbn.postTime))}] $app: ${body.ifEmpty { "(no text)" }} {key=${sbn.key}}"
            }
        return ToolResult.ok("Active notifications (${notifications.size}):\n$out")
    }

    /** Dismiss a specific notification by key, or all of them when [key] is blank/null. */
    fun dismissNotifications(key: String?): ToolResult {
        val service = requireService() ?: return notEnabled()
        return if (key.isNullOrBlank()) {
            service.dismissAll()
            ToolResult.ok("Dismissed all dismissible notifications.")
        } else {
            service.dismiss(key)
            ToolResult.ok("Dismissed notification with key $key.")
        }
    }

    /** Control the active media session: play, pause, next, previous, stop. */
    fun mediaControl(action: String): ToolResult {
        if (!hasNotificationAccess()) return notEnabled()
        val keyCode = when (action.lowercase().trim()) {
            "play", "pause", "toggle", "playpause" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            "next", "skip" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous", "prev", "back" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            "stop" -> KeyEvent.KEYCODE_MEDIA_STOP
            else -> return ToolResult.error(
                "Unknown media action '$action'. Use play, pause, next, previous, or stop."
            )
        }
        return try {
            val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val component = ComponentName(context, GotchaNotificationListenerService::class.java)
            val controllers: List<MediaController> = msm.getActiveSessions(component)
            val controller = controllers.firstOrNull()
                ?: return ToolResult.error(
                    "No app is currently playing media. You may open a music or video app with open_app first, " +
                        "then try media_control again."
                )
            controller.dispatchMediaButtonEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            controller.dispatchMediaButtonEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
            ToolResult.ok("Sent media '$action' to ${controller.packageName}.")
        } catch (_: SecurityException) {
            notEnabled()
        } catch (e: Exception) {
            ToolResult.error("Could not control media: ${e.message}")
        }
    }

    private fun requireService(): GotchaNotificationListenerService? =
        if (hasNotificationAccess()) GotchaNotificationListenerService.instance else null

    private fun notEnabled() = ToolResult.permissionNeeded(
        ToolResult.NOTIFICATION_LISTENER_ACCESS,
        "This needs Notification access. I have opened the Notification-access settings — " +
            "please enable Gotcha there and ask again."
    )

    private fun hasNotificationAccess(): Boolean {
        val flat = Settings.Secure.getString(
            context.contentResolver, "enabled_notification_listeners"
        ) ?: return false
        val expected = ComponentName(context, GotchaNotificationListenerService::class.java)
        return flat.split(":").any {
            ComponentName.unflattenFromString(it) == expected
        }
    }
}
