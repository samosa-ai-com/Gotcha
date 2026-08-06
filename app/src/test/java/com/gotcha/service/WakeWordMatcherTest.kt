package com.gotcha.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeWordMatcherTest {

    @Test
    fun `default sensitivity maps to the model card balanced threshold`() {
        val matcher = WakeWordMatcher(WakeWordMatcher.DEFAULT_SENSITIVITY)
        // 0.70 - 0.27 * 0.75 = 0.4975
        assertEquals(0.4975f, matcher.threshold(), 0.001f)
    }

    @Test
    fun `zero sensitivity gives the high-precision end of the model card range`() {
        val matcher = WakeWordMatcher(0f)
        assertEquals(0.70f, matcher.threshold(), 0.001f)
    }

    @Test
    fun `full sensitivity gives the high-sensitivity end of the model card range`() {
        val matcher = WakeWordMatcher(1f)
        assertEquals(0.43f, matcher.threshold(), 0.001f)
    }

    @Test
    fun `sensitivity is clamped before mapping to a threshold`() {
        val negative = WakeWordMatcher(sensitivity = -5f)
        assertEquals(0.70f, negative.threshold(), 0.001f)
        val huge = WakeWordMatcher(sensitivity = 5f)
        assertEquals(0.43f, huge.threshold(), 0.001f)
    }

    @Test
    fun `reachable threshold range matches the documented clamp bounds`() {
        // The slider spans sensitivity 0..1, so the reachable threshold range
        // is [0.43, 0.70] — the model card's 0.35 high-sensitivity endpoint is
        // deliberately below the slider floor.
        assertEquals(0.70f, WakeWordMatcher(0f).threshold(), 0.001f)
        assertEquals(0.43f, WakeWordMatcher(1f).threshold(), 0.001f)
    }

    @Test
    fun `single frame above threshold does not fire`() {
        val matcher = WakeWordMatcher(WakeWordMatcher.DEFAULT_SENSITIVITY)
        assertFalse(matcher.onScore(matcher.threshold() + 0.1f))
    }

    @Test
    fun `two consecutive frames above threshold fire the detection`() {
        val matcher = WakeWordMatcher(WakeWordMatcher.DEFAULT_SENSITIVITY)
        matcher.onScore(matcher.threshold() + 0.1f)
        assertTrue(matcher.onScore(matcher.threshold() + 0.1f))
    }

    @Test
    fun `streak resets when a low score breaks the run`() {
        val matcher = WakeWordMatcher(WakeWordMatcher.DEFAULT_SENSITIVITY)
        matcher.onScore(matcher.threshold() + 0.1f)
        matcher.onScore(matcher.threshold() - 0.1f)
        assertFalse(matcher.onScore(matcher.threshold() + 0.1f))
    }

    @Test
    fun `streak resets after a successful detection so the next word requires two frames again`() {
        val matcher = WakeWordMatcher(WakeWordMatcher.DEFAULT_SENSITIVITY)
        matcher.onScore(matcher.threshold() + 0.1f)
        matcher.onScore(matcher.threshold() + 0.1f) // fires; streak reset to 0
        assertFalse(matcher.onScore(matcher.threshold() + 0.1f)) // streak=1
        assertTrue(matcher.onScore(matcher.threshold() + 0.1f)) // streak=2 fires
    }

    @Test
    fun `reset clears the streak`() {
        val matcher = WakeWordMatcher(WakeWordMatcher.DEFAULT_SENSITIVITY)
        matcher.onScore(matcher.threshold() + 0.1f)
        matcher.reset()
        assertFalse(matcher.onScore(matcher.threshold() + 0.1f))
    }

    @Test
    fun `scores exactly at threshold still count`() {
        val matcher = WakeWordMatcher(WakeWordMatcher.DEFAULT_SENSITIVITY)
        matcher.onScore(matcher.threshold())
        assertTrue(matcher.onScore(matcher.threshold()))
    }
}
