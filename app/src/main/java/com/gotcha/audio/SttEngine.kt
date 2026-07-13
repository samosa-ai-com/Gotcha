package com.gotcha.audio

import android.content.Context
import android.content.Intent
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Result of a single hands-free listening turn ([SttEngine.listenOnceAndroid]). */
sealed class SttOutcome {
    data class Text(val text: String) : SttOutcome()
    data class Error(val code: Int) : SttOutcome() {
        /** True for silence/no-speech errors that a continuous loop should just retry. */
        val isBenign: Boolean
            get() = code == SpeechRecognizer.ERROR_NO_MATCH ||
                code == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
    }
}

/**
 * Speech-to-Text engine supporting two providers.
 * Both use a push-to-talk pattern: startRecording() / stopRecording().
 *
 * - API provider: records audio via MediaRecorder, transcribes via API
 * - Android provider: uses SpeechRecognizer (live recognition)
 */
class SttEngine(
    private val context: Context,
    apiBaseUrl: String = "",
    apiKey: String = ""
) {
    // API STT state
    private var audioApi: AudioApi? = if (apiBaseUrl.isNotBlank()) AudioApi(apiBaseUrl, apiKey) else null
    private var currentRecorder: MediaRecorder? = null
    private var currentAudioFile: File? = null

    // Android STT state
    private val mainHandler = Handler(Looper.getMainLooper())
    private var currentRecognizer: SpeechRecognizer? = null
    private var listenGate: CompletableDeferred<String>? = null
    private var onceGate: CompletableDeferred<SttOutcome>? = null

    /** The models available from the API (empty if provider is Android). */
    var apiSttModels: List<AudioModel> = emptyList()
        private set

    /** Discover STT models from the API. Call after construction. */
    suspend fun refreshApiModels(): List<AudioModel> = withContext(Dispatchers.IO) {
        val api = audioApi ?: return@withContext emptyList<AudioModel>()
        val models = api.listAudioModels()
        apiSttModels = models.filter { it.category == ModelCategory.STT }
        apiSttModels
    }

    /** Set API config at runtime. */
    fun configureApi(baseUrl: String, apiKey: String) {
        audioApi = if (baseUrl.isNotBlank()) AudioApi(baseUrl, apiKey) else null
    }

    // ---- API STT (MediaRecorder + transcription) ----

    /** Start recording audio for API-based STT. */
    fun startRecording(): Boolean {
        return try {
            currentAudioFile = File(context.cacheDir, "stt_recording_${System.currentTimeMillis()}.m4a")
            val recorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioChannels(1)
                setAudioSamplingRate(16000)
                setOutputFile(currentAudioFile!!.absolutePath)
                prepare()
                start()
            }
            currentRecorder = recorder
            true
        } catch (_: Exception) { false }
    }

    /** Stop recording and return the audio file for API STT. */
    fun stopRecording(): File? {
        return try {
            currentRecorder?.apply {
                stop()
                release()
            }
            currentRecorder = null
            currentAudioFile
        } catch (_: Exception) {
            currentRecorder = null
            null
        }
    }

    /** Transcribe audio using the API provider. */
    suspend fun transcribeApi(audioFile: File, model: String): Result<String> = withContext(Dispatchers.IO) {
        val api = audioApi ?: return@withContext Result.failure(Exception("API not configured"))
        api.transcribe(audioFile, model)
    }

    // ---- Android STT (SpeechRecognizer) ----

    /** Start Android SpeechRecognizer. User speaks, then calls stopAndroidListening(). */
    fun startAndroidListening(): Boolean {
        if (currentRecognizer != null) return false
        val gate = CompletableDeferred<String>()
        listenGate = gate
        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        currentRecognizer = recognizer
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                gate.complete("")
                currentRecognizer?.destroy()
                currentRecognizer = null
            }
            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                gate.complete(matches?.firstOrNull() ?: "")
                currentRecognizer?.destroy()
                currentRecognizer = null
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, java.util.Locale.getDefault())
        }
        recognizer.startListening(intent)
        return true
    }

    /** Stop Android SpeechRecognizer and return the recognized text. */
    suspend fun stopAndroidListening(): String {
        currentRecognizer?.stopListening()
        // The stopListening() call triggers onResults which completes the gate
        val result = listenGate?.await() ?: ""
        listenGate = null
        return result
    }

    /**
     * Listen for a single utterance hands-free: the recognizer endpoints on
     * its own (no stop call needed) and this returns the transcript or the
     * SpeechRecognizer error code. Used by the voice-call loop.
     */
    suspend fun listenOnceAndroid(): SttOutcome {
        if (currentRecognizer != null || onceGate != null) {
            return SttOutcome.Error(SpeechRecognizer.ERROR_RECOGNIZER_BUSY)
        }
        val gate = CompletableDeferred<SttOutcome>()
        onceGate = gate
        // SpeechRecognizer must be created and driven from the main thread.
        withContext(Dispatchers.Main) {
            try {
                val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
                currentRecognizer = recognizer
                recognizer.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onError(error: Int) {
                        gate.complete(SttOutcome.Error(error))
                        currentRecognizer?.destroy()
                        currentRecognizer = null
                    }
                    override fun onResults(results: Bundle?) {
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        gate.complete(SttOutcome.Text(matches?.firstOrNull() ?: ""))
                        currentRecognizer?.destroy()
                        currentRecognizer = null
                    }
                    override fun onPartialResults(partialResults: Bundle?) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, java.util.Locale.getDefault())
                }
                recognizer.startListening(intent)
            } catch (_: Exception) {
                gate.complete(SttOutcome.Error(SpeechRecognizer.ERROR_CLIENT))
                currentRecognizer = null
            }
        }
        return try {
            gate.await()
        } finally {
            onceGate = null
        }
    }

    /**
     * Abort an in-flight [listenOnceAndroid] (pause/end of a call). Safe to
     * call from any thread; a suspended caller unblocks with [ERROR_CANCELLED].
     */
    fun cancelListening() {
        onceGate?.complete(SttOutcome.Error(ERROR_CANCELLED))
        mainHandler.post {
            try {
                currentRecognizer?.cancel()
                currentRecognizer?.destroy()
            } catch (_: Exception) { }
            currentRecognizer = null
        }
    }

    companion object {
        /** Synthetic error code: listening was cancelled by pause/end, not by the recognizer. */
        const val ERROR_CANCELLED = -100
    }
}
