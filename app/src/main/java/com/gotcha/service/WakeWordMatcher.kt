package com.gotcha.service

/**
 * Converts a stream of Vosk partial/final transcripts into one wake-word event.
 *
 * Vosk has no Porcupine-style sensitivity knob, so sensitivity controls how
 * many consecutive recognition updates must contain the phrase. The grammar is
 * already constrained to the wake word and `[unk]`; this streak check is the
 * second layer against a single noisy partial firing a call.
 */
internal class WakeWordMatcher(
    private val phrase: String,
    sensitivity: Float
) {
    private val requiredConsecutiveMatches = when {
        sensitivity >= HIGH_SENSITIVITY -> 1
        sensitivity >= MEDIUM_SENSITIVITY -> 2
        else -> 3
    }
    private var consecutiveMatches = 0

    fun onPartial(text: String): Boolean = accept(text)

    fun onFinal(text: String): Boolean = accept(text)

    fun reset() {
        consecutiveMatches = 0
    }

    private fun accept(text: String): Boolean {
        if (!containsPhrase(text, phrase)) {
            consecutiveMatches = 0
            return false
        }
        consecutiveMatches++
        if (consecutiveMatches < requiredConsecutiveMatches) return false
        reset()
        return true
    }

    companion object {
        private const val MEDIUM_SENSITIVITY = 0.40f
        private const val HIGH_SENSITIVITY = 0.75f
        private val NON_WORD = Regex("[^a-z0-9]+")

        /** Exact-token match, so "gotcha" does not fire inside another word. */
        fun containsPhrase(text: String, phrase: String): Boolean {
            val wanted = phrase.lowercase().trim()
            if (wanted.isEmpty()) return false
            return text.lowercase()
                .split(NON_WORD)
                .any { it == wanted }
        }
    }
}
