package com.gotcha.service

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Diagnostic for "Hey Gotcha stopped firing" after the issue #37 battery work.
 *
 * Covers the two gaps the existing tests left:
 *
 * 1. **Batched feeds.** Production now drains 4 frames per `AudioRecord.read`,
 *    but every test so far fed one frame per call. `feed()` grows its scratch
 *    buffer for the larger blocks, and nothing exercised that.
 * 2. **An actual positive.** The parity test proves gated and ungated agree,
 *    which says nothing about whether either one still *detects*. This drives
 *    the pipeline with the device's own TTS saying the wake phrase — the model
 *    was trained on synthetic TTS positives, so this is a fair probe.
 *
 * ```
 * ./gradlew :app:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=com.gotcha.service.WakeWordDetectionDiagnosticTest
 * ```
 */
@RunWith(AndroidJUnit4::class)
class WakeWordDetectionDiagnosticTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /**
     * Feeding the same audio one frame at a time and four frames at a time must
     * be indistinguishable. This is the exact shape of the production change,
     * and it was untested.
     */
    @Test
    fun batchedFeeds_matchSingleFrameFeeds() {
        val clip = syntheticClip()

        val single = run(WakeWordGate.alwaysOpen()) { pipeline ->
            clip.forEach { pipeline.feed(it, it.size) }
        }
        val batched = run(WakeWordGate.alwaysOpen()) { pipeline ->
            clip.chunked(4).forEach { group ->
                val block = ShortArray(group.sumOf { it.size })
                var offset = 0
                group.forEach { frame ->
                    frame.copyInto(block, offset)
                    offset += frame.size
                }
                pipeline.feed(block, block.size)
            }
        }

        Log.i(TAG, "single=${single.scores.size} scores, batched=${batched.scores.size} scores")
        assertEquals("batching changed how many frames got scored", single.scores.size, batched.scores.size)
        for ((frame, score) in single.scores) {
            assertEquals("frame $frame differs when fed in batches", score, batched.scores.getValue(frame), 0f)
        }
    }

    /**
     * The end-to-end question: does the phrase still fire the matcher, and does
     * the gate change that answer? Both pipelines get identical audio.
     */
    @Test
    fun spokenWakePhrase_stillFires() {
        val samples = synthesizePhrase("Hey Gotcha")
        Log.i(TAG, "phrase is ${samples.size} samples (${samples.size * 1000 / 16000} ms)")

        // Lead-in and tail of near-silence: the gate needs somewhere to learn a
        // floor and close, so this reproduces a real trigger from a quiet room.
        val clip = framesOf(silence(90) + samples + silence(40))

        val ungated = run(WakeWordGate.alwaysOpen()) { pipeline ->
            clip.forEach { pipeline.feed(it, it.size) }
        }
        val gated = run(WakeWordGate()) { pipeline ->
            clip.forEach { pipeline.feed(it, it.size) }
        }

        Log.i(
            TAG,
            "ungated: peak=${ungated.peak} detections=${ungated.detections} | " +
                "gated: peak=${gated.peak} detections=${gated.detections} gatedOut=${gated.gatedFrames}"
        )
        Log.i(TAG, "matcher threshold at default sensitivity = ${WakeWordMatcher(WakeWordMatcher.DEFAULT_SENSITIVITY).threshold()}")

        assertTrue(
            "the pipeline never scored the phrase above 0.1 (peak=${ungated.peak}) — " +
                "TTS may not be a usable positive on this device, so this test cannot " +
                "tell us anything about the gate",
            ungated.peak > 0.1f
        )
        assertEquals(
            "the gate changed the peak score (ungated=${ungated.peak}, gated=${gated.peak})",
            ungated.peak,
            gated.peak,
            0f
        )
        assertEquals(
            "the gate changed whether the phrase fired",
            ungated.detections,
            gated.detections
        )
    }

    private fun run(gate: WakeWordGate, body: (OnnxWakeWordPipeline) -> Unit): Recorder {
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
            body(pipeline)
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

    /** Renders [text] with the device TTS and returns it as 16 kHz mono PCM-16. */
    private fun synthesizePhrase(text: String): ShortArray {
        val ready = CountDownLatch(1)
        var status = TextToSpeech.ERROR
        lateinit var tts: TextToSpeech
        tts = TextToSpeech(context) { code ->
            status = code
            ready.countDown()
        }
        assertTrue("TTS never initialised", ready.await(30, TimeUnit.SECONDS))
        assertEquals("no TTS engine on this device", TextToSpeech.SUCCESS, status)

        val out = File(context.cacheDir, "wake-probe.wav")
        val done = CountDownLatch(1)
        tts.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) = done.countDown()
            @Deprecated("required override")
            override fun onError(utteranceId: String?) = done.countDown()
        })
        tts.synthesizeToFile(text, null, out, "wake-probe")
        try {
            assertTrue("TTS never produced a file", done.await(30, TimeUnit.SECONDS))
            tts.shutdown()
            return resampleTo16k(readWav(out))
        } finally {
            // A rendering of the wake phrase is not something to leave sitting
            // in the app's cache after the test.
            out.delete()
        }
    }

    /** Minimal PCM-16 WAV reader: walks the chunk list for `fmt ` and `data`. */
    private fun readWav(file: File): Pair<Int, ShortArray> {
        val bytes = file.readBytes()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var offset = 12 // past "RIFF" <size> "WAVE"
        var sampleRate = 0
        var channels = 1
        while (offset + 8 <= bytes.size) {
            val id = String(bytes, offset, 4, Charsets.US_ASCII)
            val size = buffer.getInt(offset + 4)
            val body = offset + 8
            when (id) {
                "fmt " -> {
                    channels = buffer.getShort(body + 2).toInt()
                    sampleRate = buffer.getInt(body + 4)
                }
                "data" -> {
                    val count = size / 2
                    val all = ShortArray(count) { buffer.getShort(body + it * 2) }
                    val mono = if (channels == 1) all else {
                        ShortArray(count / channels) { all[it * channels] }
                    }
                    Log.i(TAG, "TTS wav: ${sampleRate}Hz, ${channels}ch, ${mono.size} samples")
                    return sampleRate to mono
                }
            }
            offset = body + size + (size % 2)
        }
        error("no data chunk in ${file.name}")
    }

    /** Linear resample. Good enough to ask "does this still fire"; not a codec. */
    private fun resampleTo16k(input: Pair<Int, ShortArray>): ShortArray {
        val (rate, samples) = input
        if (rate == OnnxWakeWordPipeline.SAMPLE_RATE) return samples
        val ratio = rate.toDouble() / OnnxWakeWordPipeline.SAMPLE_RATE
        val out = ShortArray((samples.size / ratio).toInt())
        for (i in out.indices) {
            val at = i * ratio
            val low = at.toInt()
            val high = (low + 1).coerceAtMost(samples.lastIndex)
            val frac = at - low
            out[i] = (samples[low] * (1 - frac) + samples[high] * frac).toInt().toShort()
        }
        return out
    }

    private fun silence(frames: Int) =
        ShortArray(frames * OnnxWakeWordPipeline.FRAME_SIZE) { (-6..6).random().toShort() }

    private fun framesOf(samples: ShortArray): List<ShortArray> =
        (0 until samples.size / OnnxWakeWordPipeline.FRAME_SIZE).map { index ->
            samples.copyOfRange(
                index * OnnxWakeWordPipeline.FRAME_SIZE,
                (index + 1) * OnnxWakeWordPipeline.FRAME_SIZE
            )
        }

    private fun syntheticClip(): List<ShortArray> = framesOf(
        silence(60) + ShortArray(40 * OnnxWakeWordPipeline.FRAME_SIZE) { i ->
            (kotlin.math.sin(2.0 * Math.PI * 300.0 * i / 16000.0) * 5000).toInt().toShort()
        } + silence(60)
    )

    private operator fun ShortArray.plus(other: ShortArray): ShortArray {
        val out = ShortArray(size + other.size)
        copyInto(out)
        other.copyInto(out, size)
        return out
    }

    private class Recorder : OnnxWakeWordPipeline.PipelineProbe {
        val scores = mutableMapOf<Int, Float>()
        var gatedFrames = 0
        var peak = 0f
        var detections = 0
        private var frames = -1

        override fun onMelRows(rows: Int) {
            frames++
        }

        override fun onGated() {
            gatedFrames++
        }

        override fun onScore(score: Float) {
            scores[frames] = score
            if (score > peak) peak = score
            if (score >= WakeWordMatcher(WakeWordMatcher.DEFAULT_SENSITIVITY).threshold()) detections++
        }
    }

    private companion object {
        const val TAG = "WakeWordDiag"
    }
}
