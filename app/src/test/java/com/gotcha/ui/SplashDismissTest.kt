package com.gotcha.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The launch overlay's dismissal rule: the two-second video is the minimum,
 * the app shell gates the rest, and the timeout releases either wait on its
 * own. A decoder failure counts as finished ([videoFailed] feeds the same
 * flag), so the fallback logo holds under the same rule.
 */
class SplashDismissTest {

    @Test
    fun `nothing dismisses before playback ends`() {
        assertFalse(shouldDismissSplash(playbackFinished = false, contentReady = true, timedOut = false))
        assertFalse(shouldDismissSplash(playbackFinished = false, contentReady = false, timedOut = false))
    }

    @Test
    fun `after playback, dismissal waits for the app shell`() {
        assertFalse(shouldDismissSplash(playbackFinished = true, contentReady = false, timedOut = false))
        assertTrue(shouldDismissSplash(playbackFinished = true, contentReady = true, timedOut = false))
    }

    @Test
    fun `a failed playback dismisses like a finished one`() {
        val failed = true
        assertFalse(shouldDismissSplash(playbackFinished = failed, contentReady = false, timedOut = false))
        assertTrue(shouldDismissSplash(playbackFinished = failed, contentReady = true, timedOut = false))
    }

    @Test
    fun `the timeout releases a stuck launch even if playback never ends`() {
        assertTrue(shouldDismissSplash(playbackFinished = true, contentReady = false, timedOut = true))
        assertTrue(shouldDismissSplash(playbackFinished = false, contentReady = false, timedOut = true))
        assertTrue(shouldDismissSplash(playbackFinished = false, contentReady = true, timedOut = true))
    }
}
