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
 * Measurement harness for issue #37 (wake-word battery drain). Not a pass/fail
 * regression test — it exists to produce the two numbers the fix order depends
 * on, and to keep them reproducible on a real device:
 *
 * 1. **The per-stage cost split.** The issue assumes the mel → embedding →
 *    classify chain is dominated by the two large models. Whether the gate
 *    (fix #1) or the ORT thread config (fix #2) is the bigger lever depends on
 *    the actual ms-per-stage, so measure it rather than assume it.
 * 2. **Mel rows emitted per 80 ms frame.** The whole pre-roll design rests on
 *    "76 mel rows ≈ 10 frames ≈ 760 ms of audio", which was inferred from
 *    upstream openWakeWord convention, not read out of the bundled graph.
 *    [melRowsPerFrame_matchesTenMillisecondHop] pins it down.
 *
 * Run against the connected device (an emulator's timings are meaningless for
 * a power question):
 *
 * ```
 * ./gradlew :app:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=com.gotcha.service.WakeWordPipelineBenchmarkTest
 * ```
 *
 * Then read the numbers out of logcat: `adb logcat -s WakeWordBench`.
 */
@RunWith(AndroidJUnit4::class)
class WakeWordPipelineBenchmarkTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /**
     * Times each stage under both the shipped default [OrtSession.SessionOptions]
     * and the single-threaded/no-spin config proposed as fix #2, so the two are
     * compared on the same device in the same run.
     */
    @Test
    fun perStageCost_defaultVsTunedSessionOptions() {
        val default = measure("default options") { OrtSession.SessionOptions() }
        val tuned = measure("tuned options") { tunedOptions() }

        Log.i(TAG, "=== summary (median ms per 80 ms frame) ===")
        Log.i(TAG, "default: total=${default.medianTotalMs()} $default")
        Log.i(TAG, "tuned:   total=${tuned.medianTotalMs()} $tuned")
        Log.i(
            TAG,
            "duty cycle @12.5 fps — default=${dutyCycle(default)}%, tuned=${dutyCycle(tuned)}%"
        )

        // The harness is only useful if it actually exercised the full chain.
        assertTrue("no classify samples recorded", default.classify.isNotEmpty())
        assertTrue("no classify samples recorded", tuned.classify.isNotEmpty())
    }

    /**
     * Confirms the mel front-end emits ~8 rows per 80 ms frame (a 10 ms hop),
     * which is what makes a cold `melBuffer` need ~10 frames / 760 ms to refill
     * the 76-row embedding window. If this ever changes, the pre-roll size in
     * [OnnxWakeWordPipeline] has to change with it.
     */
    @Test
    fun melRowsPerFrame_matchesTenMillisecondHop() {
        val stats = measure("mel rows") { tunedOptions() }
        val steady = stats.melRows.drop(WARMUP_FRAMES)
        assertTrue("no mel runs recorded", steady.isNotEmpty())

        val distinct = steady.distinct().sorted()
        Log.i(TAG, "mel rows per frame (steady state): $distinct")
        val rows = distinct.single()
        val framesToFillEmbeddingWindow =
            (OnnxWakeWordPipeline.MEL_EMBEDDING_WINDOW + rows - 1) / rows
        Log.i(
            TAG,
            "$rows rows/frame → ${framesToFillEmbeddingWindow} frames " +
                "(${framesToFillEmbeddingWindow * 80} ms) to refill a cold mel buffer"
        )

        // 1760-sample window, 400-sample analysis window, 160-sample (10 ms) hop.
        assertEquals(8, rows)
    }

    private fun measure(label: String, options: () -> OrtSession.SessionOptions): Stats {
        val env = OrtEnvironment.getEnvironment()
        val stats = Stats()
        val mel = loadSession(env, "melspectrogram.onnx", options())
        val embedding = loadSession(env, "embedding_model.onnx", options())
        val classifier = loadSession(env, WakeWordDetector.CLASSIFIER_MODEL, options())
        try {
            val pipeline = OnnxWakeWordPipeline(
                env = env,
                melSession = mel,
                embeddingSession = embedding,
                classifierSession = classifier,
                matcher = WakeWordMatcher(WakeWordMatcher.DEFAULT_SENSITIVITY),
                probe = stats
            )
            val frame = ShortArray(OnnxWakeWordPipeline.FRAME_SIZE)
            repeat(WARMUP_FRAMES + MEASURED_FRAMES) { index ->
                fillSpeechLike(frame, index)
                pipeline.feed(frame, frame.size)
                if (index == WARMUP_FRAMES - 1) stats.resetTimings()
            }
            Log.i(TAG, "$label → $stats")
        } finally {
            classifier.close()
            embedding.close()
            mel.close()
        }
        return stats
    }

    private fun loadSession(
        env: OrtEnvironment,
        name: String,
        options: OrtSession.SessionOptions
    ): OrtSession {
        val bytes = context.assets
            .open("${WakeWordDetector.MODEL_ASSET_DIR}/$name")
            .use { it.readBytes() }
        return options.use { env.createSession(bytes, it) }
    }

    private fun tunedOptions() = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(1)
        setInterOpNumThreads(1)
        setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
        addConfigEntry("session.intra_op.allow_spinning", "0")
        addConfigEntry("session.inter_op.allow_spinning", "0")
    }

    /**
     * Band-limited noise at roughly speech level. Stage timings are shape-driven,
     * not content-driven — every ONNX input here is a fixed size regardless of
     * what the audio contains — so synthetic audio measures the same cost real
     * speech would, without shipping a WAV fixture.
     */
    private fun fillSpeechLike(frame: ShortArray, frameIndex: Int) {
        val random = Random(frameIndex)
        var phase = frameIndex * frame.size.toDouble()
        for (i in frame.indices) {
            val tone = sin(2.0 * PI * 220.0 * phase / OnnxWakeWordPipeline.SAMPLE_RATE)
            val noise = random.nextDouble(-0.3, 0.3)
            frame[i] = ((tone * 0.4 + noise) * 8000).toInt().toShort()
            phase += 1.0
        }
    }

    private fun dutyCycle(stats: Stats): Int =
        (stats.medianTotalMs() / 80.0 * 100).toInt()

    private class Stats : OnnxWakeWordPipeline.PipelineProbe {
        val mel = mutableListOf<Long>()
        val embed = mutableListOf<Long>()
        val classify = mutableListOf<Long>()
        val melRows = mutableListOf<Int>()

        override fun onStage(stage: OnnxWakeWordPipeline.PipelineProbe.Stage, nanos: Long) {
            when (stage) {
                OnnxWakeWordPipeline.PipelineProbe.Stage.MEL -> mel += nanos
                OnnxWakeWordPipeline.PipelineProbe.Stage.EMBEDDING -> embed += nanos
                OnnxWakeWordPipeline.PipelineProbe.Stage.CLASSIFY -> classify += nanos
            }
        }

        override fun onMelRows(rows: Int) {
            melRows += rows
        }

        /** Drops the warm-up samples; the first runs pay one-off lazy-init costs. */
        fun resetTimings() {
            mel.clear()
            embed.clear()
            classify.clear()
        }

        fun medianTotalMs(): Double = ms(mel, 50) + ms(embed, 50) + ms(classify, 50)

        override fun toString(): String =
            "mel p50=${ms(mel, 50)}ms p95=${ms(mel, 95)}ms, " +
                "embed p50=${ms(embed, 50)}ms p95=${ms(embed, 95)}ms, " +
                "classify p50=${ms(classify, 50)}ms p95=${ms(classify, 95)}ms"

        private fun ms(samples: List<Long>, percentile: Int): Double {
            if (samples.isEmpty()) return 0.0
            val sorted = samples.sorted()
            val index = (sorted.size * percentile / 100).coerceIn(0, sorted.lastIndex)
            return Math.round(sorted[index] / 1_000.0) / 1_000.0
        }
    }

    private companion object {
        const val TAG = "WakeWordBench"
        const val WARMUP_FRAMES = 30
        const val MEASURED_FRAMES = 500
    }
}
