package com.gotcha.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** On-device Vosk keyword listener. It owns the microphone only while running. */
class WakeWordDetector(
    context: Context,
    private val scope: CoroutineScope,
    private val onDetected: () -> Unit,
    private val modelAssetPath: String = MODEL_ASSET_PATH,
    private val phrase: String = WAKE_PHRASE
) {
    private val appContext = context.applicationContext
    private val running = AtomicBoolean(false)
    private var job: Job? = null
    private var recorder: AudioRecord? = null
    private var model: Model? = null
    private var recognizer: Recognizer? = null

    @Synchronized
    fun start(): Boolean {
        if (running.get()) return true
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) return false

        return try {
            val modelDir = copyModelToCache()
            val createdModel = Model(modelDir.absolutePath)
            val createdRecognizer = Recognizer(createdModel, SAMPLE_RATE)
            val bufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE.toInt(),
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(SAMPLE_RATE.toInt())
            val createdRecorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE.toInt(),
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
            if (createdRecorder.state != AudioRecord.STATE_INITIALIZED) {
                createdRecorder.release()
                createdRecognizer.close()
                createdModel.close()
                return false
            }
            model = createdModel
            recognizer = createdRecognizer
            recorder = createdRecorder
            running.set(true)
            job = scope.launch(Dispatchers.IO) { listen(bufferSize) }
            true
        } catch (_: Exception) {
            stop()
            false
        }
    }

    @Synchronized
    fun stop() {
        running.set(false)
        recorder?.let {
            try { it.stop() } catch (_: IllegalStateException) { }
            it.release()
        }
        recorder = null
        recognizer?.close()
        recognizer = null
        model?.close()
        model = null
        job?.cancel()
        job = null
    }

    fun isRunning(): Boolean = running.get()

    private fun listen(bufferSize: Int) {
        val localRecorder = recorder ?: return
        val localRecognizer = recognizer ?: return
        val buffer = ByteArray(bufferSize)
        try {
            localRecorder.startRecording()
            while (running.get() && scope.isActive) {
                val count = localRecorder.read(buffer, 0, buffer.size)
                if (count > 0 && localRecognizer.acceptWaveForm(buffer, count)) {
                    val text = JSONObject(localRecognizer.result).optString("text")
                    if (containsPhrase(text)) {
                        running.set(false)
                        scope.launch(Dispatchers.Main) { onDetected() }
                        return
                    }
                }
            }
        } catch (_: Exception) {
            // A missing/incompatible model or a lost audio device must not kill the FGS.
        }
    }

    private fun containsPhrase(text: String): Boolean {
        val normalized = text.lowercase().trim()
        return normalized == phrase || normalized.contains(" $phrase ") ||
            normalized.startsWith("$phrase ") || normalized.endsWith(" $phrase")
    }

    private fun copyModelToCache(): File {
        val destination = File(appContext.cacheDir, "wake-word/$modelAssetPath")
        if (File(destination, ".ready").exists()) return destination
        destination.deleteRecursively()
        copyAssetTree(modelAssetPath, destination)
        File(destination, ".ready").createNewFile()
        return destination
    }

    private fun copyAssetTree(assetPath: String, destination: File) {
        val entries = appContext.assets.list(assetPath).orEmpty()
        destination.mkdirs()
        if (entries.isEmpty()) {
            appContext.assets.open(assetPath).use { input -> destination.outputStream().use(input::copyTo) }
        } else {
            entries.forEach { entry -> copyAssetTree("$assetPath/$entry", File(destination, entry)) }
        }
    }

    companion object {
        const val WAKE_PHRASE = "gotcha"
        const val MODEL_ASSET_PATH = "vosk-model-small-en-us-0.15"
        private const val SAMPLE_RATE = 16_000f
    }
}
