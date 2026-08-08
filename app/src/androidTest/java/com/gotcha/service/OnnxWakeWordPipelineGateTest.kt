package com.gotcha.service

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * The regression guard for the energy gate added in issue #37.
 *
 * The acceptance criterion for that work is "no drop in detection rate", which
 * is normally only checkable by saying "Hey Gotcha" at a phone and trusting the
 * result. The gate is built so it can be checked properly instead: because the
 * mel front-end keeps running while the gate is shut, every embedding the gate
 * skipped can be rebuilt from the mel rows that are still in the buffer. So a
 * gated pipeline and an ungated one must emit **identical** classifier scores
 * for every frame the gate let through — not similar, identical.
 *
 * If that holds, gating provably cannot change what the matcher sees, and so
 * cannot change the detection rate. If someone later breaks the replay path,
 * these tests fail rather than the wake word quietly getting worse in rooms
 * that happen to be quiet.
 *
 * ```
 * ./gradlew :app:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=com.gotcha.service.OnnxWakeWordPipelineGateTest
 * ```
 */
@RunWith(AndroidJUnit4::class)
class OnnxWakeWordPipelineGateTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun gatedAndUngatedPipelines_produceIdenticalScores() {
        val clip = buildClip()
        val gated = run(clip, WakeWordGate())
        val ungated = run(clip, WakeWordGate.alwaysOpen())

        Log.i(
            TAG,
            "frames=${clip.size} scored: gated=${gated.scores.size} " +
                "ungated=${ungated.scores.size} gatedOut=${gated.gatedFrames}"
        )

        assertTrue(
            "the gate never closed, so this proved nothing — check the clip's silence level",
            gated.gatedFrames > 0
        )
        assertTrue("the gate never opened", gated.scores.isNotEmpty())
        assertEquals(
            "the ungated baseline should score every frame it can",
            ungated.scores.size,
            gated.scores.size + gated.gatedFrames
        )

        for ((frame, score) in gated.scores) {
            val baseline = ungated.scores[frame]
                ?: error("ungated run produced no score for frame $frame")
            assertEquals(
                "frame $frame diverged after the gate re-armed",
                baseline,
                score,
                0f
            )
        }
    }

    /**
     * The case the replay exists for: the gate shuts during a long silence and
     * has to re-arm on the first frame of the phrase. Every score from the
     * re-arm onwards has to match the ungated baseline, because those are the
     * frames a detection would actually come from.
     */
    @Test
    fun scoresImmediatelyAfterReArming_matchTheUngatedBaseline() {
        val clip = buildClip()
        val gated = run(clip, WakeWordGate())
        val ungated = run(clip, WakeWordGate.alwaysOpen())

        // The first scored frame after a gap in the scored-frame sequence is a
        // re-arm. Those are the frames the replay path is responsible for.
        val scoredFrames = gated.scores.keys.sorted()
        val reArms = scoredFrames.filterIndexed { index, frame ->
            index > 0 && frame != scoredFrames[index - 1] + 1
        }
        Log.i(TAG, "re-arm frames: $reArms")
        assertTrue("no re-arm happened in this clip", reArms.isNotEmpty())

        for (frame in reArms) {
            // The re-arm frame plus the rest of the classifier window after it.
            for (offset in 0 until OnnxWakeWordPipeline.CLASSIFIER_FRAMES) {
                val score = gated.scores[frame + offset] ?: continue
                assertEquals(
                    "frame ${frame + offset} (re-arm at $frame + $offset) diverged",
                    ungated.scores.getValue(frame + offset),
                    score,
                    0f
                )
            }
        }
    }

    private fun run(clip: List<ShortArray>, gate: WakeWordGate): Recorder {
        val env = OrtEnvironment.getEnvironment()
        val recorder = Recorder()
        val mel = loadSession(env, "melspectrogram.onnx")
        val embedding = loadSession(env, "embedding_model.onnx")
        val classifier = loadSession(env, WakeWordDetector.CLASSIFIER_MODEL)
        try {
            val pipeline = OnnxWakeWordPipeline(
                env = env,
                melSession = mel,
                embeddingSession = embedding,
                classifierSession = classifier,
                matcher = WakeWordMatcher(WakeWordMatcher.DEFAULT_SENSITIVITY),
                gate = gate,
                probe = recorder
            )
            clip.forEach { frame -> pipeline.feed(frame, frame.size) }
        } finally {
            classifier.close()
            embedding.close()
            mel.close()
        }
        return recorder
    }

    private fun loadSession(env: OrtEnvironment, name: String): OrtSession {
        val bytes = context.assets
            .open("${WakeWordDetector.MODEL_ASSET_DIR}/$name")
            .use { it.readBytes() }
        return OrtSession.SessionOptions().use { env.createSession(bytes, it) }
    }

    /**
     * Room tone, then two bursts of sound separated by silences long enough to
     * outlast the gate's hangover. Parity is a property of the plumbing, not of
     * the audio, so this does not need to be a real "Hey Gotcha" recording — it
     * needs to make the gate open, close, and re-arm, which it does.
     */
    private fun buildClip(): List<ShortArray> = buildList {
        // The first stretch has to outlast both the 50-frame floor priming and
        // the 38-frame hangover before the gate can close at all.
        addAll(frames(count = 100, amplitude = QUIET))
        addAll(frames(count = 20, amplitude = LOUD))
        addAll(frames(count = 70, amplitude = QUIET))
        addAll(frames(count = 25, amplitude = LOUD))
        addAll(frames(count = 60, amplitude = QUIET))
    }

    private fun frames(count: Int, amplitude: Int): List<ShortArray> =
        List(count) { index ->
            val random = Random(index * 31 + amplitude)
            ShortArray(OnnxWakeWordPipeline.FRAME_SIZE) { i ->
                val tone = sin(2.0 * PI * 220.0 * i / OnnxWakeWordPipeline.SAMPLE_RATE)
                ((tone * 0.6 + random.nextDouble(-0.4, 0.4)) * amplitude).toInt().toShort()
            }
        }

    private class Recorder : OnnxWakeWordPipeline.PipelineProbe {
        /** Frame index → classifier score. */
        val scores = mutableMapOf<Int, Float>()
        var gatedFrames = 0
            private set

        // computeMel() runs on every frame, gated or not, and emits rows every
        // time — so this fires exactly once per frame and doubles as the clock.
        private var frames = -1

        override fun onMelRows(rows: Int) {
            frames++
        }

        override fun onGated() {
            gatedFrames++
        }

        override fun onScore(score: Float) {
            scores[frames] = score
        }
    }

    private companion object {
        const val TAG = "WakeWordGateParity"

        /** Below the gate's −60 dBFS floor once the noise floor has been learned. */
        const val QUIET = 12
        const val LOUD = 6000
    }
}
