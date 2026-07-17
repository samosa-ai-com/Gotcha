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

    // Continuous push-to-talk state
    private var isContinuousListening = false
    private val continuousTranscript = StringBuilder()

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
        if (isContinuousListening) return false
        listenGate = CompletableDeferred<String>()
        isContinuousListening = true
        continuousTranscript.clear()

        mainHandler.post { startContinuousListeningCycle() }
        return true
    }

    private fun startContinuousListeningCycle() {
        if (!isContinuousListening) return
        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        currentRecognizer = recognizer
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                currentRecognizer?.destroy()
                currentRecognizer = null
                if (isContinuousListening) {
                    mainHandler.post { startContinuousListeningCycle() }
                } else {
                    listenGate?.complete(continuousTranscript.toString())
                }
            }
            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull() ?: ""
                if (text.isNotBlank()) {
                    if (continuousTranscript.isNotEmpty()) continuousTranscript.append(" ")
                    continuousTranscript.append(text)
                }
                currentRecognizer?.destroy()
                currentRecognizer = null
                if (isContinuousListening) {
                    mainHandler.post { startContinuousListeningCycle() }
                } else {
                    listenGate?.complete(continuousTranscript.toString())
                }
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, java.util.Locale.getDefault())

            // Prevent auto-stopping on pauses to mimic API-based continuous recording
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 100000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 100000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 100000L)
        }
        try {
            recognizer.startListening(intent)
        } catch (_: Exception) {
            isContinuousListening = false
            listenGate?.complete(continuousTranscript.toString())
        }
    }

    /** Stop Android SpeechRecognizer and return the recognized text. */
    suspend fun stopAndroidListening(): String {
        isContinuousListening = false
        currentRecognizer?.stopListening()
        // The stopListening() call triggers onResults which completes the gate
        val result = listenGate?.await() ?: continuousTranscript.toString()
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

    // ---- Provider-agnostic PTT ----

    /** Start listening with the current provider. Returns false on failure. */
    fun startListening(provider: AudioProvider): Boolean {
        return when (provider) {
            AudioProvider.ANDROID -> startAndroidListening()
            AudioProvider.API -> startRecording()
            AudioProvider.NONE -> false
        }
    }

    /**
     * Stop listening and return the transcript. Provider-agnostic.
     * For ANDROID: returns the recognized text.
     * For API: stops the recorder, transcribes, returns the text or error.
     */
    suspend fun stopListeningAndTranscribe(provider: AudioProvider, model: String): Result<String> {
        return when (provider) {
            AudioProvider.ANDROID -> {
                val text = stopAndroidListening()
                if (text.isBlank()) {
                    Result.failure(Exception("No speech detected"))
                } else {
                    Result.success(text)
                }
            }
            AudioProvider.API -> {
                val file = stopRecording()
                if (file == null) {
                    return Result.failure(Exception("Recording failed"))
                }
                transcribeApi(file, model)
            }
            AudioProvider.NONE -> Result.failure(Exception("STT not configured"))
        }
    }

    /**
     * Abort an active listen (pause/end of a call). Handles both providers.
     * Safe to call from any thread.
     */
    fun cancelListening(provider: AudioProvider) {
        when (provider) {
            AudioProvider.ANDROID -> {
                isContinuousListening = false
                onceGate?.complete(SttOutcome.Error(ERROR_CANCELLED))
                listenGate?.complete("")
                listenGate = null
                mainHandler.post {
                    try {
                        currentRecognizer?.cancel()
                        currentRecognizer?.destroy()
                    } catch (_: Exception) { }
                    currentRecognizer = null
                }
            }
            AudioProvider.API -> {
                try {
                    currentRecorder?.apply {
                        stop()
                        release()
                    }
                } catch (_: Exception) { }
                currentRecorder = null
                currentAudioFile = null
            }
            AudioProvider.NONE -> {}
        }
    }

    companion object {
        /** Synthetic error code: listening was cancelled by pause/end, not by the recognizer. */
        const val ERROR_CANCELLED = -100
    }
}
