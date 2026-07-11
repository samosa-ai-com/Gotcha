package com.gotcha.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Text-to-Speech engine supporting two providers:
 * - Android built-in (TextToSpeech API)
 * - API-based (OpenAI-compatible /v1/audio/speech)
 */
class TtsEngine(
    private val context: Context,
    apiBaseUrl: String = "",
    apiKey: String = ""
) {
    private var audioApi: AudioApi? = if (apiBaseUrl.isNotBlank()) AudioApi(apiBaseUrl, apiKey) else null
    private var androidTts: TextToSpeech? = null
    private var androidReady = false
    private var ttsInitGate = CompletableDeferred<Boolean>()

    /** The models available from the API (empty if provider is Android). */
    var apiTtsModels: List<AudioModel> = emptyList()
        private set

    init {
        try {
            androidTts = TextToSpeech(context) { status ->
                androidReady = (status == TextToSpeech.SUCCESS)
                ttsInitGate.complete(androidReady)
            }
        } catch (_: Exception) {
            ttsInitGate.complete(false)
        }
    }

    /** Discover TTS models from the API. Call after construction. */
    suspend fun refreshApiModels(): List<AudioModel> = withContext(Dispatchers.IO) {
        val api = audioApi ?: return@withContext emptyList<AudioModel>()
        val models = api.listAudioModels()
        apiTtsModels = models.filter { it.category == ModelCategory.TTS }
        apiTtsModels
    }

    /** Set a different API base URL / key at runtime (e.g. after settings change). */
    fun configureApi(baseUrl: String, apiKey: String) {
        audioApi = if (baseUrl.isNotBlank()) AudioApi(baseUrl, apiKey) else null
    }

    /**
     * Speak [text] using the given [provider] and optionally a specific [apiModel].
     * Returns true if speech was started successfully.
     */
    suspend fun speak(
        text: String,
        provider: AudioProvider = AudioProvider.ANDROID,
        apiModel: String = "",
        voice: String = ""
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            when (provider) {
                AudioProvider.ANDROID -> speakAndroid(text)
                AudioProvider.API -> speakApi(text, apiModel, voice)
                AudioProvider.NONE -> false
            }
        } catch (_: Exception) { false }
    }

    private suspend fun speakAndroid(text: String): Boolean {
        val tts = androidTts ?: return false
        if (!androidReady) {
            ttsInitGate.await()
        }
        if (!androidReady) return false

        val gate = CompletableDeferred<Boolean>()
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { gate.complete(true) }
            override fun onError(utteranceId: String?) { gate.complete(false) }
        })
        tts.setLanguage(Locale.US)
        val result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tts_utterance")
        return if (result == TextToSpeech.SUCCESS) gate.await() else false
    }

    private suspend fun speakApi(text: String, model: String, voice: String): Boolean {
        val api = audioApi ?: return false
        if (model.isBlank()) return false
        val result = api.synthesize(text, model, voice)
        if (result.isFailure) return false
        val audioBytes = result.getOrThrow()
        return playPcmAudio(audioBytes)
    }

    /** Play raw PCM/WAV audio bytes through AudioTrack. */
    private fun playPcmAudio(bytes: ByteArray): Boolean {
        return try {
            // If it's a WAV file, skip the 44-byte header
            val pcmData = if (bytes.size > 44 &&
                bytes[0] == 0x52.toByte() && bytes[1] == 0x49.toByte() &&
                bytes[2] == 0x46.toByte() && bytes[3] == 0x46.toByte()) {
                bytes.copyOfRange(44, bytes.size)
            } else bytes

            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build())
                .setAudioFormat(AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(24000)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build())
                .setBufferSizeInBytes(pcmData.size)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            track.write(pcmData, 0, pcmData.size)
            track.play()
            true
        } catch (_: Exception) { false }
    }

    /** Stop any ongoing speech. */
    fun stop() {
        androidTts?.stop()
    }

    /** Release resources. */
    fun shutdown() {
        androidTts?.stop()
        androidTts?.shutdown()
        androidTts = null
    }
}
