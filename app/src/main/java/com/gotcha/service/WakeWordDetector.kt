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
import android.util.Log
import androidx.core.content.ContextCompat
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
    private var melSession: OrtSession? = null
    private var embeddingSession: OrtSession? = null
    private var classifierSession: OrtSession? = null
    private var matcher = WakeWordMatcher(WakeWordMatcher.DEFAULT_SENSITIVITY)
    private var generation = 0

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
            job = scope.launch(Dispatchers.IO) { initializeAndListen(startGeneration) }
        }
        return true
    }

    fun stop() {
        val oldRecorder: AudioRecord?
        val oldMel: OrtSession?
        val oldEmbedding: OrtSession?
        val oldClassifier: OrtSession?
        val oldJob: Job?
        synchronized(stateLock) {
            running.set(false)
            generation++
            matcher.reset()
            oldRecorder = recorder
            oldMel = melSession
            oldEmbedding = embeddingSession
            oldClassifier = classifierSession
            oldJob = job
            recorder = null
            melSession = null
            embeddingSession = null
            classifierSession = null
            job = null
        }
        oldRecorder?.let {
            try {
                it.stop()
            } catch (_: IllegalStateException) {
                // Already stopped.
            }
            it.release()
        }
        closeSessions(oldMel, oldEmbedding, oldClassifier)
        oldJob?.cancel()
    }

    fun isRunning(): Boolean = running.get()

    @SuppressLint("MissingPermission")
    private suspend fun initializeAndListen(generation: Int) {
        val env = OrtEnvironment.getEnvironment()
        var createdMel: OrtSession? = null
        var createdEmbedding: OrtSession? = null
        var createdClassifier: OrtSession? = null
        var createdRecorder: AudioRecord? = null
        try {
            createdMel = loadSession(env, "$modelAssetDir/melspectrogram.onnx")
            createdEmbedding = loadSession(env, "$modelAssetDir/embedding_model.onnx")
            createdClassifier = loadSession(env, "$modelAssetDir/$classifierModel")
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
                    closeSessions(createdMel, createdEmbedding, createdClassifier)
                    createdRecorder.release()
                    return
                }
                melSession = createdMel
                embeddingSession = createdEmbedding
                classifierSession = createdClassifier
                recorder = createdRecorder
            }
            scope.launch(Dispatchers.Main) { onStarted() }
            listen(
                generation = generation,
                env = env,
                localRecorder = createdRecorder,
                localMel = createdMel,
                localEmbedding = createdEmbedding,
                localClassifier = createdClassifier
            )
        } catch (e: Exception) {
            closeSessions(createdMel, createdEmbedding, createdClassifier)
            createdRecorder?.release()
            fail(startupError(e), e, generation)
        }
    }

    private fun loadSession(env: OrtEnvironment, assetPath: String): OrtSession {
        val bytes = appContext.assets.open(assetPath).use { it.readBytes() }
        return env.createSession(bytes)
    }

    private fun listen(
        generation: Int,
        env: OrtEnvironment,
        localRecorder: AudioRecord,
        localMel: OrtSession,
        localEmbedding: OrtSession,
        localClassifier: OrtSession
    ) {
        // Read several 80 ms frames per wakeup rather than one. The pipeline
        // splits whatever it is handed back into frames, so this changes only
        // how often the CPU is woken: 3.1 times a second instead of 12.5.
        val block = ShortArray(OnnxWakeWordPipeline.FRAME_SIZE * READ_FRAMES)
        val pipeline = OnnxWakeWordPipeline(
            env = env,
            melSession = localMel,
            embeddingSession = localEmbedding,
            classifierSession = localClassifier,
            matcher = matcher
        )
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

                var detected = false
                synchronized(stateLock) {
                    if (!running.get() || recorder !== localRecorder || generation != this.generation) {
                        return
                    }
                    if (pipeline.feed(block, count)) {
                        detected = true
                        running.set(false)
                    }
                }
                if (detected) {
                    scope.launch(Dispatchers.Main) { onDetected() }
                    return
                }
            }
        } catch (e: Exception) {
            if (running.get()) fail("Wake word listening stopped unexpectedly.", e, generation)
        }
    }

    private fun fail(message: String, cause: Exception? = null, generation: Int) {
        synchronized(stateLock) {
            if (generation != this.generation) return
        }
        lastError = message
        if (cause != null) Log.w(TAG, message, cause)
        stop()
        scope.launch(Dispatchers.Main) { onError(message) }
    }

    private fun startupError(error: Exception): String = when (error) {
        is SecurityException -> "Microphone permission is not available."
        is IOException -> "The bundled wake-word model could not be loaded."
        else -> "Wake word could not start."
    }

    private fun closeSessions(mel: OrtSession?, embedding: OrtSession?, classifier: OrtSession?) {
        mel?.close()
        embedding?.close()
        classifier?.close()
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
