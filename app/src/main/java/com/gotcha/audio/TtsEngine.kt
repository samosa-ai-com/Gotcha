package com.gotcha.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.gotcha.i18n.Language
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/**
 * Text-to-Speech engine supporting two providers:
 * - Android built-in (TextToSpeech API)
 * - API-based (OpenAI-compatible /v1/audio/speech)
 */
class TtsEngine(
    private val context: Context,
    apiBaseUrl: String = "",
    apiKey: String = "",
    private val onUnauthorized: (() -> Unit)? = null
) {
    private var audioApi: AudioApi? = if (apiBaseUrl.isNotBlank()) {
        AudioApi(apiBaseUrl, apiKey, onUnauthorized = onUnauthorized)
    } else {
        null
    }
    private var androidTts: TextToSpeech? = null
    private var androidReady = false
    private var ttsInitGate = CompletableDeferred<Boolean>()

    @Volatile
    private var currentTrack: AudioTrack? = null

    @Volatile
    private var playbackGate: CompletableDeferred<Unit>? = null

    private val _isSpeaking = MutableStateFlow(false)

    /** True while [speak] is playing audio — used to avoid the wake word hearing
     *  the app's own speech and re-triggering itself. */
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    /** The models available from the API (empty if provider is Android). */
    var apiTtsModels: List<AudioModel> = emptyList()
        private set

    /** Set when [Language] has no installed Android TTS voice data — read by Settings for the install prompt. */
    var lastLanguageUnavailable: Language? = null
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
        audioApi = if (baseUrl.isNotBlank()) {
            AudioApi(baseUrl, apiKey, onUnauthorized = onUnauthorized)
        } else {
            null
        }
    }

    /**
     * Speak [text] using the given [provider] and optionally a specific [apiModel].
     * Returns true if speech was started successfully.
     */
    suspend fun speak(
        text: String,
        provider: AudioProvider = AudioProvider.ANDROID,
        apiModel: String = "",
        voice: String = "",
        language: Language = Language.ENGLISH
    ): Boolean = withContext(Dispatchers.IO) {
        val sanitized = SpeechTextSanitizer.sanitize(text)
        if (sanitized.isBlank()) return@withContext true
        try {
            _isSpeaking.value = true
            when {
                provider == AudioProvider.ANDROID -> speakAndroid(sanitized, language)
                provider.isApiBased() -> speakApi(sanitized, apiModel, voice, language)
                else -> false
            }
        } catch (_: Exception) {
            false
        } finally {
            _isSpeaking.value = false
        }
    }

    private suspend fun speakAndroid(text: String, language: Language): Boolean {
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

            // stop() interrupts the utterance; without this the gate never completes
            // and a suspended speak() would hang (e.g. pausing a voice call).
            override fun onStop(utteranceId: String?, interrupted: Boolean) { gate.complete(false) }
        })
        val res = tts.setLanguage(language.locale)
        val ok = res != TextToSpeech.LANG_MISSING_DATA && res != TextToSpeech.LANG_NOT_SUPPORTED
        if (ok) {
            lastLanguageUnavailable = null
        } else {
            lastLanguageUnavailable = language
            tts.setLanguage(Locale.US)
        }
        val result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tts_utterance")
        return if (result == TextToSpeech.SUCCESS) gate.await() else false
    }

    private suspend fun speakApi(text: String, model: String, voice: String, language: Language): Boolean {
        val api = audioApi ?: return false
        if (model.isBlank()) return false
        val effectiveVoice = voice.ifBlank {
            apiTtsModels.firstOrNull { it.id == model }?.defaultVoiceFor(language) ?: "af_heart"
        }
        val result = api.synthesize(text, model, effectiveVoice)
        if (result.isFailure) return false
        val audioBytes = result.getOrThrow()
        return playPcmAudio(audioBytes)
    }

    /**
     * Play raw PCM/WAV audio bytes through AudioTrack, suspending until
     * playback completes (or [stop] interrupts it) so callers can sequence
     * speech with other audio work (e.g. re-arming the mic in a voice call).
     */
    private suspend fun playPcmAudio(bytes: ByteArray): Boolean {
        return try {
            // If it's a WAV file, skip the 44-byte header
            val pcmData = if (bytes.size > 44 &&
                bytes[0] == 0x52.toByte() && bytes[1] == 0x49.toByte() &&
                bytes[2] == 0x46.toByte() && bytes[3] == 0x46.toByte()
            ) {
                bytes.copyOfRange(44, bytes.size)
            } else {
                bytes
            }

            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE_HZ)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(pcmData.size)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()

            val done = CompletableDeferred<Unit>()
            currentTrack = track
            playbackGate = done
            // Marker position is in frames: 16-bit mono = 2 bytes per frame.
            track.setNotificationMarkerPosition(pcmData.size / 2)
            track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                override fun onMarkerReached(t: AudioTrack?) { done.complete(Unit) }
                override fun onPeriodicNotification(t: AudioTrack?) {}
            })
            track.write(pcmData, 0, pcmData.size)
            track.play()
            // Fallback in case the marker callback never fires on some devices.
            val durationMs = pcmData.size / 2 * 1000L / SAMPLE_RATE_HZ
            withTimeoutOrNull(durationMs + PLAYBACK_TIMEOUT_SLACK_MS) { done.await() }
            releaseTrack()
            true
        } catch (_: Exception) {
            releaseTrack()
            false
        }
    }

    private fun releaseTrack() {
        try {
            currentTrack?.release()
        } catch (_: Exception) { }
        currentTrack = null
        playbackGate = null
    }

    /** Stop any ongoing speech (both providers). Unblocks a suspended [speak]. */
    fun stop() {
        androidTts?.stop()
        try {
            currentTrack?.pause()
            currentTrack?.flush()
        } catch (_: Exception) { }
        playbackGate?.complete(Unit)
    }

    /** Release resources. */
    fun shutdown() {
        stop()
        releaseTrack()
        androidTts?.shutdown()
        androidTts = null
    }

    private companion object {
        const val SAMPLE_RATE_HZ = 24000
        const val PLAYBACK_TIMEOUT_SLACK_MS = 500L
    }
}
