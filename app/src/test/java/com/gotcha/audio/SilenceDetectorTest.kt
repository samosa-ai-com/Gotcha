package com.gotcha.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SilenceDetectorTest {

    private val start = 1_000_000L

    private fun primeFloor(detector: SilenceDetector, ambientDb: Float = -65f) {
        var now = start
        repeat(12) {
            assertEquals(
                SilenceDetector.Decision.CONTINUE,
                detector.update(windowRmsDb = ambientDb, nowMs = now)
            )
            now += 200
        }
    }

    @Test
    fun `stops after the configured trailing silence once speech was heard`() {
        val detector = SilenceDetector(silenceTimeoutMs = 3_000L)
        primeFloor(detector)
        detector.update(windowRmsDb = -20f, nowMs = start + 3_000)
        detector.update(windowRmsDb = -20f, nowMs = start + 3_200)
        assertFalse(detector.speechDetected) // acceptance needs 3 consecutive windows
        detector.update(windowRmsDb = -20f, nowMs = start + 3_400)
        assertTrue(detector.speechDetected)
        assertEquals(
            SilenceDetector.Decision.CONTINUE,
            detector.update(windowRmsDb = -65f, nowMs = start + 4_000)
        )
        assertEquals(
            SilenceDetector.Decision.CONTINUE,
            detector.update(windowRmsDb = -65f, nowMs = start + 5_000)
        )
        assertEquals(
            SilenceDetector.Decision.STOP,
            detector.update(windowRmsDb = -65f, nowMs = start + 6_400)
        )
    }

    @Test
    fun `keeps listening while speech continues`() {
        val detector = SilenceDetector(silenceTimeoutMs = 3_000L)
        primeFloor(detector)
        var now = start + 3_000
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
    fun `single loud transient is rejected - the streak resets before acceptance`() {
        val detector = SilenceDetector(silenceTimeoutMs = 3_000L)
        primeFloor(detector)
        detector.update(windowRmsDb = -20f, nowMs = start + 3_000)
        detector.update(windowRmsDb = -65f, nowMs = start + 3_200)
        assertFalse(detector.speechDetected)
        detector.update(windowRmsDb = -20f, nowMs = start + 3_400)
        detector.update(windowRmsDb = -20f, nowMs = start + 3_600)
        assertFalse("the transient must break the streak", detector.speechDetected)
    }

    @Test
    fun `loud room is adapted to - ambient is not treated as speech`() {
        val detector = SilenceDetector(silenceTimeoutMs = 3_000L)
        // Moderately loud room (~ -45 dBFS), well above the initial threshold
        // of -50. The floor must rise above the ambient so it is never speech.
        primeFloor(detector, ambientDb = -45f)
        repeat(20) { i ->
            assertEquals(
                "ambient must never be accepted as speech",
                SilenceDetector.Decision.CONTINUE,
                detector.update(windowRmsDb = -45f, nowMs = start + 3_000 + i * 200)
            )
        }
        assertFalse(detector.speechDetected)
    }

    @Test
    fun `slow-starting user in a loud room is not cut off`() {
        val detector = SilenceDetector(silenceTimeoutMs = 3_000L)
        primeFloor(detector, ambientDb = -45f)
        // The user pauses several seconds before speaking. The trailing-silence
        // clock must not have started, so no STOP fires during the pause.
        repeat(20) { i ->
            assertEquals(
                SilenceDetector.Decision.CONTINUE,
                detector.update(windowRmsDb = -45f, nowMs = start + 3_000 + i * 200)
            )
        }
        detector.update(windowRmsDb = -20f, nowMs = start + 7_000)
        detector.update(windowRmsDb = -20f, nowMs = start + 7_200)
        detector.update(windowRmsDb = -20f, nowMs = start + 7_400)
        assertTrue(detector.speechDetected)
        // Speech accepted at ~7_400; silence afterwards should not STOP before
        // the 3 s trailing window has elapsed.
        assertEquals(
            SilenceDetector.Decision.CONTINUE,
            detector.update(windowRmsDb = -45f, nowMs = start + 9_000)
        )
        assertEquals(
            SilenceDetector.Decision.STOP,
            detector.update(windowRmsDb = -45f, nowMs = start + 10_400)
        )
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
