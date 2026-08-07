package com.gotcha.service

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.util.Log
import androidx.core.content.ContextCompat
import com.gotcha.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * On-device "Hey Gotcha" keyword listener backed by OpenWakeWord ONNX models.
 * It owns the microphone only while running.
 *
 * The three ONNX models are read from assets and loaded on an IO coroutine —
 * model load takes tens of milliseconds, so doing it on the service's main
 * thread would show up as a ball-service freeze right after start.
 *
 * There are two ways to stop it, and the difference is the point:
 * [pause] gives up the microphone but keeps the loaded models, for the
 * constant interruptions of ordinary use; [release] gives up everything, for
 * when the feature is switched off or the service is going away.
 */
class WakeWordDetector(
    context: Context,
    private val scope: CoroutineScope,
    private val sensitivityProvider: () -> Float,
    private val onStarted: () -> Unit = {},
    private val onDetected: () -> Unit,
    private val onError: (String) -> Unit = {},
    private val modelAssetDir: String = MODEL_ASSET_DIR,
    private val classifierModel: String = CLASSIFIER_MODEL
) {
    private val appContext = context.applicationContext
    private val stateLock = Any()
    private val running = AtomicBoolean(false)
    private var job: Job? = null
    private var recorder: AudioRecord? = null

    /**
     * The three ORT sessions, cached across [pause]/[start] cycles. Only
     * [release] closes them — see [pause] for why.
     */
    private var sessions: Sessions? = null

    /**
     * How many listen loops are currently running inference against [sessions].
     * Normally 0 or 1; briefly 2 when a stale loop has not yet noticed it was
     * superseded. Guarded by [stateLock].
     *
     * This exists so the inference itself does not have to hold [stateLock].
     * A `run()` on the embedding model, plus the burst a gate re-arm can
     * trigger, is tens of milliseconds — long enough that holding the lock
     * across it would stall any main-thread `pause()`/`release()` behind it.
     * Instead the lock is held only for the state checks, and the models are
     * kept alive by whoever is using them.
     */
    private var sessionUsers = 0

    /** Sessions a [release] wanted to close while a listen loop still held them. */
    private var pendingClose: Sessions? = null
    private var matcher = WakeWordMatcher(WakeWordMatcher.DEFAULT_SENSITIVITY)
    private var generation = 0

    private class Sessions(
        val mel: OrtSession,
        val embedding: OrtSession,
        val classifier: OrtSession
    ) {
        private val closed = AtomicBoolean(false)

        /**
         * Idempotent. The reference count should make a second close
         * unreachable — but ORT throws on one, and an ownership mistake here
         * should not be the thing that takes down a user's assistant. In debug
         * builds it complains loudly so the mistake still gets found.
         */
        fun close() {
            if (!closed.compareAndSet(false, true)) {
                if (BuildConfig.DEBUG) {
                    Log.w(TAG, "Sessions closed twice", Throwable("second close from"))
                }
                return
            }
            mel.close()
            embedding.close()
            classifier.close()
        }
    }

    @Volatile
    var lastError: String? = null
        private set

    /** Returns false only for a synchronous precondition failure (currently the mic grant). */
    fun start(): Boolean {
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            lastError = "Microphone permission is not granted."
            return false
        }
        synchronized(stateLock) {
            if (running.get()) return true
            lastError = null
            generation++
            matcher = WakeWordMatcher(sensitivityProvider().coerceIn(0f, 1f))
            running.set(true)
            val startGeneration = generation
            if (BuildConfig.DEBUG) Log.d(TAG, "start(): generation=$startGeneration")
            job = scope.launch(Dispatchers.IO) { initializeAndListen(startGeneration) }
        }
        return true
    }

    /**
     * Stops listening and **fully releases the microphone**, but keeps the ONNX
     * sessions loaded for the next [start].
     *
     * This is the right call for every transient interruption — a call starting
     * or ending, the app's own TTS speaking. Those happen constantly: the
     * service pauses the listener on every single TTS utterance, and before
     * this split that meant re-reading ~2.6 MB of model bytes out of assets and
     * rebuilding three ORT sessions each time the app said one sentence.
     *
     * The mic is still released here, not merely stopped. That matters beyond
     * battery: pausing while TTS speaks is a privacy guarantee, not an
     * optimisation (privacy-data-retention.md §10.3), and "we kept the mic open
     * but promise we ignored it" is not the same guarantee. Keeping sessions is
     * safe because a loaded model holds no audio.
     */
    fun pause() {
        teardown(closeSessions = false)
    }

    /**
     * Stops listening and frees everything, sessions included. For when the
     * wake word is switched off or the service is going away — not for the
     * pause/resume churn of ordinary use.
     */
    fun release() {
        teardown(closeSessions = true)
    }

    private fun teardown(closeSessions: Boolean) {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, if (closeSessions) "release()" else "pause()", Throwable("called from"))
        }
        val oldRecorder: AudioRecord?
        var oldSessions: Sessions? = null
        val oldJob: Job?
        synchronized(stateLock) {
            running.set(false)
            generation++
            matcher.reset()
            oldRecorder = recorder
            oldJob = job
            recorder = null
            job = null
            if (closeSessions) {
                val doomed = sessions
                sessions = null
                if (doomed != null) {
                    if (sessionUsers > 0) {
                        // A listen loop is mid-inference against these. Closing
                        // them here would free models out from under a running
                        // OrtSession.run() — hand the close to whoever finishes
                        // last instead.
                        pendingClose = doomed
                    } else {
                        oldSessions = doomed
                    }
                }
            }
        }
        oldRecorder?.let {
            try {
                it.stop()
            } catch (_: IllegalStateException) {
                // Already stopped.
            }
            it.release()
        }
        oldSessions?.close()
        oldJob?.cancel()
    }

    fun isRunning(): Boolean = running.get()

    @SuppressLint("MissingPermission")
    private suspend fun initializeAndListen(generation: Int) {
        val env = OrtEnvironment.getEnvironment()
        /** Sessions this run holds a use-claim on; released in the finally. */
        var claimed: Sessions? = null
        var createdRecorder: AudioRecord? = null
        try {
            // Claim cached sessions under the very lock that reads them. Reading
            // first and claiming later leaves a window where sessionUsers is 0,
            // and a release() landing in that window frees the models this run
            // is about to adopt — which is exactly how it went wrong.
            synchronized(stateLock) {
                if (!running.get() || generation != this.generation) return
                sessions?.let {
                    claimed = it
                    sessionUsers++
                }
            }
            if (claimed == null) {
                // Cold start. The load is slow, so it happens off the lock; the
                // result is unpublished until the block below, so nothing else
                // can see it and no claim is needed yet.
                val loaded = loadSessions(env)
                synchronized(stateLock) {
                    if (!running.get() || generation != this.generation) {
                        loaded.close()
                        return
                    }
                    sessions = loaded
                    claimed = loaded
                    sessionUsers++
                }
            }
            val active = claimed ?: return
            // Bytes, not samples: 16-bit mono. A minimum-size buffer forces a
            // wakeup every 80 ms and leaves no room to absorb the burst of work
            // that re-arming the gate can cost, so ask for ~1.3 s instead. The
            // memory is irrelevant (~40 KB); the wakeup rate is not.
            val bufferSize = AudioRecord.getMinBufferSize(
                OnnxWakeWordPipeline.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(OnnxWakeWordPipeline.FRAME_SIZE * 2 * BUFFER_FRAMES)
            createdRecorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                OnnxWakeWordPipeline.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
            if (createdRecorder.state != AudioRecord.STATE_INITIALIZED) {
                throw IOException("AudioRecord failed to initialize")
            }

            synchronized(stateLock) {
                if (!running.get() || generation != this.generation) {
                    // Stopped while starting up. The claim is dropped by the
                    // finally below, which closes the models if a release()
                    // was waiting on this run to let go of them.
                    createdRecorder.release()
                    return
                }
                recorder = createdRecorder
            }
            scope.launch(Dispatchers.Main) { onStarted() }
            listen(
                generation = generation,
                env = env,
                localRecorder = createdRecorder,
                localSessions = active
            )
        } catch (e: Exception) {
            // Deliberately does not close the sessions: once published they are
            // owned by the reference count, and closing them here raced with a
            // concurrent run that had legitimately adopted them.
            createdRecorder?.release()
            fail(startupError(e), e, generation)
        } finally {
            if (claimed != null) releaseSessionUse()
        }
    }

    /**
     * How many times all three models have been read out of assets and turned
     * into ORT sessions. The whole point of [pause] is that this does not climb
     * with ordinary use, and a count is the only way to assert that from a test
     * — the alternative is timing a resume, which is inherently flaky.
     */
    @Volatile
    internal var sessionLoadCount = 0
        private set

    /**
     * Drops this loop's claim on the models, closing them if a [release]
     * asked for that while the loop was still running.
     */
    private fun releaseSessionUse() {
        val toClose: Sessions?
        synchronized(stateLock) {
            sessionUsers--
            toClose = if (sessionUsers == 0) pendingClose.also { pendingClose = null } else null
        }
        toClose?.close()
    }

    /** Loads all three models, closing any that succeeded if a later one fails. */
    private fun loadSessions(env: OrtEnvironment): Sessions {
        var mel: OrtSession? = null
        var embedding: OrtSession? = null
        try {
            mel = loadSession(env, "$modelAssetDir/melspectrogram.onnx")
            embedding = loadSession(env, "$modelAssetDir/embedding_model.onnx")
            val loaded = Sessions(mel, embedding, loadSession(env, "$modelAssetDir/$classifierModel"))
            sessionLoadCount++
            return loaded
        } catch (e: Exception) {
            mel?.close()
            embedding?.close()
            throw e
        }
    }

    /**
     * Loads one model with a thread configuration suited to a 12.5 Hz duty
     * cycle (issue #37). ONNX Runtime's defaults are tuned for throughput on a
     * busy server, and both of them are wrong here:
     *
     * - The intra-op thread count defaults to the core count, so three sessions
     *   spin up three pools and each 80 ms tick wakes several cores for a few
     *   milliseconds of work that fits comfortably on one.
     * - The pools busy-spin waiting for the next `run()`. At this duty cycle
     *   that is mostly burning CPU doing nothing, and it stops the SoC dropping
     *   to a low-power state between frames.
     *
     * Measured wall time barely moves (4.14 ms → 4.41 ms per frame at the
     * median), while p95 on the embedding stage improves a lot (11.35 ms →
     * 4.21 ms). That is expected — wall time is not core time, and this trades
     * a little median latency for far less total core time and no spinning.
     * The battery half of the claim needs batterystats, not the benchmark.
     *
     * Deliberately not set: `setOptimizationLevel(ORT_ENABLE_ALL)`, which is
     * already the default and would be a no-op. XNNPACK remains an untested
     * lever — contrary to a common claim it is *not* the default CPU EP in the
     * Android AAR (the shipped 1.26.0 exposes `addXnnpack` as explicit opt-in
     * and defaults to MLAS), so it is worth trying, but as its own measured
     * experiment.
     */
    private fun loadSession(env: OrtEnvironment, assetPath: String): OrtSession {
        val bytes = appContext.assets.open(assetPath).use { it.readBytes() }
        return OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(1)
            options.setInterOpNumThreads(1)
            options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
            options.addConfigEntry("session.intra_op.allow_spinning", "0")
            options.addConfigEntry("session.inter_op.allow_spinning", "0")
            env.createSession(bytes, options)
        }
    }

    private fun listen(
        generation: Int,
        env: OrtEnvironment,
        localRecorder: AudioRecord,
        localSessions: Sessions
    ) {
        // Read several 80 ms frames per wakeup rather than one. The pipeline
        // splits whatever it is handed back into frames, so this changes only
        // how often the CPU is woken: 3.1 times a second instead of 12.5.
        val block = ShortArray(OnnxWakeWordPipeline.FRAME_SIZE * READ_FRAMES)
        // Debug builds get a running account of what the listener is hearing.
        // "The wake word stopped working" is otherwise unfalsifiable from the
        // outside: a listener that holds the mic and scores nothing looks
        // exactly like one that is reading silence.
        val telemetry = if (BuildConfig.DEBUG) ListenTelemetry() else null
        val pipeline = OnnxWakeWordPipeline(
            env = env,
            melSession = localSessions.mel,
            embeddingSession = localSessions.embedding,
            classifierSession = localSessions.classifier,
            matcher = matcher,
            probe = telemetry
        )
        telemetry?.let {
            // The listener can be running, holding the mic and reading
            // plausible levels while still being fed the wrong thing — a
            // different physical mic, or a stream mangled by voice-comm
            // processing left behind by a call. Record what we actually got.
            val audio = appContext.getSystemService(android.content.Context.AUDIO_SERVICE)
                as android.media.AudioManager
            Log.d(
                TAG,
                "listening: threshold=${matcher.threshold()} readFrames=$READ_FRAMES " +
                    "rate=${localRecorder.sampleRate} ch=${localRecorder.channelCount} " +
                    "session=${localRecorder.audioSessionId} " +
                    "route=${describeRoute(localRecorder)} " +
                    "audioMode=${audio.mode} " +
                    "micMute=${audio.isMicrophoneMute} " +
                    "bufFrames=${localRecorder.bufferSizeInFrames}"
            )
        }
        // An audio capture loop should be scheduled like one. At the default
        // priority of a Dispatchers.IO thread this loop competes with ordinary
        // background work, and a preempted read is a dropped frame. Restored
        // afterwards because the thread goes back to the shared IO pool.
        val callerPriority = Process.getThreadPriority(Process.myTid())
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        try {
            localRecorder.startRecording()
            while (running.get()) {
                val count = localRecorder.read(block, 0, block.size)
                if (count < 0) {
                    if (running.get()) throw IOException("AudioRecord read failed: $count")
                    return
                }
                // A blocking read should never come back empty, but returning
                // to the top on 0 would spin the loop at full tilt if one ever
                // did. Wait out a frame instead.
                if (count == 0) {
                    Thread.sleep(FRAME_DURATION_MS)
                    continue
                }

                // Spelled out rather than `telemetry?.onRead(rmsDb(...))`. Both
                // skip the RMS scan in release — a safe call does not evaluate
                // its arguments when the receiver is null — but that is a
                // subtlety one refactor away from being lost, and this is a
                // per-frame path in an always-on listener.
                if (telemetry != null) {
                    telemetry.onRead(WakeWordGate.rmsDb(block, count))
                }

                // The state check is under the lock; the inference is not. A
                // batch of four frames, plus the replay a gate re-arm can
                // trigger, is tens of milliseconds of ONNX — long enough that
                // running it under the lock would stall a main-thread pause()
                // or release() behind it. The models cannot be closed while
                // this loop runs (see sessionUsers), so dropping the lock here
                // is safe.
                if (isStale(localRecorder, generation)) return
                if (!pipeline.feed(block, count)) continue

                // Re-check before acting: the listener may have been paused
                // while that batch was being processed, in which case this
                // detection belongs to a listener that no longer exists.
                val fire = synchronized(stateLock) {
                    if (isStaleLocked(localRecorder, generation)) {
                        false
                    } else {
                        running.set(false)
                        true
                    }
                }
                if (fire) scope.launch(Dispatchers.Main) { onDetected() }
                return
            }
        } catch (e: Exception) {
            if (running.get()) fail("Wake word listening stopped unexpectedly.", e, generation)
        } finally {
            Process.setThreadPriority(callerPriority)
        }
    }

    private fun describeRoute(recorder: AudioRecord): String {
        val device = recorder.routedDevice ?: return "none"
        val effects = buildList {
            if (android.media.audiofx.AcousticEchoCanceler.isAvailable()) add("aec-avail")
            if (android.media.audiofx.NoiseSuppressor.isAvailable()) add("ns-avail")
            if (android.media.audiofx.AutomaticGainControl.isAvailable()) add("agc-avail")
        }
        return "type=${device.type} product=${device.productName} address=${device.address} " +
            "effects=${effects.joinToString("|")}"
    }

    /**
     * Debug-only running summary of what the listener hears. Reports levels and
     * scores, never audio — nothing here could reconstruct what was said.
     */
    private class ListenTelemetry : OnnxWakeWordPipeline.PipelineProbe {
        private var reads = 0
        private var frames = 0
        private var gated = 0
        private var scored = 0
        private var peakScore = 0f
        private var peakRms = -200f
        private var quietestRms = 200f
        private var melMin = Float.MAX_VALUE
        private var melMax = -Float.MAX_VALUE
        private var melMean = 0f
        private var embMin = Float.MAX_VALUE
        private var embMax = -Float.MAX_VALUE
        private var embMean = 0f

        override fun onStageStats(
            stage: OnnxWakeWordPipeline.PipelineProbe.Stage,
            min: Float,
            mean: Float,
            max: Float
        ) {
            when (stage) {
                OnnxWakeWordPipeline.PipelineProbe.Stage.MEL -> {
                    if (min < melMin) melMin = min
                    if (max > melMax) melMax = max
                    melMean = mean
                }
                OnnxWakeWordPipeline.PipelineProbe.Stage.EMBEDDING -> {
                    if (min < embMin) embMin = min
                    if (max > embMax) embMax = max
                    embMean = mean
                }
                else -> Unit
            }
        }

        fun onRead(rmsDb: Float) {
            reads++
            if (rmsDb > peakRms) peakRms = rmsDb
            if (rmsDb < quietestRms) quietestRms = rmsDb
            if (reads % REPORT_EVERY_READS != 0) return
            // Locale.ROOT, not the default: on a decimal-comma device these
            // become "-70,5" and stop being greppable, which is the whole point
            // of the line.
            Log.d(
                TAG,
                "heard: rms ${fmt(quietestRms, 1)}..${fmt(peakRms, 1)} dBFS, " +
                    "frames=$frames scored=$scored gated=$gated peakScore=${fmt(peakScore, 3)} " +
                    "mel[${fmt(melMin, 2)}..${fmt(melMax, 2)} avg ${fmt(melMean, 2)}] " +
                    "emb[${fmt(embMin, 2)}..${fmt(embMax, 2)} avg ${fmt(embMean, 2)}]"
            )
            melMin = Float.MAX_VALUE
            melMax = -Float.MAX_VALUE
            embMin = Float.MAX_VALUE
            embMax = -Float.MAX_VALUE
            peakRms = -200f
            quietestRms = 200f
            peakScore = 0f
            frames = 0
            scored = 0
            gated = 0
        }

        override fun onMelRows(rows: Int) {
            frames++
        }

        override fun onGated() {
            gated++
        }

        override fun onScore(score: Float) {
            scored++
            if (score > peakScore) peakScore = score
        }

        private fun fmt(value: Float, decimals: Int): String =
            String.format(java.util.Locale.ROOT, "%.${decimals}f", value)

        private companion object {
            /** ~3 s at 4 frames per read. */
            const val REPORT_EVERY_READS = 10
        }
    }

    private fun isStale(localRecorder: AudioRecord, generation: Int): Boolean =
        synchronized(stateLock) { isStaleLocked(localRecorder, generation) }

    /** Caller must hold [stateLock]. */
    private fun isStaleLocked(localRecorder: AudioRecord, generation: Int): Boolean =
        !running.get() || recorder !== localRecorder || generation != this.generation

    private fun fail(message: String, cause: Exception? = null, generation: Int) {
        synchronized(stateLock) {
            if (generation != this.generation) return
        }
        lastError = message
        if (cause != null) Log.w(TAG, message, cause)
        // Full release, not pause: a failure here can mean a session that never
        // loaded or one that broke mid-run, so nothing cached is worth keeping.
        release()
        scope.launch(Dispatchers.Main) { onError(message) }
    }

    private fun startupError(error: Exception): String = when (error) {
        is SecurityException -> "Microphone permission is not available."
        is IOException -> "The bundled wake-word model could not be loaded."
        else -> "Wake word could not start."
    }

    companion object {
        const val WAKE_PHRASE = "hey gotcha"
        const val MODEL_ASSET_DIR = "openwakeword"
        const val CLASSIFIER_MODEL = "hey_gotcha.onnx"
        private const val TAG = "WakeWordDetector"

        /**
         * 80 ms frames drained per `AudioRecord.read`. Four cuts the wakeup
         * rate by 4× and costs up to 320 ms of extra trigger latency, on top of
         * the matcher's existing 160 ms patience.
         */
        private const val READ_FRAMES = 4

        /** ~1.3 s of audio: enough headroom that a slow frame cannot overrun the buffer. */
        private const val BUFFER_FRAMES = 16

        private const val FRAME_DURATION_MS = 80L
    }
}
