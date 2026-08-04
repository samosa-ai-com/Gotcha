package com.gotcha.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import com.gotcha.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * On-device Vosk keyword listener. It owns the microphone only while running.
 *
 * The model is loaded and the bundled assets are extracted on an IO coroutine:
 * the small model is tens of megabytes, so doing either on the service's main
 * thread would show up as a ball-service freeze right after boot.
 */
class WakeWordDetector(
    context: Context,
    private val scope: CoroutineScope,
    private val sensitivityProvider: () -> Float,
    private val onStarted: () -> Unit = {},
    private val onDetected: () -> Unit,
    private val onError: (String) -> Unit = {},
    private val modelAssetPath: String = MODEL_ASSET_PATH,
    private val phrase: String = WAKE_PHRASE
) {
    private val appContext = context.applicationContext
    private val stateLock = Any()
    private val running = AtomicBoolean(false)
    private var job: Job? = null
    private var recorder: AudioRecord? = null
    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var matcher = WakeWordMatcher(phrase, DEFAULT_SENSITIVITY)
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
            matcher = WakeWordMatcher(phrase, sensitivityProvider().coerceIn(0f, 1f))
            running.set(true)
            val startGeneration = generation
            job = scope.launch(Dispatchers.IO) { initializeAndListen(startGeneration) }
        }
        return true
    }

    fun stop() {
        val oldRecorder: AudioRecord?
        val oldRecognizer: Recognizer?
        val oldModel: Model?
        val oldJob: Job?
        synchronized(stateLock) {
            running.set(false)
            generation++
            matcher.reset()
            oldRecorder = recorder
            oldRecognizer = recognizer
            oldModel = model
            oldJob = job
            recorder = null
            recognizer = null
            model = null
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
        oldRecognizer?.close()
        oldModel?.close()
        oldJob?.cancel()
    }

    fun isRunning(): Boolean = running.get()

    private suspend fun initializeAndListen() {
        var createdModel: Model? = null
        var createdRecognizer: Recognizer? = null
        var createdRecorder: AudioRecord? = null
        try {
            val modelDir = copyModelToCache()
            createdModel = Model(modelDir.absolutePath)
            createdRecognizer = Recognizer(createdModel, SAMPLE_RATE, KEYWORD_GRAMMAR)
            val bufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE.toInt(),
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(MIN_BUFFER_BYTES)
            createdRecorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE.toInt(),
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
            if (createdRecorder.state != AudioRecord.STATE_INITIALIZED) {
                throw IOException("AudioRecord failed to initialize")
            }

            synchronized(stateLock) {
                if (!running.get()) {
                    closeResources(createdRecorder, createdRecognizer, createdModel)
                    return
                }
                model = createdModel
                recognizer = createdRecognizer
                recorder = createdRecorder
            }
            scope.launch(Dispatchers.Main) { onStarted() }
            listen(createdRecorder, createdRecognizer, bufferSize)
        } catch (e: Exception) {
            closeResources(createdRecorder, createdRecognizer, createdModel)
            fail(startupError(e), e)
        }
    }

    private fun listen(localRecorder: AudioRecord, localRecognizer: Recognizer, bufferSize: Int) {
        val buffer = ByteArray(bufferSize)
        try {
            localRecorder.startRecording()
            while (running.get()) {
                val count = localRecorder.read(buffer, 0, buffer.size)
                if (count < 0) {
                    if (running.get()) throw IOException("AudioRecord read failed: $count")
                    return
                }
                if (count == 0) continue

                var detected = false
                synchronized(stateLock) {
                    if (!running.get() || recorder !== localRecorder || recognizer !== localRecognizer) {
                        return
                    }
                    val isFinal = localRecognizer.acceptWaveForm(buffer, count)
                    val json = if (isFinal) localRecognizer.result else localRecognizer.partialResult
                    val text = JSONObject(json).optString(if (isFinal) "text" else "partial")
                    detected = if (isFinal) matcher.onFinal(text) else matcher.onPartial(text)
                    if (detected) running.set(false)
                }
                if (detected) {
                    scope.launch(Dispatchers.Main) { onDetected() }
                    return
                }
            }
        } catch (e: Exception) {
            if (running.get()) fail("Wake word listening stopped unexpectedly.", e)
        }
    }

    private fun fail(message: String, cause: Exception? = null) {
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

    private fun closeResources(
        oldRecorder: AudioRecord?,
        oldRecognizer: Recognizer?,
        oldModel: Model?
    ) {
        oldRecorder?.release()
        oldRecognizer?.close()
        oldModel?.close()
    }

    private fun copyModelToCache(): File {
        val root = File(appContext.cacheDir, "wake-word")
        val versionedRoot = File(root, BuildConfig.VERSION_CODE.toString())
        val destination = File(versionedRoot, modelAssetPath)
        if (File(destination, MODEL_READY_MARKER).isFile) return destination

        destination.deleteRecursively()
        copyAssetTree(modelAssetPath, destination)
        File(destination, MODEL_READY_MARKER).writeText("ok")

        // Old app versions may carry their own extracted copy. They are pure cache.
        root.listFiles()?.forEach { child ->
            if (child != versionedRoot) child.deleteRecursively()
        }
        return destination
    }

    private fun copyAssetTree(assetPath: String, destination: File) {
        val entries = appContext.assets.list(assetPath).orEmpty()
        if (entries.isEmpty()) {
            destination.parentFile?.mkdirs()
            appContext.assets.open(assetPath).use { input ->
                destination.outputStream().use { output -> input.copyTo(output) }
            }
        } else {
            destination.mkdirs()
            entries.forEach { entry -> copyAssetTree("$assetPath/$entry", File(destination, entry)) }
        }
    }

    companion object {
        const val WAKE_PHRASE = "gotcha"
        const val MODEL_ASSET_PATH = "vosk-model-small-en-us-0.15"
        const val DEFAULT_SENSITIVITY = 0.75f
        private const val SAMPLE_RATE = 16_000f
        private const val MIN_BUFFER_BYTES = 8_000
        private const val MODEL_READY_MARKER = ".ready"
        private const val KEYWORD_GRAMMAR = "[\"gotcha\", \"[unk]\"]"
        private const val TAG = "WakeWordDetector"
    }
}
