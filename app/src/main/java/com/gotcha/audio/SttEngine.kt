package com.gotcha.audio

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.gotcha.i18n.Language
import com.gotcha.util.HumanReadableError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
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
    apiKey: String = "",
    private val onUnauthorized: (() -> Unit)? = null
) {
    // API STT state
    private var audioApi: AudioApi? = if (apiBaseUrl.isNotBlank()) {
        AudioApi(apiBaseUrl, apiKey, onUnauthorized = onUnauthorized)
    } else {
        null
    }
    private var currentRecorder: MediaRecorder? = null
    private var currentAudioFile: File? = null

    /** Set by [cancelListening] to abort an in-flight VAD (hands-free) recording. */
    @Volatile
    private var vadStopRequested = false

    // Audio muting state
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
    private var isMutedForRestart = false

    // Android STT state
    private val mainHandler = Handler(Looper.getMainLooper())
    private var currentRecognizer: SpeechRecognizer? = null
    private var listenGate: CompletableDeferred<String>? = null
    private var onceGate: CompletableDeferred<SttOutcome>? = null

    // Continuous push-to-talk state
    private var isContinuousListening = false
    private val continuousTranscript = StringBuilder()

    /** Latest partial result from the current recognizer segment (not yet finalized). */
    private var currentPartialResult = ""

    /** Language for the current/next Android recognizer session. */
    private var currentLanguage = Language.ENGLISH

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
        audioApi = if (baseUrl.isNotBlank()) {
            AudioApi(baseUrl, apiKey, onUnauthorized = onUnauthorized)
        } else {
            null
        }
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
    suspend fun transcribeApi(
        audioFile: File,
        model: String,
        language: String = "",
        contentType: String = "audio/m4a"
    ): Result<String> = withContext(Dispatchers.IO) {
        val api = audioApi ?: return@withContext Result.failure(Exception("API not configured"))
        try {
            api.transcribe(
                audioFile = audioFile,
                model = model,
                language = language,
                contentType = contentType
            )
        } finally {
            audioFile.delete()
        }
    }

    /**
     * Listen for a single hands-free utterance and return its transcript.
     *
     * Unlike the push-to-talk path ([startListening] / [stopListeningAndTranscribe]),
     * this ends on its own once the user has finished speaking:
     *
     * - Android provider: the platform's [SpeechRecognizer] endpoints on end of
     *   speech (with a best-effort silence hint of [silenceTimeoutMs]).
     * - API provider: raw PCM is captured via [AudioRecord] and a local
     *   [SilenceDetector] stops the recording after [silenceTimeoutMs] of
     *   trailing silence; the resulting WAV is transcribed through the API.
     */
    suspend fun listenForUtterance(
        provider: AudioProvider,
        model: String,
        language: Language = Language.ENGLISH,
        silenceTimeoutMs: Long
    ): Result<String> = when {
        provider == AudioProvider.ANDROID -> {
            when (val outcome = listenOnceAndroid(language, silenceTimeoutMs)) {
                is SttOutcome.Text ->
                    if (outcome.text.isNotBlank()) {
                        Result.success(outcome.text)
                    } else {
                        Result.failure(Exception("No speech detected"))
                    }
                is SttOutcome.Error -> Result.failure(Exception("Speech recognition failed: ${outcome.code}"))
            }
        }
        provider.isApiBased() -> withContext(Dispatchers.IO) {
            val wav = recordPcmWithVad(silenceTimeoutMs)
            if (wav == null) {
                Result.failure(Exception("No speech detected"))
            } else {
                transcribeApi(wav, model, language.iso639, contentType = "audio/wav")
            }
        }
        else -> Result.failure(Exception("STT not configured"))
    }

    /**
     * Records up to [silenceTimeoutMs] of trailing silence after speech using
     * [AudioRecord] (16 kHz mono PCM-16) and returns a WAV file, or null when
     * the user never spoke. Aborts when [cancelListening] is called.
     */
    @SuppressLint("MissingPermission")
    private fun recordPcmWithVad(silenceTimeoutMs: Long): File? {
        val sampleRate = 16_000
        val windowShorts = 3_200 // 200 ms windows
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuffer, windowShorts * 2)
        )
        if (recorder.state != AudioRecord.STATE_INITIALIZED) return null

        vadStopRequested = false
        val detector = SilenceDetector(silenceTimeoutMs)
        val shortBuf = ShortArray(windowShorts)
        val byteBuf = ByteArray(windowShorts * 2)
        val pcm = ByteArrayOutputStream()
        val startMs = System.currentTimeMillis()
        var result: File? = null
        try {
            recorder.startRecording()
            while (!vadStopRequested) {
                val count = recorder.read(shortBuf, 0, shortBuf.size)
                if (count < 0) break
                if (count == 0) continue
                for (i in 0 until count) {
                    val sample = shortBuf[i].toInt()
                    byteBuf[i * 2] = (sample and 0xFF).toByte()
                    byteBuf[i * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
                }
                pcm.write(byteBuf, 0, count * 2)
                val now = System.currentTimeMillis()
                val decision = detector.update(SilenceDetector.rmsDb(shortBuf, count), now)
                if (decision != SilenceDetector.Decision.CONTINUE ||
                    now - startMs > MAX_VAD_RECORDING_MS
                ) {
                    break
                }
            }
            if (detector.speechDetected && pcm.size() > 0) {
                result = writeWav(context.cacheDir, pcm.toByteArray(), sampleRate)
            }
        } catch (e: Exception) {
            // A permissions/security failure, OOM, or recorder error must not
            // look like "no speech" without a trace — log it for debugging.
            Log.w(TAG, "VAD recording failed; treating as no speech", e)
            result = null
        } finally {
            try {
                recorder.stop()
            } catch (_: Exception) {
                // Never started or already stopped.
            }
            recorder.release()
            vadStopRequested = false
        }
        return result
    }

    // ---- Android STT (SpeechRecognizer) ----

    /** Start Android SpeechRecognizer. User speaks, then calls stopAndroidListening(). */
    fun startAndroidListening(language: Language = Language.ENGLISH): Boolean {
        if (isContinuousListening) return false
        currentLanguage = language
        listenGate = CompletableDeferred<String>()
        isContinuousListening = true
        continuousTranscript.clear()
        currentPartialResult = ""

        muteBeep()
        mainHandler.post { startContinuousListeningCycle() }
        return true
    }

    private fun startContinuousListeningCycle() {
        if (!isContinuousListening) {
            // Fix race: if stop was called between cycles, complete the gate now
            listenGate?.complete(buildFinalTranscript())
            return
        }
        currentPartialResult = "" // Reset for new segment
        var recognizer = currentRecognizer
        if (recognizer == null) {
            val newRec = SpeechRecognizer.createSpeechRecognizer(context)
            currentRecognizer = newRec
            recognizer = newRec
            newRec.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    Log.d(TAG, "onReadyForSpeech")
                }
                override fun onBeginningOfSpeech() { Log.d(TAG, "onBeginningOfSpeech") }
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() { Log.d(TAG, "onEndOfSpeech") }
                override fun onError(error: Int) {
                    val humanMsg = HumanReadableError.fromSpeechRecognizerCode(error)
                    Log.d(TAG, "onError: $error ($humanMsg)")
                    if (error == SpeechRecognizer.ERROR_CLIENT ||
                        error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||
                        error == SpeechRecognizer.ERROR_SERVER
                    ) {
                        try {
                            currentRecognizer?.destroy()
                        } catch (_: Exception) {}
                        currentRecognizer = null
                    }
                    if (isContinuousListening) {
                        mainHandler.post { startContinuousListeningCycle() }
                    } else {
                        unmuteBeep()
                        listenGate?.complete(buildFinalTranscript())
                    }
                }
                override fun onResults(results: Bundle?) {
                    Log.d(TAG, "onResults")
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull() ?: ""
                    if (text.isNotBlank()) {
                        if (continuousTranscript.isNotEmpty()) continuousTranscript.append(" ")
                        continuousTranscript.append(text)
                    }
                    currentPartialResult = "" // Final result supersedes partial
                    if (isContinuousListening) {
                        mainHandler.post { startContinuousListeningCycle() }
                    } else {
                        unmuteBeep()
                        listenGate?.complete(buildFinalTranscript())
                    }
                }
                override fun onPartialResults(partialResults: Bundle?) {
                    Log.d(TAG, "onPartialResults")
                    val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull() ?: ""
                    if (text.isNotBlank()) {
                        currentPartialResult = text
                    }
                }
                override fun onEvent(eventType: Int, params: Bundle?) { Log.d(TAG, "onEvent: $eventType") }

                override fun onSegmentResults(segmentResults: Bundle) {
                    Log.d(TAG, "onSegmentResults")
                    val matches = segmentResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull() ?: ""
                    if (text.isNotBlank()) {
                        if (continuousTranscript.isNotEmpty()) continuousTranscript.append(" ")
                        continuousTranscript.append(text)
                    }
                    currentPartialResult = ""
                }

                override fun onEndOfSegmentedSession() {
                    Log.d(TAG, "onEndOfSegmentedSession")
                    if (isContinuousListening) {
                        mainHandler.post { startContinuousListeningCycle() }
                    } else {
                        unmuteBeep()
                        listenGate?.complete(buildFinalTranscript())
                    }
                }
            })
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, currentLanguage.bcp47)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)

            // Prevent auto-stopping on pauses to mimic API-based continuous recording
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 20000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 20000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 20000L)
            putExtra("android.speech.extra.DICTATION_MODE", true)
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, true)
            }
        }
        try {
            muteBeep()
            recognizer?.startListening(intent)
        } catch (_: Exception) {
            unmuteBeep()
            isContinuousListening = false
            listenGate?.complete(buildFinalTranscript())
        }
    }

    /** Stop Android SpeechRecognizer and return the recognized text. */
    suspend fun stopAndroidListening(): String {
        isContinuousListening = false
        val rec = currentRecognizer
        if (rec != null) {
            rec.stopListening()
            // Wait for onResults to fire and complete the gate, with a timeout
            // to avoid hanging if the recognizer is unresponsive or between cycles.
            val gateResult = kotlinx.coroutines.withTimeoutOrNull(3000L) {
                listenGate?.await()
            }
            if (gateResult != null) {
                unmuteBeep()
                listenGate = null
                return gateResult
            }
            // Timed out — force cleanup
            mainHandler.post {
                unmuteBeep()
                currentRecognizer?.cancel()
                currentRecognizer?.destroy()
                currentRecognizer = null
            }
        }
        // Recognizer was null (between cycles) or timed out — return what we have
        val result = buildFinalTranscript()
        unmuteBeep()
        listenGate?.complete(result)
        listenGate = null
        return result
    }

    /**
     * Build the final transcript by appending the current in-flight partial
     * result (if any) to the accumulated finalized segments.
     */
    private fun buildFinalTranscript(): String {
        val base = continuousTranscript.toString()
        val partial = currentPartialResult
        currentPartialResult = ""
        if (partial.isBlank()) return base
        return if (base.isNotBlank()) "$base $partial" else partial
    }

    /**
     * Listen for a single utterance hands-free: the recognizer endpoints on
     * its own (no stop call needed) and this returns the transcript or the
     * SpeechRecognizer error code. Used by the voice-call loop.
     */
    suspend fun listenOnceAndroid(
        language: Language = Language.ENGLISH,
        silenceTimeoutMs: Long = 2000L
    ): SttOutcome {
        if (currentRecognizer != null || onceGate != null) {
            return SttOutcome.Error(SpeechRecognizer.ERROR_RECOGNIZER_BUSY)
        }
        val gate = CompletableDeferred<SttOutcome>()
        onceGate = gate
        // SpeechRecognizer must be created and driven from the main thread.
        withContext(Dispatchers.Main) {
            try {
                muteBeep()
                val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
                currentRecognizer = recognizer
                recognizer.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onError(error: Int) {
                        unmuteBeep()
                        gate.complete(SttOutcome.Error(error))
                        currentRecognizer?.destroy()
                        currentRecognizer = null
                    }
                    override fun onResults(results: Bundle?) {
                        unmuteBeep()
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        gate.complete(SttOutcome.Text(matches?.firstOrNull() ?: ""))
                        currentRecognizer?.destroy()
                        currentRecognizer = null
                    }
                    override fun onPartialResults(partialResults: Bundle?) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                    override fun onSegmentResults(segmentResults: Bundle) {}
                    override fun onEndOfSegmentedSession() {}
                })
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, language.bcp47)
                    // Best-effort end-of-speech hint; the platform may ignore it.
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, silenceTimeoutMs)
                }
                recognizer.startListening(intent)
            } catch (_: Exception) {
                unmuteBeep()
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
    fun startListening(provider: AudioProvider, language: Language = Language.ENGLISH): Boolean {
        return when {
            provider == AudioProvider.ANDROID -> startAndroidListening(language)
            provider.isApiBased() -> startRecording()
            else -> false
        }
    }

    /**
     * Stop recording/listening and transcribe audio using [provider] and [model].
     * For API: stops the recorder, transcribes, returns the text or error.
     */
    suspend fun stopListeningAndTranscribe(
        provider: AudioProvider,
        model: String,
        language: String = ""
    ): Result<String> {
        return when {
            provider == AudioProvider.ANDROID -> {
                val text = stopAndroidListening()
                if (text.isBlank()) {
                    Result.failure(Exception("No speech detected"))
                } else {
                    Result.success(text)
                }
            }
            provider.isApiBased() -> {
                val file = stopRecording()
                if (file == null) {
                    return Result.failure(Exception("Recording failed"))
                }
                transcribeApi(file, model, language)
            }
            else -> Result.failure(Exception("STT not configured"))
        }
    }

    /**
     * Abort an active listen (pause/end of a call). Handles both providers.
     * Safe to call from any thread.
     */
    fun cancelListening(provider: AudioProvider) {
        when {
            provider == AudioProvider.ANDROID -> {
                isContinuousListening = false
                currentPartialResult = ""
                onceGate?.complete(SttOutcome.Error(ERROR_CANCELLED))
                listenGate?.complete("")
                listenGate = null
                mainHandler.post {
                    unmuteBeep()
                    try {
                        currentRecognizer?.cancel()
                        currentRecognizer?.destroy()
                    } catch (_: Exception) { }
                    currentRecognizer = null
                }
            }
            provider.isApiBased() -> {
                vadStopRequested = true
                try {
                    currentRecorder?.apply {
                        stop()
                        release()
                    }
                } catch (_: Exception) { }
                currentRecorder = null
                currentAudioFile = null
            }
            else -> {}
        }
    }

    private fun muteBeep() {
        if (!isMutedForRestart) {
            try {
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
                audioManager.adjustStreamVolume(AudioManager.STREAM_SYSTEM, AudioManager.ADJUST_MUTE, 0)
                audioManager.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, AudioManager.ADJUST_MUTE, 0)
                audioManager.adjustStreamVolume(AudioManager.STREAM_RING, AudioManager.ADJUST_MUTE, 0)
            } catch (_: Exception) {}
            isMutedForRestart = true
        }
    }

    private fun unmuteBeep() {
        if (isMutedForRestart) {
            try {
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
                audioManager.adjustStreamVolume(AudioManager.STREAM_SYSTEM, AudioManager.ADJUST_UNMUTE, 0)
                audioManager.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, AudioManager.ADJUST_UNMUTE, 0)
                audioManager.adjustStreamVolume(AudioManager.STREAM_RING, AudioManager.ADJUST_UNMUTE, 0)
            } catch (_: Exception) {}
            isMutedForRestart = false
        }
    }

    companion object {
        private const val TAG = "SttEngine"

        /** Synthetic error code: listening was cancelled by pause/end, not by the recognizer. */
        const val ERROR_CANCELLED = -100

        /** Hard cap on a single hands-free VAD recording (90 s). */
        private const val MAX_VAD_RECORDING_MS = 90_000L

        /**
         * True when [error] is the benign "the user stayed quiet" outcome rather
         * than a real STT failure (network, permissions, recognizer busy, ...).
         * A hands-free listen loop must treat silence as a quiet retry signal and
         * surface genuine failures to the user instead of silently dying.
         */
        fun isBenignSttError(error: Throwable?): Boolean {
            if (error == null) return true
            val message = error.message ?: return false
            if (message == NO_SPEECH_DETECTED) return true
            val code = message.removePrefix(SPEECH_FAILED_PREFIX).toIntOrNull()
            return code != null && SttOutcome.Error(code).isBenign
        }

        private const val NO_SPEECH_DETECTED = "No speech detected"
        private const val SPEECH_FAILED_PREFIX = "Speech recognition failed: "
    }
}

private fun writeWav(cacheDir: File, pcm: ByteArray, sampleRate: Int): File {
    // UUID suffix so two recordings created in the same millisecond never collide.
    val file = File(
        cacheDir,
        "stt_hands_free_${System.currentTimeMillis()}_${java.util.UUID.randomUUID().toString().take(8)}.wav"
    )
    file.outputStream().use { out ->
        val dataSize = pcm.size
        val header = ByteArray(44)
        header[0] = 'R'.code.toByte()
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()
        writeIntLittleEndian(header, 4, 36 + dataSize)
        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte()
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()
        writeIntLittleEndian(header, 16, 16)
        writeShortLittleEndian(header, 20, 1)
        writeShortLittleEndian(header, 22, 1)
        writeIntLittleEndian(header, 24, sampleRate)
        writeIntLittleEndian(header, 28, sampleRate * 2)
        writeShortLittleEndian(header, 32, 2)
        writeShortLittleEndian(header, 34, 16)
        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()
        writeIntLittleEndian(header, 40, dataSize)
        out.write(header)
        out.write(pcm)
    }
    return file
}

private fun writeIntLittleEndian(bytes: ByteArray, offset: Int, value: Int) {
    bytes[offset] = (value and 0xFF).toByte()
    bytes[offset + 1] = ((value shr 8) and 0xFF).toByte()
    bytes[offset + 2] = ((value shr 16) and 0xFF).toByte()
    bytes[offset + 3] = ((value shr 24) and 0xFF).toByte()
}

private fun writeShortLittleEndian(bytes: ByteArray, offset: Int, value: Int) {
    bytes[offset] = (value and 0xFF).toByte()
    bytes[offset + 1] = ((value shr 8) and 0xFF).toByte()
}
