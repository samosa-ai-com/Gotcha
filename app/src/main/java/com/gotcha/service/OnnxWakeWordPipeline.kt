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
 * ## Energy gating (issue #37)
 *
 * Steps 1 and 2 above are ~8% of a frame's cost; step 3 is the rest. So the
 * mel front-end runs on every frame and [WakeWordGate] decides whether to pay
 * for the embedding backbone. When the room is quiet the pipeline is doing
 * mel + an RMS, and nothing else.
 *
 * Re-arming is **exact**, not approximate. Because `melBuffer` kept filling
 * while the gate was shut, [replayGatedEmbeddings] can rebuild the skipped
 * embeddings from the real audio that produced them, so the classifier sees
 * bit-for-bit the window an ungated run would have handed it. That is what
 * `OnnxWakeWordPipelineGateTest` asserts, and it is why gating cannot cost a
 * detection — including when the gate closes mid-phrase.
 *
 * The one thing gating trades away is CPU in a *choppy* room: each re-arm
 * replays up to 15 embeddings, which only pays for itself if the quiet gap was
 * longer than ~1.3 s. `WakeWordGate`'s hangover is what keeps gaps that short
 * from re-arming at all.
 *
 * ## Allocation
 *
 * The hot path is close to allocation-free, which matters on a path that runs
 * for as long as the phone is on. Pre-allocated and reused: the raw PCM ring,
 * the combined feed buffer, the frame buffer, the mel window, the embedding
 * input (`[1][76][32][1]` — this one was 2432 one-element arrays per frame),
 * and the classifier input. The mel rows and embeddings are recycled through
 * [melRowPool] and [featurePool] as they fall out of their bounded buffers.
 *
 * What still allocates per frame: the `OnnxTensor` wrappers and whatever ORT
 * hands back from `run()`. Both are ORT's to own. This is not "zero
 * allocation" — keep the claim honest, a profiler is the arbiter.
 */
@Suppress("UNCHECKED_CAST")
internal class OnnxWakeWordPipeline(
    private val env: OrtEnvironment,
    private val melSession: OrtSession,
    private val embeddingSession: OrtSession,
    private val classifierSession: OrtSession,
    private val matcher: WakeWordMatcher,
    private val gate: WakeWordGate = WakeWordGate(),
    private val probe: PipelineProbe? = null
) {
    /**
     * Optional measurement hook. Off (null) in production; the instrumented
     * benchmark passes one in to get the per-stage cost split that decides how
     * much of the wake-word CPU budget each ONNX model actually owns.
     */
    internal interface PipelineProbe {
        fun onStage(stage: Stage, nanos: Long) {}

        /** Number of mel rows one `melspectrogram.onnx` run emitted. */
        fun onMelRows(rows: Int) {}

        /** A frame whose embedding stage was skipped by [WakeWordGate]. */
        fun onGated() {}

        /** The classifier's output for a frame that made it past the gate. */
        fun onScore(score: Float) {}

        /**
         * Summary statistics of one stage's output. Used to bisect where a
         * detection failure lives — if the mel values match a working run but
         * the embeddings do not, the audio is fine and the model is not, and
         * vice versa. Statistics only: these could not reconstruct speech.
         */
        fun onStageStats(stage: Stage, min: Float, mean: Float, max: Float) {}

        enum class Stage { MEL, EMBEDDING, CLASSIFY }
    }

    /** Ring buffer of the most recent raw PCM samples. Size caps at [MEL_WINDOW]. */
    private val rawSamples = ShortArray(MEL_WINDOW)
    private var rawStart = 0
    private var rawCount = 0

    /**
     * Scratch buffer that holds the current feed's samples plus whatever was
     * carried over from the previous feed. [feed] takes whatever the caller's
     * `AudioRecord.read` returned — several frames at a time, since the reads
     * were batched to cut wakeups — so this grows to fit the largest feed seen
     * and is then reused. The carried-over remainder is always shorter than
     * [FRAME_SIZE].
     */
    private var combined = ShortArray(MEL_WINDOW + FRAME_SIZE)
    private var combinedLength = 0

    /** Reused destination for each extracted 80 ms frame. */
    private val frame = ShortArray(FRAME_SIZE)

    /** Reused mel window (raw PCM → floats); filled up to [rawCount] per frame. */
    private val melWindow = FloatArray(MEL_WINDOW)

    private val melBuffer = ArrayDeque<FloatArray>()
    private val featureBuffer = ArrayDeque<FloatArray>()

    /**
     * Mel rows that fell out of [melBuffer], kept for reuse. The buffer is
     * bounded, so in the steady state every row appended can take the storage
     * of one that was just evicted and the hot path stops allocating.
     */
    private val melRowPool = ArrayDeque<FloatArray>()

    /** The same idea as [melRowPool], for the 96-dim embeddings. */
    private val featurePool = ArrayDeque<FloatArray>()

    /**
     * Reused embedding input, `[1][76][32][1]`. Allocating this per frame meant
     * 2432 one-element FloatArrays every 80 ms — by a wide margin the largest
     * source of garbage on a path that runs forever. Every element is written
     * before the tensor is built, and the tensor is closed before the next fill.
     */
    private val embeddingInput =
        Array(1) { Array(MEL_EMBEDDING_WINDOW) { Array(MEL_BINS) { FloatArray(1) } } }

    /**
     * Reused classifier input, `[1][16][96]`. The rows are references into
     * [featureBuffer], replaced wholesale each call, so the placeholder they
     * start out pointing at is never read.
     */
    private val classifierInput = Array(1) { Array(CLASSIFIER_FRAMES) { EMPTY_FEATURE } }

    /**
     * Rows appended to [melBuffer] by each of the last [CLASSIFIER_FRAMES]
     * frames. Re-arming after the gate closes has to rebuild the embeddings for
     * a run of earlier frames, and that means knowing where each of those
     * frames ended in [melBuffer]. Measured to be a flat 8 rows per frame, but
     * recording it beats hardcoding a number the bundled graph could change.
     */
    private val rowCounts = IntArray(CLASSIFIER_FRAMES)
    private var rowCountHead = 0
    private var rowCountFilled = 0

    /** Consecutive frames whose embedding was skipped because the gate was shut. */
    private var gatedFrames = 0

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
        rowCountHead = 0
        rowCountFilled = 0
        gatedFrames = 0
        melBuffer.clear()
        featureBuffer.clear()
        melRowPool.clear()
        featurePool.clear()
        matcher.reset()
        gate.reset()
        prefillFeatures()
    }

    /** Feeds [count] PCM samples and returns true exactly once when the wake word fires. */
    fun feed(samples: ShortArray, count: Int): Boolean {
        var detected = false

        // Concatenate remainder + new samples into the reusable combined buffer.
        val needed = remainder.size + count
        if (combined.size < needed) combined = ShortArray(needed)
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

        val open = gate.onFrame(frame, FRAME_SIZE)

        // The mel front-end runs unconditionally even while the gate is shut.
        // It is ~8% of the chain's cost, and keeping it warm is what makes
        // re-arming exact: every mel row the embedding stage skipped is still
        // in melBuffer, so the classifier window can be rebuilt from real audio
        // instead of from whatever silence-shaped stand-in was available.
        if (!timed(PipelineProbe.Stage.MEL) { computeMel() }) return false

        if (!open) {
            if (gatedFrames < CLASSIFIER_FRAMES) gatedFrames++
            probe?.onGated()
            return false
        }
        if (gatedFrames > 0) {
            replayGatedEmbeddings(gatedFrames)
            gatedFrames = 0
        }

        if (!timed(PipelineProbe.Stage.EMBEDDING) { computeEmbedding() }) return false
        val score = timed(PipelineProbe.Stage.CLASSIFY) { classify() } ?: return false
        probe?.onScore(score)

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
        probe?.onMelRows(rows.size)
        probe?.let { active ->
            var min = Float.MAX_VALUE
            var max = -Float.MAX_VALUE
            var sum = 0.0
            for (row in rows) {
                for (value in row) {
                    if (value < min) min = value
                    if (value > max) max = value
                    sum += value
                }
            }
            active.onStageStats(
                PipelineProbe.Stage.MEL,
                min,
                (sum / (rows.size * MEL_BINS)).toFloat(),
                max
            )
        }
        for (row in rows) {
            val stored = melRowPool.removeLastOrNull() ?: FloatArray(MEL_BINS)
            for (bin in 0 until MEL_BINS) stored[bin] = row[bin] / 10f + 2f
            melBuffer.addLast(stored)
        }
        while (melBuffer.size > MEL_BUFFER_MAX) melRowPool.addLast(melBuffer.removeFirst())
        recordRowCount(rows.size)
        return true
    }

    /**
     * Rebuilds the embeddings for the [missedFrames] frames the gate skipped,
     * oldest first, so the classifier sees the same 16-embedding window it
     * would have seen had the gate never closed.
     *
     * Only the last [CLASSIFIER_FRAMES] − 1 matter: anything older has already
     * fallen out of the classifier's window, and the entries still sitting in
     * [featureBuffer] from before the gate closed are exactly the ones an
     * ungated run would have there.
     */
    private fun replayGatedEmbeddings(missedFrames: Int) {
        val replay = minOf(missedFrames, CLASSIFIER_FRAMES - 1, rowCountFilled - 1)
        for (framesBack in replay downTo 1) {
            timed(PipelineProbe.Stage.EMBEDDING) { computeEmbedding(rowsInLast(framesBack)) }
        }
    }

    /**
     * Runs the embedding model over the 76 mel rows ending [rowsBack] rows
     * before the end of [melBuffer]. [rowsBack] of 0 is the current frame.
     */
    private fun computeEmbedding(rowsBack: Int = 0): Boolean {
        val end = melBuffer.size - rowsBack
        val base = end - MEL_EMBEDDING_WINDOW
        if (base < 0) return false
        val x = embeddingInput
        for (k in 0 until MEL_EMBEDDING_WINDOW) {
            val row = melBuffer[base + k]
            val slot = x[0][k]
            for (w in 0 until MEL_BINS) slot[w][0] = row[w]
        }

        val tensor = OnnxTensor.createTensor(env, x)
        val embedding = try {
            embeddingSession.run(mapOf(embeddingSession.inputNames.first() to tensor)).use { result ->
                val out = (result.get(0).value as Array<Array<Array<FloatArray>>>)[0][0][0]
                // Recycled rather than copyOf()'d. Safe because classify()
                // refills every slot of classifierInput before it reads them,
                // so no evicted row is ever read after being handed back.
                val stored = featurePool.removeLastOrNull() ?: FloatArray(FEATURE_DIM)
                out.copyInto(stored)
                stored
            }
        } finally {
            tensor.close()
        }
        probe?.let { active ->
            var min = Float.MAX_VALUE
            var max = -Float.MAX_VALUE
            var sum = 0.0
            for (value in embedding) {
                if (value < min) min = value
                if (value > max) max = value
                sum += value
            }
            active.onStageStats(
                PipelineProbe.Stage.EMBEDDING,
                min,
                (sum / embedding.size).toFloat(),
                max
            )
        }
        featureBuffer.addLast(embedding)
        while (featureBuffer.size > FEATURE_BUFFER_MAX) featurePool.addLast(featureBuffer.removeFirst())
        return true
    }

    private fun classify(): Float? {
        if (featureBuffer.size < CLASSIFIER_FRAMES) return null
        val x = classifierInput
        val base = featureBuffer.size - CLASSIFIER_FRAMES
        for (k in 0 until CLASSIFIER_FRAMES) x[0][k] = featureBuffer[base + k]

        val tensor = OnnxTensor.createTensor(env, x)
        return try {
            classifierSession.run(mapOf(classifierSession.inputNames.first() to tensor)).use { result ->
                (result.get(0).value as Array<FloatArray>)[0][0]
            }
        } finally {
            tensor.close()
        }
    }

    private fun recordRowCount(rows: Int) {
        rowCounts[rowCountHead] = rows
        rowCountHead = (rowCountHead + 1) % CLASSIFIER_FRAMES
        if (rowCountFilled < CLASSIFIER_FRAMES) rowCountFilled++
    }

    /** Rows appended by the most recent [frames] frames, current frame included. */
    private fun rowsInLast(frames: Int): Int {
        var total = 0
        for (k in 1..frames) {
            total += rowCounts[(rowCountHead - k + CLASSIFIER_FRAMES) % CLASSIFIER_FRAMES]
        }
        return total
    }

    /** Times [body] only when a [probe] is attached, so production stays free of the clock reads. */
    private inline fun <T> timed(stage: PipelineProbe.Stage, body: () -> T): T {
        val active = probe ?: return body()
        val startedAt = System.nanoTime()
        try {
            return body()
        } finally {
            active.onStage(stage, System.nanoTime() - startedAt)
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

        /**
         * Mel rows kept around. Re-arming after the gate closes has to rebuild
         * the embeddings for up to [CLASSIFIER_FRAMES] − 1 earlier frames, and
         * the oldest of those still needs a full [MEL_EMBEDDING_WINDOW] of rows
         * behind it: 76 + 15 × 8 = 196 at the measured 8 rows per frame. 240
         * leaves headroom without mattering (240 × 32 floats ≈ 30 KB).
         */
        private const val MEL_BUFFER_MAX = 240
        private const val FEATURE_BUFFER_MAX = 32

        /** Placeholder for [classifierInput]'s rows; overwritten before every read. */
        private val EMPTY_FEATURE = FloatArray(FEATURE_DIM)
    }
}
