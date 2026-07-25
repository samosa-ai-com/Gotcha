package com.gotcha.tools

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
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

    private companion object {
        const val MILLIS_PER_SECOND = 1000L
    }

    fun readNotifications(limit: Int, app: String? = null): ToolResult {
        val service = requireService() ?: return notEnabled()
        var notifications = service.currentNotifications().toList()
        if (notifications.isEmpty()) return ToolResult.ok("There are no active notifications.")
        val pm = context.packageManager

        if (!app.isNullOrBlank()) {
            val needle = app.trim()
            notifications = notifications.filter { sbn ->
                sbn.packageName.contains(needle, ignoreCase = true) ||
                    appLabel(pm, sbn.packageName).contains(needle, ignoreCase = true)
            }
            if (notifications.isEmpty()) return ToolResult.ok("No active notifications from '$app'.")
        }

        val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        val out = notifications
            .sortedByDescending { it.postTime }
            .take(limit.coerceIn(1, 50))
            .joinToString("\n") { sbn ->
                val extras = sbn.notification.extras
                val title = extras.getCharSequence("android.title")?.toString()?.trim().orEmpty()
                val text = extras.getCharSequence("android.text")?.toString()?.trim().orEmpty()
                val bigText = extras.getCharSequence("android.bigText")?.toString()?.trim().orEmpty()
                val textLines = extras.getCharSequenceArray("android.textLines")
                    ?.mapNotNull { it?.toString()?.trim() }
                    ?.filter { it.isNotEmpty() }
                    .orEmpty()
                val appName = appLabel(pm, sbn.packageName)
                val body = listOf(title, text).filter { it.isNotEmpty() }.joinToString(" — ")
                val extraDetail = listOfNotNull(
                    bigText.takeIf { it.isNotEmpty() && it != text },
                    textLines.takeIf { it.isNotEmpty() }?.joinToString(" / ")
                ).joinToString(" | ")
                val line = "- [${fmt.format(
                    Date(sbn.postTime)
                )}] $appName: ${body.ifEmpty { "(no text)" }} {key=${sbn.key}}"
                if (extraDetail.isNotEmpty()) "$line\n    $extraDetail" else line
            }
        return ToolResult.ok("Active notifications (${notifications.size}):\n$out")
    }

    private fun appLabel(pm: android.content.pm.PackageManager, packageName: String): String = try {
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    } catch (_: Exception) {
        packageName
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

    /**
     * Control a media session: play, pause, toggle, next, previous, stop, seek,
     * fast_forward, rewind.
     *
     * Uses [MediaController.TransportControls] rather than media key events,
     * because keys only offer a play/pause *toggle* and cannot seek at all. The
     * one exception is `toggle` itself, which stays on the key-event path since
     * that is exactly what the key means — and it is what the previous
     * implementation used for every action, so existing behaviour is preserved.
     */
    fun mediaControl(action: String, app: String? = null, positionSeconds: Int? = null): ToolResult {
        if (!hasNotificationAccess()) return notEnabled()
        val parsed = MediaSelection.parseAction(action)
            ?: return ToolResult.error(
                "Unknown media action '$action'. Use play, pause, toggle, next, previous, stop, " +
                    "seek, fast_forward, or rewind."
            )
        if (parsed == MediaAction.SEEK && positionSeconds == null) {
            return ToolResult.error("seek needs 'position_seconds'.")
        }
        return try {
            val controllers = activeControllers()
            val chosen = MediaSelection.pick(controllers.map { it.toSessionInfo() }, app)
                ?: return noSessionError(controllers, app)
            val controller = controllers.first { it.packageName == chosen.packageName }

            applyAction(controller, parsed, positionSeconds)
            val target = appLabel(context.packageManager, controller.packageName)
            ToolResult.ok("Sent media '$action' to $target.")
        } catch (_: SecurityException) {
            notEnabled()
        } catch (e: Exception) {
            ToolResult.error("Could not control media: ${e.message}")
        }
    }

    /** Reports what every active media session is playing. */
    fun getNowPlaying(): ToolResult {
        if (!hasNotificationAccess()) return notEnabled()
        return try {
            val controllers = activeControllers()
            if (controllers.isEmpty()) {
                return ToolResult.ok("Nothing is playing — no app holds an active media session.")
            }
            val rows = controllers.joinToString("\n") { controller ->
                val metadata = controller.metadata
                val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
                val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                    ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST).orEmpty()
                val album = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty()
                val duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: -1L
                val playback = controller.playbackState
                val state = MediaSelection.describeState(
                    playback?.state ?: PlaybackState.STATE_NONE
                )
                val position = playback?.position ?: -1L

                val what = listOf(title, artist, album)
                    .filter { it.isNotBlank() }
                    .joinToString(" — ")
                    .ifBlank { "(no metadata)" }
                val progress = if (duration > 0) {
                    " [${MediaSelection.formatPosition(position)} / " +
                        "${MediaSelection.formatPosition(duration)}]"
                } else {
                    ""
                }
                "- ${appLabel(context.packageManager, controller.packageName)} " +
                    "(${controller.packageName}): $state | $what$progress"
            }
            ToolResult.ok("Active media session(s):\n$rows")
        } catch (_: SecurityException) {
            notEnabled()
        } catch (e: Exception) {
            ToolResult.error("Could not read media sessions: ${e.message}")
        }
    }

    private fun activeControllers(): List<MediaController> {
        val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        val component = ComponentName(context, GotchaNotificationListenerService::class.java)
        return msm.getActiveSessions(component)
    }

    private fun MediaController.toSessionInfo() = SessionInfo(
        packageName = packageName,
        appLabel = appLabel(context.packageManager, packageName),
        state = playbackState?.state ?: PlaybackState.STATE_NONE
    )

    private fun noSessionError(controllers: List<MediaController>, app: String?): ToolResult =
        if (app.isNullOrBlank()) {
            ToolResult.error(
                "No app is currently playing media. You may open a music or video app with " +
                    "open_app first, then try media_control again."
            )
        } else {
            val running = controllers.joinToString(", ") {
                appLabel(context.packageManager, it.packageName)
            }.ifBlank { "none" }
            ToolResult.error(
                "No media session matched '$app'. Apps with a media session right now: $running."
            )
        }

    /** Applies [action], falling back to a media key event if transport controls refuse it. */
    private fun applyAction(
        controller: MediaController,
        action: MediaAction,
        positionSeconds: Int?
    ) {
        val transport = controller.transportControls
        when (action) {
            MediaAction.PLAY -> transport.play()
            MediaAction.PAUSE -> transport.pause()
            MediaAction.TOGGLE -> dispatchKey(controller, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            MediaAction.NEXT -> transport.skipToNext()
            MediaAction.PREVIOUS -> transport.skipToPrevious()
            MediaAction.STOP -> transport.stop()
            MediaAction.SEEK -> transport.seekTo((positionSeconds ?: 0).toLong() * MILLIS_PER_SECOND)
            MediaAction.FAST_FORWARD -> transport.fastForward()
            MediaAction.REWIND -> transport.rewind()
        }
    }

    private fun dispatchKey(controller: MediaController, keyCode: Int) {
        controller.dispatchMediaButtonEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        controller.dispatchMediaButtonEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
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
