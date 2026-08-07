package com.gotcha.service

import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Cheap energy gate in front of the wake-word embedding stage (issue #37).
 *
 * The listener runs 12.5 frames a second forever, and a measured ~85% of each
 * frame's cost is the embedding backbone. Almost every real-world frame is
 * silence, so the fix is to only pay for that backbone when there is something
 * to hear. This class answers exactly one question per 80 ms frame — "is there
 * energy above the room's noise floor?" — for a few microseconds of RMS.
 *
 * It deliberately does **not** reuse [com.gotcha.audio.SilenceDetector]:
 *
 * - That is a conversational state machine (accept-streak, "user stopped
 *   talking", "user never spoke") built for the STT turn. Its `Decision` values
 *   have no meaning per frame.
 * - Its `rmsDb` clamps its return at −50 dBFS, so a gate built on it has no
 *   resolution below −50 and cannot be tuned for a quiet room. [rmsDb] here is
 *   unclamped.
 *
 * Behaviour:
 *
 * - The noise floor is a low percentile over the last [FLOOR_WINDOW] frames, so
 *   a loud room raises the bar instead of holding the gate permanently open.
 *   Unlike `SilenceDetector` the floor keeps updating while the gate is open:
 *   re-arming is exact (see [OnnxWakeWordPipeline]), so a gate that closes
 *   mid-phrase costs a little CPU but never costs a detection, and that makes
 *   "never let the floor go stale" the safer trade.
 * - A **single** frame above the threshold opens the gate — no accept-streak.
 *   A spurious open costs milliseconds; a missed onset costs a detection.
 * - Once open it stays open for [hangoverFrames] after energy drops. This is
 *   what keeps an intermittently noisy room from flapping the gate: each
 *   re-arm replays the classifier window, and that replay only pays for itself
 *   if the quiet gap was longer than ~1.3 s.
 * - It starts open and stays open until the floor window is primed, so the
 *   first seconds after start can never be gated against an unlearned floor.
 */
internal class WakeWordGate(
    private val marginDb: Float = SPEECH_MARGIN_DB,
    private val minThresholdDb: Float = MIN_THRESHOLD_DB,
    private val hangoverFrames: Int = HANGOVER_FRAMES,
    private val alwaysOpen: Boolean = false
) {
    private val recentRms = FloatArray(FLOOR_WINDOW)

    /** Reused scratch for the percentile sort, so the floor costs no allocation. */
    private val floorScratch = FloatArray(FLOOR_WINDOW)
    private var recentCount = 0
    private var recentStart = 0
    private var framesSinceEnergy = 0
    private var framesSinceFloorRefresh = 0

    /**
     * The level a frame must exceed to open the gate, in dBFS.
     *
     * Cached rather than recomputed per frame. The floor is a percentile over
     * four seconds of history — it moves slowly by construction, so refreshing
     * it every [FLOOR_REFRESH_FRAMES] tracks it just as well while keeping the
     * sort off a path that runs 12.5 times a second for as long as the phone is
     * on. Recomputing it per frame made the gate the only remaining per-frame
     * allocation in the listener.
     */
    var thresholdDb: Float = maxOf(minThresholdDb, INITIAL_NOISE_FLOOR_DB + marginDb)
        private set

    /** True while the floor window is still filling; the gate is forced open. */
    val priming: Boolean
        get() = recentCount < FLOOR_WINDOW

    /** Returns true when [frame] should be run through the embedding stage. */
    fun onFrame(frame: ShortArray, count: Int): Boolean = onLevel(rmsDb(frame, count))

    /** [onFrame] split out from the PCM, so tests can drive levels directly. */
    fun onLevel(frameRmsDb: Float): Boolean {
        if (alwaysOpen) return true
        val open = priming || frameRmsDb > thresholdDb

        recentRms[recentStart] = frameRmsDb
        recentStart = (recentStart + 1) % FLOOR_WINDOW
        val wasPriming = recentCount < FLOOR_WINDOW
        if (wasPriming) recentCount++

        // Refresh on the frame that completes priming — the first real floor is
        // worth having immediately — and periodically after that.
        framesSinceFloorRefresh++
        if ((wasPriming && recentCount == FLOOR_WINDOW) ||
            framesSinceFloorRefresh >= FLOOR_REFRESH_FRAMES
        ) {
            refreshThreshold()
        }

        if (open) {
            framesSinceEnergy = 0
            return true
        }
        // Saturate rather than overflow: this counter runs for as long as the
        // room is quiet, which on a 24/7 listener is a very long time.
        if (framesSinceEnergy <= hangoverFrames) framesSinceEnergy++
        return framesSinceEnergy <= hangoverFrames
    }

    fun reset() {
        recentCount = 0
        recentStart = 0
        framesSinceEnergy = 0
        framesSinceFloorRefresh = 0
        recentRms.fill(0f)
        thresholdDb = maxOf(minThresholdDb, INITIAL_NOISE_FLOOR_DB + marginDb)
    }

    private fun refreshThreshold() {
        framesSinceFloorRefresh = 0
        thresholdDb = maxOf(minThresholdDb, noiseFloorEstimate() + marginDb)
    }

    private fun noiseFloorEstimate(): Float {
        if (recentCount < FLOOR_WINDOW) return INITIAL_NOISE_FLOOR_DB
        recentRms.copyInto(floorScratch)
        floorScratch.sort()
        val index = (floorScratch.size * FLOOR_QUANTILE).toInt()
            .coerceIn(0, floorScratch.lastIndex)
        return floorScratch[index]
    }

    companion object {
        /**
         * A gate that never closes — i.e. the pre-#37 behaviour, where every
         * frame paid for the embedding backbone. The parity tests run one
         * pipeline behind this and one behind a real gate, and require the two
         * to produce identical classifier scores.
         */
        fun alwaysOpen(): WakeWordGate = WakeWordGate(alwaysOpen = true)

        /** How far above the noise floor a frame has to be to count as sound. */
        private const val SPEECH_MARGIN_DB = 6f

        /**
         * Absolute lower bound on the threshold. Well below
         * `SilenceDetector.ABSOLUTE_FLOOR_DB` (−50) on purpose: a quiet bedroom
         * at night sits below −50 dBFS, and a gate that cannot be set quieter
         * than the room never closes.
         */
        private const val MIN_THRESHOLD_DB = -60f

        private const val INITIAL_NOISE_FLOOR_DB = -70f

        /**
         * ~3 s. Long enough that a pause inside a sentence does not re-arm the
         * pipeline, which matters because each re-arm costs a replay of the
         * 16-frame classifier window.
         */
        private const val HANGOVER_FRAMES = 38

        /** ~4 s of frames. Long enough that continuous speech cannot lift the floor to its own level. */
        private const val FLOOR_WINDOW = 50

        private const val FLOOR_QUANTILE = 0.2f

        /** ~1 s. The floor spans 4 s of history, so this loses nothing. */
        private const val FLOOR_REFRESH_FRAMES = 12

        /** Level reported for digital silence, where the log is undefined. */
        const val SILENT_DB = -120f

        /**
         * RMS of [count] 16-bit PCM samples in dBFS. Unlike
         * `SilenceDetector.rmsDb` this is **not** clamped at −50 dBFS.
         */
        fun rmsDb(samples: ShortArray, count: Int): Float {
            if (count <= 0) return SILENT_DB
            var sum = 0L
            for (i in 0 until count) {
                val sample = samples[i].toInt()
                sum += sample.toLong() * sample
            }
            val rms = sqrt(sum.toDouble() / count)
            if (rms <= 0.0) return SILENT_DB
            return maxOf(20.0 * log10(rms / 32768.0), SILENT_DB.toDouble()).toFloat()
        }
    }
}
