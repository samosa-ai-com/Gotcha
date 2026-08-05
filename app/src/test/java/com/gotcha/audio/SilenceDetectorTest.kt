package com.gotcha.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SilenceDetectorTest {

    private val start = 1_000_000L

    @Test
    fun `stops after the configured trailing silence once speech was heard`() {
        val detector = SilenceDetector(silenceTimeoutMs = 3_000L)
        detector.update(windowRmsDb = -20f, nowMs = start)
        assertTrue(detector.speechDetected)
        assertEquals(SilenceDetector.Decision.CONTINUE, detector.update(windowRmsDb = -65f, nowMs = start + 1_000))
        assertEquals(SilenceDetector.Decision.CONTINUE, detector.update(windowRmsDb = -65f, nowMs = start + 2_000))
        assertEquals(SilenceDetector.Decision.STOP, detector.update(windowRmsDb = -65f, nowMs = start + 3_000))
    }

    @Test
    fun `keeps listening while speech continues`() {
        val detector = SilenceDetector(silenceTimeoutMs = 3_000L)
        var now = start
        repeat(10) {
            assertEquals(SilenceDetector.Decision.CONTINUE, detector.update(windowRmsDb = -20f, nowMs = now))
            now += 200
        }
        assertTrue(detector.speechDetected)
    }

    @Test
    fun `reports no speech after the no-speech timeout`() {
        val detector = SilenceDetector(silenceTimeoutMs = 3_000L, noSpeechTimeoutMs = 15_000L)
        assertEquals(SilenceDetector.Decision.CONTINUE, detector.update(windowRmsDb = -65f, nowMs = start))
        assertFalse(detector.speechDetected)
        assertEquals(
            SilenceDetector.Decision.CONTINUE,
            detector.update(windowRmsDb = -65f, nowMs = start + 14_000)
        )
        assertEquals(
            SilenceDetector.Decision.STOP_NO_SPEECH,
            detector.update(windowRmsDb = -65f, nowMs = start + 15_000)
        )
    }

    @Test
    fun `silence does not stop before speech is heard`() {
        val detector = SilenceDetector(silenceTimeoutMs = 3_000L)
        var now = start
        repeat(30) {
            assertEquals(SilenceDetector.Decision.CONTINUE, detector.update(windowRmsDb = -65f, nowMs = now))
            now += 200
        }
        assertFalse("a silently recording user must not trigger STOP", detector.speechDetected)
    }

    @Test
    fun `rmsDb is near the floor for digital silence and higher for loud samples`() {
        val silent = ShortArray(100)
        val quiet = ShortArray(100) { 300 }
        val loud = ShortArray(100) { 20000 }
        val silentDb = SilenceDetector.rmsDb(silent, silent.size)
        val quietDb = SilenceDetector.rmsDb(quiet, quiet.size)
        val loudDb = SilenceDetector.rmsDb(loud, loud.size)
        assertTrue(silentDb <= -50f)
        assertTrue(quietDb > silentDb)
        assertTrue(loudDb > quietDb)
        // 20000/32768 ≈ -4.3 dBFS.
        assertTrue(loudDb in -10f..0f)
    }

    @Test
    fun `rmsDb ignores trailing zeros beyond count`() {
        val samples = ShortArray(200)
        for (i in 0 until 100) samples[i] = 20000
        val full = SilenceDetector.rmsDb(samples, 100)
        val truncated = SilenceDetector.rmsDb(samples, 50)
        assertTrue(full > -10f)
        assertTrue(truncated > -10f)
    }
}
