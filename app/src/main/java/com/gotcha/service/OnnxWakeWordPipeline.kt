package com.gotcha.service

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer

/**
 * Runs the OpenWakeWord detection pipeline over a stream of 16 kHz PCM frames.
 *
 * Mirrors the reference streaming implementation in upstream `openWakeWord`
 * (`openwakeword/model.py` + `openwakeword/utils.py`, Apache-2.0):
 *
 * 1. Audio is accumulated in 1280-sample (80 ms) frames.
 * 2. `melspectrogram.onnx` is run on the most recent 1760 samples and its
 *    output is transformed with `spec / 10 + 2`.
 * 3. `embedding_model.onnx` turns the latest 76 mel rows into one 96-dim
 *    speech embedding per frame.
 * 4. `hey_gotcha.onnx` classifies the latest 16 embeddings and emits a score
 *    in [0, 1], which [WakeWordMatcher] turns into a single detection event.
 *
 * The first [WARMUP_FRAMES] scored frames are ignored so the feature buffers
 * fill up before anything can be detected.
 *
 * The hot path avoids per-frame GC pressure: the raw PCM ring buffer, the
 * combined feed buffer, the frame buffer, and the mel window are all
 * pre-allocated once and reused. The per-row mel arrays, `toList()` snapshots,
 * and ONNX tensor inputs are still allocated each frame (~37.5/s), which is
 * acceptable — keep this from drifting into the comment-free "zero allocation"
 * territory that a profiler would disprove.
 */
@Suppress("UNCHECKED_CAST")
internal class OnnxWakeWordPipeline(
    private val env: OrtEnvironment,
    private val melSession: OrtSession,
    private val embeddingSession: OrtSession,
    private val classifierSession: OrtSession,
    private val matcher: WakeWordMatcher
) {
    /** Ring buffer of the most recent raw PCM samples. Size caps at [MEL_WINDOW]. */
    private val rawSamples = ShortArray(MEL_WINDOW)
    private var rawStart = 0
    private var rawCount = 0

    /**
     * Scratch buffer that holds the current feed's samples plus whatever was
     * carried over from the previous feed. Bounded by
     * [MEL_WINDOW] + [FRAME_SIZE], which is the worst case when [feed] is
     * called with a full [FRAME_SIZE] shortly after a remainder was kept.
     */
    private val combined = ShortArray(MEL_WINDOW + FRAME_SIZE)
    private var combinedLength = 0

    /** Reused destination for each extracted 80 ms frame. */
    private val frame = ShortArray(FRAME_SIZE)

    /** Reused mel window (raw PCM → floats); filled up to [rawCount] per frame. */
    private val melWindow = FloatArray(MEL_WINDOW)

    private val melBuffer = ArrayDeque<FloatArray>()
    private val featureBuffer = ArrayDeque<FloatArray>()

    private var remainder = ShortArray(0)
    private var scoredFrames = 0

    init {
        assertMelInputLengthDynamic()
        prefillFeatures()
    }

    /**
     * The pipeline feeds the mel model a growing window (1280 → 1760 samples)
     * until the ring buffer fills, so the model's length axis must be dynamic.
     * Fails fast at construction if a bundled `.onnx` declares a static input
     * length, instead of throwing on the second frame of every run.
     */
    private fun assertMelInputLengthDynamic() {
        val input = melSession.inputInfo[melSession.inputNames.first()]?.info
        val shape = (input as? ai.onnxruntime.TensorInfo)?.getShape() ?: return
        val declaredLength = shape.getOrNull(1)
        require(declaredLength == null || declaredLength == -1L) {
            "melspectrogram.onnx declares a static input length of $declaredLength; " +
                "the pipeline requires a dynamic length axis (up to $MEL_WINDOW samples)."
        }
    }

    fun reset() {
        rawStart = 0
        rawCount = 0
        combinedLength = 0
        remainder = ShortArray(0)
        scoredFrames = 0
        melBuffer.clear()
        featureBuffer.clear()
        matcher.reset()
        prefillFeatures()
    }

    /** Feeds [count] PCM samples and returns true exactly once when the wake word fires. */
    fun feed(samples: ShortArray, count: Int): Boolean {
        var detected = false

        // Concatenate remainder + new samples into the reusable combined buffer.
        combinedLength = 0
        if (remainder.isNotEmpty()) {
            System.arraycopy(remainder, 0, combined, 0, remainder.size)
            combinedLength = remainder.size
        }
        System.arraycopy(samples, 0, combined, combinedLength, count)
        combinedLength += count

        val fullFrames = combinedLength / FRAME_SIZE
        var offset = 0
        repeat(fullFrames) {
            System.arraycopy(combined, offset, frame, 0, FRAME_SIZE)
            offset += FRAME_SIZE
            if (processFrame(frame)) detected = true
        }
        if (offset < combinedLength) {
            val leftover = combinedLength - offset
            // Reuse the remainder array's storage when it fits; otherwise
            // allocate once here (rare — only after the very first feed).
            if (remainder.size != leftover) remainder = ShortArray(leftover)
            System.arraycopy(combined, offset, remainder, 0, leftover)
        } else if (remainder.isNotEmpty()) {
            remainder = ShortArray(0)
        }
        return detected
    }

    private fun processFrame(frame: ShortArray): Boolean {
        // Append the frame to the raw ring buffer.
        for (sample in frame) {
            rawSamples[rawStart] = sample
            rawStart = (rawStart + 1) % MEL_WINDOW
            if (rawCount < MEL_WINDOW) rawCount++
        }

        if (!computeMel()) return false
        if (!computeEmbedding()) return false
        val score = classify() ?: return false

        scoredFrames++
        if (scoredFrames <= WARMUP_FRAMES) return false
        return matcher.onScore(score)
    }

    private fun computeMel(): Boolean {
        if (rawCount < FRAME_SIZE) return false

        // Build the mel window from the ring buffer in chronological order.
        // rawCount <= MEL_WINDOW; the oldest sample lives at rawStart
        // (modulo the ring) once rawCount == MEL_WINDOW.
        val length = rawCount.coerceAtMost(MEL_WINDOW)
        val start = if (rawCount < MEL_WINDOW) {
            0
        } else {
            rawStart // when full, rawStart points at the oldest sample
        }
        for (i in 0 until length) {
            melWindow[i] = rawSamples[(start + i) % MEL_WINDOW].toFloat()
        }

        val tensor = OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(melWindow, 0, length),
            longArrayOf(1L, length.toLong())
        )
        val rows = try {
            melSession.run(mapOf(melSession.inputNames.first() to tensor)).use { result ->
                (result.get(0).value as Array<Array<Array<FloatArray>>>)[0][0]
            }
        } finally {
            tensor.close()
        }
        if (rows.isEmpty()) return false
        for (row in rows) {
            melBuffer.addLast(FloatArray(MEL_BINS) { row[it] / 10f + 2f })
        }
        while (melBuffer.size > MEL_BUFFER_MAX) melBuffer.removeFirst()
        return true
    }

    private fun computeEmbedding(): Boolean {
        if (melBuffer.size < MEL_EMBEDDING_WINDOW) return false
        val mels = melBuffer.toList()
        val x = Array(1) { Array(MEL_EMBEDDING_WINDOW) { Array(MEL_BINS) { FloatArray(1) } } }
        val base = mels.size - MEL_EMBEDDING_WINDOW
        for (k in 0 until MEL_EMBEDDING_WINDOW) {
            val row = mels[base + k]
            for (w in 0 until MEL_BINS) x[0][k][w][0] = row[w]
        }

        val tensor = OnnxTensor.createTensor(env, x)
        val embedding = try {
            embeddingSession.run(mapOf(embeddingSession.inputNames.first() to tensor)).use { result ->
                (result.get(0).value as Array<Array<Array<FloatArray>>>)[0][0][0].copyOf()
            }
        } finally {
            tensor.close()
        }
        featureBuffer.addLast(embedding)
        while (featureBuffer.size > FEATURE_BUFFER_MAX) featureBuffer.removeFirst()
        return true
    }

    private fun classify(): Float? {
        if (featureBuffer.size < CLASSIFIER_FRAMES) return null
        val features = featureBuffer.toList()
        val x = Array(1) { Array(CLASSIFIER_FRAMES) { FloatArray(FEATURE_DIM) } }
        val base = features.size - CLASSIFIER_FRAMES
        for (k in 0 until CLASSIFIER_FRAMES) x[0][k] = features[base + k]

        val tensor = OnnxTensor.createTensor(env, x)
        return try {
            classifierSession.run(mapOf(classifierSession.inputNames.first() to tensor)).use { result ->
                (result.get(0).value as Array<FloatArray>)[0][0]
            }
        } finally {
            tensor.close()
        }
    }

    private fun prefillFeatures() {
        repeat(CLASSIFIER_FRAMES) { featureBuffer.addLast(FloatArray(FEATURE_DIM)) }
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val FRAME_SIZE = 1280
        const val MEL_WINDOW = 1760
        const val MEL_BINS = 32
        const val MEL_EMBEDDING_WINDOW = 76
        const val FEATURE_DIM = 96
        const val CLASSIFIER_FRAMES = 16
        private const val WARMUP_FRAMES = 5
        private const val MEL_BUFFER_MAX = 120
        private const val FEATURE_BUFFER_MAX = 32
    }
}
