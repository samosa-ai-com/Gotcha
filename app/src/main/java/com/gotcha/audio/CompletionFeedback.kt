package com.gotcha.audio

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator

/**
 * Non-verbal "a reply just arrived" signals.
 *
 * A reply can land while the user is looking at another app — or, on a voice
 * call, not looking at anything — so the buzz pattern is often the only thing
 * that says the turn ended and whether it ended well. The two patterns are
 * therefore deliberately unalike: a reply is a short rising double buzz, an
 * error is a longer, even triple buzz.
 */
object CompletionFeedback {

    /** Rising two-pulse buzz: soft, then firm. Reads as "done", not "wrong". */
    private val REPLY_TIMINGS = longArrayOf(0, 40, 70, 110)

    /**
     * Paired with [REPLY_TIMINGS]. Devices without amplitude control play
     * every non-zero entry at their default strength, so the pattern still
     * differs from the error buzz by rhythm alone.
     */
    private val REPLY_AMPLITUDES = intArrayOf(0, 110, 0, 255)

    /** Longer, even triple buzz — an error should feel insistent, not tidy. */
    private val ERROR_TIMINGS = longArrayOf(0, 100, 80, 100, 80, 100)

    private const val CHIME_VOLUME = 70
    private const val CHIME_RELEASE_MS = 1_000L

    /**
     * Signal that a reply arrived. Both channels are opt-in per [vibrate] and
     * [chime]; with both off this is a no-op.
     */
    fun replyArrived(context: Context, vibrate: Boolean, chime: Boolean) {
        if (vibrate) {
            vibrator(context)?.vibrate(
                VibrationEffect.createWaveform(REPLY_TIMINGS, REPLY_AMPLITUDES, -1)
            )
        }
        if (chime) playChime()
    }

    /** Signal that the turn ended badly. Always vibrates — errors are not opt-out. */
    fun error(context: Context) {
        vibrator(context)?.vibrate(VibrationEffect.createWaveform(ERROR_TIMINGS, -1))
    }

    private fun vibrator(context: Context): Vibrator? =
        (context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)?.takeIf { it.hasVibrator() }

    /**
     * Two-note ascending beep on the notification stream, so it follows the
     * user's notification volume and stays silent in Do Not Disturb. The
     * generator holds a native audio resource, hence the delayed release once
     * the tone has played out.
     */
    private fun playChime() {
        val tone = runCatching {
            ToneGenerator(AudioManager.STREAM_NOTIFICATION, CHIME_VOLUME)
        }.getOrNull() ?: return
        tone.startTone(ToneGenerator.TONE_PROP_BEEP2)
        Handler(Looper.getMainLooper()).postDelayed({ tone.release() }, CHIME_RELEASE_MS)
    }
}
