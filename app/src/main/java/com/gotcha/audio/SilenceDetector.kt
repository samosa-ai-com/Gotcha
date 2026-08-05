package com.gotcha.audio

/**
 * A noise-floor-adaptive voice-activity detector used by the hands-free
 * wake-word call flow.
 *
 * It is fed one RMS (dBFS) value per audio window. The noise floor is a
 * low-percentile estimate over a rolling window of recent RMS values, so it
 * can both fall and rise: in a loud room the floor climbs toward the ambient
 * level instead of permanently classifying ambient as speech. The rolling
 * window only records windows heard before speech is accepted, so sustained
 * speech can never drag the floor up mid-utterance. Speech is any window
 * above `noiseFloor + margin` (never below an absolute floor). A window must
 * stay above the threshold for [ACCEPT_STREAK] consecutive windows before it
 * counts as speech, so a one-window transient (door slam, cough) is ignored.
 *
 * Once speech has been accepted, the detector reports [Decision.STOP] after
 * [silenceTimeoutMs] of trailing silence — the clock only starts after genuine
 * speech, so a user who pauses before beginning to speak is not cut off. If
 * the user never spoke, it reports [Decision.STOP_NO_SPEECH] after
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

    private val recentRms = FloatArray(FLOOR_WINDOW)
    private var recentCount = 0
    private var recentStart = 0
    private var aboveThresholdStreak = 0
    private var startMs = -1L
    private var lastSpeechAtMs = -1L

    /** The RMS level (dBFS) a window must exceed to count as speech. */
    val thresholdDb: Float
        get() = maxOf(absoluteFloorDb, noiseFloorEstimate + speechMarginDb)

    fun update(windowRmsDb: Float, nowMs: Long): Decision {
        if (startMs < 0) startMs = nowMs
        // Only pre-speech windows inform the floor, so the estimate keeps
        // reflecting ambient even while the user talks over it.
        if (!speechDetected) {
            recentRms[recentStart] = windowRmsDb
            recentStart = (recentStart + 1) % FLOOR_WINDOW
            if (recentCount < FLOOR_WINDOW) recentCount++
        }

        if (windowRmsDb > thresholdDb) {
            aboveThresholdStreak++
            // Only accept speech once the floor window is primed — otherwise a
            // loud first window would be accepted against the initial floor
            // before the ambient level has been learned.
            if (!speechDetected && recentCount >= FLOOR_WINDOW &&
                aboveThresholdStreak >= ACCEPT_STREAK
            ) {
                speechDetected = true
            }
            lastSpeechAtMs = nowMs
        } else {
            aboveThresholdStreak = 0
        }
        if (speechDetected && nowMs - lastSpeechAtMs >= silenceTimeoutMs) {
            return Decision.STOP
        }
        if (!speechDetected && nowMs - startMs >= noSpeechTimeoutMs) {
            return Decision.STOP_NO_SPEECH
        }
        return Decision.CONTINUE
    }

    /**
     * Low-percentile of the recent RMS windows, in dBFS. Using a percentile
     * rather than the running minimum makes the floor robust to a single quiet
     * outlier, and letting it rise tracks louder rooms without treating the
     * ambient level as speech.
     */
    private val noiseFloorEstimate: Float
        get() {
            if (recentCount < FLOOR_WINDOW) return INITIAL_NOISE_FLOOR_DB
            val sorted = recentRms.copyOf(FLOOR_WINDOW).apply { sort() }
            val idx = (sorted.size * FLOOR_QUANTILE).toInt()
            return maxOf(sorted[idx.coerceIn(0, sorted.lastIndex)], absoluteFloorDb)
        }

    enum class Decision { CONTINUE, STOP, STOP_NO_SPEECH }

    companion object {
        private const val NO_SPEECH_TIMEOUT_DEFAULT_MS = 15_000L
        private const val SPEECH_MARGIN_DB = 10f
        private const val ABSOLUTE_FLOOR_DB = -50f
        private const val INITIAL_NOISE_FLOOR_DB = -60f

        /** Consecutive above-threshold windows required before accepting speech. */
        private const val ACCEPT_STREAK = 3

        /** Number of recent RMS windows the floor percentile is computed over. */
        private const val FLOOR_WINDOW = 12

        /** Fraction of recent windows that are background noise (low percentile). */
        private const val FLOOR_QUANTILE = 0.25f

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
