package com.gotcha.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gate is the one piece of the battery work that can be tested off-device:
 * it is pure arithmetic over frame levels, with no ONNX and no Android.
 */
class WakeWordGateTest {

    /** Enough frames to prime the floor window; the gate is forced open until then. */
    private fun WakeWordGate.prime(levelDb: Float = QUIET_ROOM_DB) {
        repeat(PRIMING_FRAMES) { onLevel(levelDb) }
    }

    @Test
    fun beforeTheFloorIsLearned_theGateStaysOpen() {
        val gate = WakeWordGate()
        // Digital silence, the most gate-able input there is — but with no
        // learned floor, closing would be a guess, and a wrong guess drops the
        // first seconds of listening.
        repeat(PRIMING_FRAMES) { assertTrue(gate.onLevel(WakeWordGate.SILENT_DB)) }
        assertFalse(gate.priming)
    }

    @Test
    fun quietRoom_closesTheGateOnceTheHangoverExpires() {
        val gate = WakeWordGate()
        gate.prime()

        // The hangover has to run out first — it is armed by the priming phase.
        repeat(HANGOVER_FRAMES) { assertTrue(gate.onLevel(QUIET_ROOM_DB)) }
        assertFalse(gate.onLevel(QUIET_ROOM_DB))
        assertFalse(gate.onLevel(QUIET_ROOM_DB))
    }

    @Test
    fun quietRoomFloor_sitsWellBelowSilenceDetectorsClamp() {
        val gate = WakeWordGate()
        gate.prime(levelDb = -72f)

        // SilenceDetector.rmsDb bottoms out at -50 dBFS, so a gate built on it
        // could never close in a room this quiet. This one can.
        assertTrue(
            "threshold ${gate.thresholdDb} should be below -50 dBFS in a quiet room",
            gate.thresholdDb < -50f
        )
        repeat(HANGOVER_FRAMES + 1) { gate.onLevel(-72f) }
        assertFalse(gate.onLevel(-72f))
        assertTrue(gate.onLevel(-40f))
    }

    @Test
    fun aSingleLoudFrame_opensTheGateImmediately() {
        val gate = WakeWordGate()
        gate.prime()
        repeat(HANGOVER_FRAMES + 1) { gate.onLevel(QUIET_ROOM_DB) }
        assertFalse(gate.onLevel(QUIET_ROOM_DB))

        // No accept-streak, deliberately: a spurious open costs milliseconds of
        // CPU, a missed onset costs the user their wake word.
        assertTrue(gate.onLevel(SPEECH_DB))
    }

    @Test
    fun afterSpeechStops_theGateHoldsOpenForTheHangover() {
        val gate = WakeWordGate()
        gate.prime()
        assertTrue(gate.onLevel(SPEECH_DB))

        // A pause inside a sentence must not re-arm the pipeline: each re-arm
        // replays the classifier window.
        repeat(HANGOVER_FRAMES) { index ->
            assertTrue("closed $index frames into the hangover", gate.onLevel(QUIET_ROOM_DB))
        }
        assertFalse(gate.onLevel(QUIET_ROOM_DB))
    }

    @Test
    fun aLoudRoom_raisesTheThresholdWithIt() {
        val quiet = WakeWordGate().apply { prime(levelDb = -70f) }
        val loud = WakeWordGate().apply { prime(levelDb = -30f) }

        assertTrue(
            "a loud room should demand a louder frame (${quiet.thresholdDb} vs ${loud.thresholdDb})",
            loud.thresholdDb > quiet.thresholdDb
        )
        // Ambient chatter at the room's own level is not an onset.
        repeat(HANGOVER_FRAMES + 1) { loud.onLevel(-30f) }
        assertFalse(loud.onLevel(-30f))
    }

    @Test
    fun sustainedSpeech_cannotDragTheFloorUpToItsOwnLevel() {
        val gate = WakeWordGate()
        gate.prime(levelDb = -70f)

        // Ten seconds of talking. Real speech is not a flat level — syllables,
        // word gaps and breaths put a good fraction of frames far below the
        // peaks, and the floor is a low percentile precisely so it tracks those
        // dips rather than the speech itself.
        val syllable = listOf(SPEECH_DB, -30f, -58f, SPEECH_DB, -34f, -62f, -40f, SPEECH_DB)
        repeat(125) { index -> gate.onLevel(syllable[index % syllable.size]) }

        assertTrue(
            "threshold ${gate.thresholdDb} climbed to speech level",
            gate.thresholdDb < SPEECH_DB
        )
        assertTrue(gate.onLevel(SPEECH_DB))
    }

    @Test
    fun anUnbrokenConstantTone_becomesTheFloorAndIsGatedOut() {
        val gate = WakeWordGate()
        gate.prime(levelDb = -70f)

        // A fan, a hum, a held note: once it has filled the whole floor window
        // it *is* the room's ambient level, and gating it out is the point.
        // Safe to do because re-arming replays the classifier window from
        // retained mel rows, so even if this were speech, closing the gate
        // costs CPU on the next onset and never costs a detection.
        repeat(PRIMING_FRAMES + HANGOVER_FRAMES + 1) { gate.onLevel(-25f) }
        assertFalse(gate.onLevel(-25f))

        // Anything that rises above the new ambient still gets through.
        assertTrue(gate.onLevel(-15f))
    }

    @Test
    fun rmsDb_isUnclampedAndTracksLevel() {
        val fullScale = ShortArray(160) { Short.MAX_VALUE }
        assertEquals(0f, WakeWordGate.rmsDb(fullScale, fullScale.size), 0.1f)

        assertEquals(WakeWordGate.SILENT_DB, WakeWordGate.rmsDb(ShortArray(160), 160), 0.001f)
        assertEquals(WakeWordGate.SILENT_DB, WakeWordGate.rmsDb(fullScale, 0), 0.001f)

        // The point of not reusing SilenceDetector.rmsDb: this keeps resolving
        // below -50 dBFS instead of flattening out there.
        val veryQuiet = ShortArray(160) { 20 }
        val quieter = ShortArray(160) { 5 }
        assertTrue(WakeWordGate.rmsDb(veryQuiet, 160) < -50f)
        assertTrue(WakeWordGate.rmsDb(quieter, 160) < WakeWordGate.rmsDb(veryQuiet, 160))
    }

    private companion object {
        /** Mirrors WakeWordGate.FLOOR_WINDOW / HANGOVER_FRAMES (both private). */
        const val PRIMING_FRAMES = 50
        const val HANGOVER_FRAMES = 38

        const val QUIET_ROOM_DB = -70f
        const val SPEECH_DB = -25f
    }
}
