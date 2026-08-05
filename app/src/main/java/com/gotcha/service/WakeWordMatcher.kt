package com.gotcha.service

/**
 * Converts the OpenWakeWord classifier score stream into one wake-word event.
 *
 * The "Hey Gotcha" model card (docs/MODEL_CARD.md) recommends a threshold of
 * 0.50 for balanced behavior, 0.65 for high precision and 0.35 for high
 * sensitivity. The sensitivity slider is mapped onto that range:
 *
 *   threshold = 0.70 - 0.27 * sensitivity
 *
 * so the default sensitivity of 0.75 lands on 0.50. Because the slider only
 * spans sensitivity 0..1, the reachable threshold range is [0.43, 0.70] — the
 * model card's 0.35 high-sensitivity endpoint sits below the slider's floor
 * and is intentionally unreachable. A single 80 ms frame above the threshold
 * is not enough to trust — [PATIENCE] consecutive qualifying frames are
 * required, which rejects the occasional one-frame spike without adding
 * meaningful latency.
 */
internal class WakeWordMatcher(
    sensitivity: Float
) {
    private val threshold: Float = (BASE_THRESHOLD - SENSITIVITY_RANGE * sensitivity.coerceIn(0f, 1f))
        .coerceIn(MIN_THRESHOLD, MAX_THRESHOLD)
    private var consecutiveMatches = 0

    fun threshold(): Float = threshold

    fun onScore(score: Float): Boolean {
        if (score < threshold) {
            consecutiveMatches = 0
            return false
        }
        consecutiveMatches++
        if (consecutiveMatches < PATIENCE) return false
        consecutiveMatches = 0
        return true
    }

    fun reset() {
        consecutiveMatches = 0
    }

    companion object {
        /** Balanced threshold from the model card at the default sensitivity. */
        const val DEFAULT_SENSITIVITY = 0.75f
        private const val BASE_THRESHOLD = 0.70f
        private const val SENSITIVITY_RANGE = 0.27f

        // Reachable range for sensitivity in [0, 1]: 0.70 - 0.27 = 0.43 at the
        // most sensitive end. Bounds are defensive; the formula already stays
        // inside them after the sensitivity clamp.
        private const val MIN_THRESHOLD = 0.43f
        private const val MAX_THRESHOLD = 0.70f

        // 2 frames × 80 ms = 160 ms: enough to reject a one-frame audio spike,
        // small enough to feel snappy. The Python reference also uses short
        // patience when callers pass it explicitly.
        private const val PATIENCE = 2
    }
}
