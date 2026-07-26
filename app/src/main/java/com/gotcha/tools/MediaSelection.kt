package com.gotcha.tools

import android.media.session.PlaybackState

/**
 * What a media action maps to, once the requested verb has been normalised.
 *
 * Shuffle and repeat are deliberately absent: the framework's
 * `MediaController.TransportControls` has no such methods (only the
 * `androidx.media` compat class does), so there is no way to drive them here.
 */
enum class MediaAction {
    PLAY,
    PAUSE,
    TOGGLE,
    NEXT,
    PREVIOUS,
    STOP,
    SEEK,
    FAST_FORWARD,
    REWIND
}

/**
 * The subset of a media session the picker needs. Keeping it as a plain data
 * class lets the selection rules be unit-tested without a MediaController.
 */
data class SessionInfo(
    val packageName: String,
    val appLabel: String,
    /** One of the `PlaybackState.STATE_*` constants. */
    val state: Int
)

/**
 * Pure session-picking and action-parsing rules for [NotificationTool]'s media
 * tools. Extracted so the interesting logic — "which of three running players did
 * the user mean?" — is testable on the JVM.
 */
object MediaSelection {

    /** Maps the tool's free-form `action` argument onto a [MediaAction]. */
    fun parseAction(action: String): MediaAction? = when (action.lowercase().trim()) {
        "play", "resume" -> MediaAction.PLAY
        "pause" -> MediaAction.PAUSE
        "toggle", "playpause", "play_pause" -> MediaAction.TOGGLE
        "next", "skip", "forward" -> MediaAction.NEXT
        "previous", "prev", "back" -> MediaAction.PREVIOUS
        "stop" -> MediaAction.STOP
        "seek" -> MediaAction.SEEK
        "fast_forward", "ff" -> MediaAction.FAST_FORWARD
        "rewind" -> MediaAction.REWIND
        else -> null
    }

    /**
     * Picks the session to act on.
     *
     * With an [app] hint, only sessions whose package or label match are eligible
     * — returning null rather than silently acting on the wrong player. Without
     * one, a currently-playing session wins over a merely-open one; that is what
     * the user means by "pause the music" when a podcast app is also loaded but
     * idle.
     */
    fun pick(sessions: List<SessionInfo>, app: String?): SessionInfo? {
        val candidates = if (app.isNullOrBlank()) {
            sessions
        } else {
            val needle = app.trim()
            sessions.filter {
                it.packageName.contains(needle, ignoreCase = true) ||
                    it.appLabel.contains(needle, ignoreCase = true)
            }
        }
        return candidates.firstOrNull { it.state == PlaybackState.STATE_PLAYING }
            ?: candidates.firstOrNull { it.state == PlaybackState.STATE_BUFFERING }
            ?: candidates.firstOrNull()
    }

    /** Human-readable playback state for `get_now_playing`. */
    fun describeState(state: Int): String = when (state) {
        PlaybackState.STATE_PLAYING -> "playing"
        PlaybackState.STATE_PAUSED -> "paused"
        PlaybackState.STATE_STOPPED -> "stopped"
        PlaybackState.STATE_BUFFERING -> "buffering"
        PlaybackState.STATE_CONNECTING -> "connecting"
        PlaybackState.STATE_ERROR -> "error"
        PlaybackState.STATE_NONE -> "idle"
        else -> "unknown"
    }

    /** `3:07` / `1:02:33` — position and duration read better than raw milliseconds. */
    fun formatPosition(millis: Long): String {
        if (millis < 0) return "--:--"
        val totalSeconds = millis / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(java.util.Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(java.util.Locale.US, "%d:%02d", minutes, seconds)
        }
    }
}
