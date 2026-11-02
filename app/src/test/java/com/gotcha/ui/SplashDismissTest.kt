package com.gotcha.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The launch overlay's dismissal rule: the two-second video is the minimum,
 * the app shell gates the rest, and the timeout is the back stop.
 */
class SplashDismissTest {

    @Test
    fun `the video alone never dismisses before it ends`() {
        assertFalse(shouldDismissSplash(videoEnded = false, contentReady = true, timedOut = false))
        assertFalse(shouldDismissSplash(videoEnded = false, contentReady = false, timedOut = false))
    }

    @Test
    fun `after the video, dismissal waits for the app shell`() {
        assertFalse(shouldDismissSplash(videoEnded = true, contentReady = false, timedOut = false))
        assertTrue(shouldDismissSplash(videoEnded = true, contentReady = true, timedOut = false))
    }

    @Test
    fun `the timeout releases a stuck launch even if the video never ends`() {
        assertTrue(shouldDismissSplash(videoEnded = true, contentReady = false, timedOut = true))
        assertTrue(shouldDismissSplash(videoEnded = false, contentReady = false, timedOut = true))
        assertTrue(shouldDismissSplash(videoEnded = false, contentReady = true, timedOut = true))
    }
}
