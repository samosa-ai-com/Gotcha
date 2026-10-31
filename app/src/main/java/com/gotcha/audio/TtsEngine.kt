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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

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

    @Volatile
    private var androidReady = false

    @Volatile
    private var ttsInitGate = CompletableDeferred<Boolean>()

    /** The engine [androidTts] was bound to ask for; blank = the phone's preferred one. */
    private var androidEngine = ""

    /**
     * Per-language fallback engines (issue #153): when the requested engine
     * has no voice for a language but another installed one does — Samsung
     * with no Arabic beside Google with it, say — the winner is remembered so
     * later speaks go straight there. Cleared with a new engine choice, so an
     * explicit pick always wins again.
     */
    private var requestedEngine = ""
    private val fallbackEngines = mutableMapOf<Language, String>()

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

    /**
     * Set when the last [speak] used Android and found no installed voice for
     * its [Language], so said nothing; null after any other speak. Read by
     * Settings for the install prompt and by callers to say why nothing was
     * read aloud.
     */
    var lastLanguageUnavailable: Language? = null
        private set

    /**
     * Serializes Android speaks: engine hops rebind mid-path, and two
     * overlapping speaks would otherwise strand each other's utterance gates
     * and memoize under the wrong engine choice.
     */
    private val androidSpeakMutex = Mutex()

    init {
        bindAndroidTts("")
    }

    /** Bind Android text-to-speech to [engine] (blank = the phone's), dropping any earlier binding. */
    private fun bindAndroidTts(engine: String) {
        androidTts?.shutdown()
        androidReady = false
        val gate = CompletableDeferred<Boolean>()
        ttsInitGate = gate
        androidEngine = engine
        androidTts = try {
            TextToSpeech(
                context,
                { status ->
                    // A binding replaced before it finished must not mark the new one ready.
                    if (gate === ttsInitGate) androidReady = status == TextToSpeech.SUCCESS
                    gate.complete(status == TextToSpeech.SUCCESS)
                },
                engine.ifBlank { null }
            )
        } catch (_: Exception) {
            gate.complete(false)
            null
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
     * [voice] is the API voice; [androidEngine] and [androidVoice] choose Android
     * Built-in's engine and voice (blank = the phone's and the engine's own).
     * Returns true if speech was started successfully.
     */
    suspend fun speak(
        text: String,
        provider: AudioProvider = AudioProvider.ANDROID,
        apiModel: String = "",
        voice: String = "",
        language: Language = Language.ENGLISH,
        androidEngine: String = "",
        androidVoice: String = ""
    ): Boolean = withContext(Dispatchers.IO) {
        // Android resets inside its mutex, serialized with its result; other
        // providers clear here so a stale report never survives a switch.
        if (provider != AudioProvider.ANDROID) lastLanguageUnavailable = null
        val sanitized = SpeechTextSanitizer.sanitize(text)
        if (sanitized.isBlank()) return@withContext true
        try {
            _isSpeaking.value = true
            when {
                provider == AudioProvider.ANDROID -> speakAndroid(sanitized, language, androidEngine, androidVoice)
                provider.isApiBased() -> speakApi(sanitized, apiModel, voice, language)
                else -> false
            }
        } catch (_: Exception) {
            false
        } finally {
            _isSpeaking.value = false
        }
    }

    private suspend fun speakAndroid(text: String, language: Language, engine: String, voiceName: String): Boolean =
        androidSpeakMutex.withLock { speakAndroidLocked(text, language, engine, voiceName) }

    private suspend fun speakAndroidLocked(
        text: String,
        language: Language,
        engine: String,
        voiceName: String
    ): Boolean {
        // Reset here as well as in speak(): serialized with the result below,
        // so one speak cannot clear another's missing-language report mid-read.
        lastLanguageUnavailable = null
        if (engine != requestedEngine) {
            requestedEngine = engine
            fallbackEngines.clear()
        }
        val preferred = fallbackEngines[language] ?: engine
        if (preferred != androidEngine) bindAndroidTts(preferred)
        var tts = readyAndroidTts() ?: return false

        // The requested engine has no voice for the language: ask the other
        // installed engines before giving up, and remember the winner. An
        // engine list that cannot even be read is unknown, not missing: report
        // failure without the install prompt.
        if (!isAvailable(tts.setLanguage(language.locale))) {
            val alternatives = runCatching { tts.engines.orEmpty() }.getOrNull() ?: return false
            var winner: String? = null
            var probed = false
            for (candidate in alternatives.map { it.name }.filter { it != preferred }) {
                bindAndroidTts(candidate)
                val alt = readyAndroidTts() ?: continue
                probed = true
                if (isAvailable(alt.setLanguage(language.locale))) {
                    winner = candidate
                    break
                }
            }
            if (winner == null) {
                // A missing voice says nothing rather than reading the text with an
                // English one, which mangles it (#122); callers report why.
                // Claim missing only on a definitive answer: the primary plus
                // a probed candidate, or no alternatives at all. Uninitialized
                // candidates leave support unknown, so stay quiet instead of
                // prompting an install that may not be needed.
                if (probed || alternatives.isEmpty()) lastLanguageUnavailable = language
                return false
            }
            fallbackEngines[language] = winner
            tts = readyAndroidTts() ?: return false
        }

        val gate = CompletableDeferred<Boolean>()
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { gate.complete(true) }
            override fun onError(utteranceId: String?) { gate.complete(false) }

            // stop() interrupts the utterance; without this the gate never completes
            // and a suspended speak() would hang (e.g. pausing a voice call).
            override fun onStop(utteranceId: String?, interrupted: Boolean) { gate.complete(false) }
        })
        // A voice picked for another language is left for when that one is spoken.
        // On a fallback engine the picked name never matches, so its own voice is used.
        if (voiceName.isNotBlank()) {
            tts.voices.orEmpty()
                .firstOrNull { it.name == voiceName && language.matchesCode(it.locale.toLanguageTag()) }
                ?.let(tts::setVoice)
        }
        val result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tts_utterance")
        return if (result == TextToSpeech.SUCCESS) gate.await() else false
    }

    private suspend fun readyAndroidTts(): TextToSpeech? {
        val tts = androidTts ?: return null
        if (!androidReady) ttsInitGate.await()
        return tts.takeIf { androidReady }
    }

    private fun isAvailable(result: Int): Boolean =
        result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED

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
