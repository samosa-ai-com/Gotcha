package com.gotcha.audio

/**
 * A small noise-floor-adaptive voice-activity detector used by the hands-free
 * wake-word call flow.
 *
 * It is fed one RMS (dBFS) value per audio window. Speech is any window above
 * `noiseFloor + margin` (never below an absolute floor); the noise floor is a
 * slow exponential average of windows that are *below* the threshold, so the
 * detector adapts to quiet and loud rooms. Once speech has been detected, the
 * detector reports [Decision.STOP] after [silenceTimeoutMs] of trailing
 * silence; if the user never spoke, it reports [Decision.STOP_NO_SPEECH] after
 * [noSpeechTimeoutMs].
 */
internal class SilenceDetector(
    private val silenceTimeoutMs: Long,
    private val noSpeechTimeoutMs: Long = NO_SPEECH_TIMEOUT_DEFAULT_MS,
    private val speechMarginDb: Float = SPEECH_MARGIN_DB,
    private val absoluteFloorDb: Float = ABSOLUTE_FLOOR_DB
) {
    var speechDetected: Boolean = false
        private set

    private var noiseFloorDb = INITIAL_NOISE_FLOOR_DB
    private var startMs = -1L
    private var lastSpeechAtMs = -1L

    /** The RMS level (dBFS) a window must exceed to count as speech. */
    val thresholdDb: Float
        get() = maxOf(absoluteFloorDb, noiseFloorDb + speechMarginDb)

    fun update(windowRmsDb: Float, nowMs: Long): Decision {
        if (startMs < 0) startMs = nowMs
        if (windowRmsDb > thresholdDb) {
            speechDetected = true
            lastSpeechAtMs = nowMs
        } else {
            // Slide the floor toward whatever is quieter than speech.
            noiseFloorDb = 0.95f * noiseFloorDb + 0.05f * windowRmsDb
        }
        if (speechDetected && nowMs - lastSpeechAtMs >= silenceTimeoutMs) {
            return Decision.STOP
        }
        if (!speechDetected && nowMs - startMs >= noSpeechTimeoutMs) {
            return Decision.STOP_NO_SPEECH
        }
        return Decision.CONTINUE
    }

    enum class Decision { CONTINUE, STOP, STOP_NO_SPEECH }

    companion object {
        private const val NO_SPEECH_TIMEOUT_DEFAULT_MS = 15_000L
        private const val SPEECH_MARGIN_DB = 10f
        private const val ABSOLUTE_FLOOR_DB = -50f
        private const val INITIAL_NOISE_FLOOR_DB = -60f

        /** RMS of [count] 16-bit PCM samples in dBFS (clamped to [ABSOLUTE_FLOOR_DB]). */
        fun rmsDb(samples: ShortArray, count: Int): Float {
            if (count <= 0) return ABSOLUTE_FLOOR_DB
            var sum = 0L
            for (i in 0 until count) {
                val sample = samples[i].toInt()
                sum += sample.toLong() * sample
            }
            val rms = kotlin.math.sqrt(sum.toDouble() / count)
            if (rms <= 0.0) return ABSOLUTE_FLOOR_DB
            val db = 20.0 * kotlin.math.log10(rms / 32768.0)
            return maxOf(db, ABSOLUTE_FLOOR_DB.toDouble()).toFloat()
        }
    }
}
