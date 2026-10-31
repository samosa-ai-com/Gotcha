package com.gotcha.audio

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionService
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import androidx.annotation.RequiresApi
import com.gotcha.R
import com.gotcha.i18n.Language
import com.gotcha.ui.nameRes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * The phone's text-to-speech engines and speech recognizers, and what each can
 * do for a language (#122). Gotcha speaks and listens with the ones the user
 * picks on the Speech page, or the phone's own when none is picked.
 */
object AndroidSpeechInfo {

    /** Secure setting naming the default recognizer; not public API but readable. */
    private const val DEFAULT_RECOGNIZER_SETTING = "voice_recognition_service"
    private const val PROBE_TIMEOUT_MS = 5000L

    /** Per-engine bound for the fallback probe; the common path never pays it. */
    private const val FALLBACK_PROBE_TIMEOUT_MS = 3000L
    private const val TAG_TIMEOUT_MS = 1500L

    suspend fun probe(context: Context, query: AndroidSpeechQuery): AndroidSpeechStatus {
        val tts = probeTts(context, query.ttsEngine, query.language)
        val recognizers = recognizers(context)
        val default = defaultRecognizer(context)
            ?: recognizers.singleOrNull()?.let { ComponentName.unflattenFromString(it.id) }
        val recognizer = installedRecognizer(context, query.sttService) ?: default
        val onDevice = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) hasOnDeviceRecognition(context) else null
        val onDeviceOnly = query.sttOnDeviceOnly && onDevice == true
        fun named(component: ComponentName) = recognizers.firstOrNull { it.id == component.flattenToString() }
            ?: AndroidEngine(component.flattenToString(), appLabel(context, component.packageName))
        return tts.copy(
            recognizers = recognizers,
            defaultRecognizer = default?.let(::named),
            recognizer = recognizer?.let(::named),
            onDeviceRecognition = onDevice,
            recognizerAvailability = recognizerSupport(context, query.sttService, onDeviceOnly, query.language),
            sttOnDeviceOnly = onDeviceOnly
        )
    }

    // ---- Text to speech ----

    /**
     * Binds [engine] (blank = the phone's preferred one) just long enough to read
     * its engines, voices and support for [language].
     */
    private suspend fun probeTts(context: Context, engine: String, language: Language): AndroidSpeechStatus {
        val ready = CompletableDeferred<Boolean>()
        val tts = withContext(Dispatchers.Main) {
            try {
                TextToSpeech(context, { ready.complete(it == TextToSpeech.SUCCESS) }, engine.ifBlank { null })
            } catch (_: Exception) {
                null
            }
        } ?: return AndroidSpeechStatus()
        return try {
            if (withTimeoutOrNull(PROBE_TIMEOUT_MS) { ready.await() } != true) return AndroidSpeechStatus()
            val engines = tts.engines.orEmpty().map { AndroidEngine(it.name, it.label) }
            fun named(id: String) = engines.firstOrNull { it.id == id } ?: AndroidEngine(id, id)
            val default = tts.defaultEngine?.takeIf { it.isNotBlank() }
            val inUse = engine.takeIf { e -> engines.any { it.id == e } } ?: default
            val voices = tts.voices.orEmpty()
                .filter { language.matchesCode(it.locale.toLanguageTag()) }
                .map(::voiceOf)
                .sortedWith(compareBy({ it.availability.ordinal }, { it.name }))
            val availability = ttsAvailability(voices, tts.isLanguageAvailable(language.locale))
            // The engine in use lacks the language: another installed one may
            // still cover it, and speaking falls back to it (issue #153).
            val fallback = when (availability) {
                SpeechAvailability.DOWNLOADABLE, SpeechAvailability.UNSUPPORTED ->
                    probeTtsFallback(
                        context,
                        engines,
                        setOfNotNull(inUse, engine.takeIf { it.isNotBlank() }),
                        language
                    )
                else -> null
            }
            AndroidSpeechStatus(
                ttsEngines = engines,
                ttsDefaultEngine = default?.let(::named),
                ttsEngine = inUse?.let(::named),
                ttsVoices = voices,
                ttsAvailability = availability,
                ttsFallbackEngine = fallback
            )
        } catch (_: Exception) {
            AndroidSpeechStatus()
        } finally {
            tts.shutdown()
        }
    }

    /**
     * The first installed engine besides [skipIds] with the language, if any.
     * Each candidate is bound briefly and asked; failures just move on, so one
     * broken engine cannot fail the probe. Only called when the engine in use
     * lacks the language, keeping the common path to a single binding.
     */
    private suspend fun probeTtsFallback(
        context: Context,
        engines: List<AndroidEngine>,
        skipIds: Set<String>,
        language: Language
    ): AndroidEngine? {
        for (engine in engines.filter { it.id !in skipIds }) {
            val ready = CompletableDeferred<Boolean>()
            val tts = withContext(Dispatchers.Main) {
                try {
                    TextToSpeech(context, { ready.complete(it == TextToSpeech.SUCCESS) }, engine.id)
                } catch (_: Exception) {
                    null
                }
            } ?: continue
            try {
                if (withTimeoutOrNull(FALLBACK_PROBE_TIMEOUT_MS) { ready.await() } != true) continue
                val result = runCatching { tts.isLanguageAvailable(language.locale) }.getOrNull()
                if (result != null &&
                    result != TextToSpeech.LANG_MISSING_DATA &&
                    result != TextToSpeech.LANG_NOT_SUPPORTED
                ) {
                    return engine
                }
            } catch (_: Exception) {
                continue
            } finally {
                tts.shutdown()
            }
        }
        return null
    }

    internal fun voiceOf(voice: Voice): AndroidVoice {
        val notInstalled = TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED in voice.features.orEmpty()
        return AndroidVoice(
            voice.name,
            when {
                notInstalled -> SpeechAvailability.DOWNLOADABLE
                voice.isNetworkConnectionRequired -> SpeechAvailability.ONLINE
                else -> SpeechAvailability.ON_DEVICE
            }
        )
    }

    /**
     * The best of [voices]; engines that list no voices are judged by
     * [languageResult], a `TextToSpeech.isLanguageAvailable` code.
     */
    internal fun ttsAvailability(voices: List<AndroidVoice>, languageResult: Int): SpeechAvailability =
        voices.minOfOrNull { it.availability }
            ?: when (languageResult) {
                TextToSpeech.LANG_MISSING_DATA -> SpeechAvailability.DOWNLOADABLE
                TextToSpeech.LANG_NOT_SUPPORTED -> SpeechAvailability.UNSUPPORTED
                else -> SpeechAvailability.AVAILABLE
            }

    // ---- Speech recognition ----

    /** Installed recognizers, by component and name. */
    fun recognizers(context: Context): List<AndroidEngine> {
        val pm = context.packageManager
        return pm.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
            .map { info ->
                val service = info.serviceInfo
                AndroidEngine(
                    ComponentName(service.packageName, service.name).flattenToString(),
                    service.loadLabel(pm).toString()
                )
            }
            .sortedBy { it.label }
    }

    /** [id] as a component when that recognizer is still installed. */
    fun installedRecognizer(context: Context, id: String): ComponentName? {
        if (id.isBlank()) return null
        return ComponentName.unflattenFromString(id)?.takeIf { component ->
            recognizers(context).any { it.id == component.flattenToString() }
        }
    }

    private fun defaultRecognizer(context: Context): ComponentName? =
        try {
            Settings.Secure.getString(context.contentResolver, DEFAULT_RECOGNIZER_SETTING)
                ?.takeIf { it.isNotBlank() }
                ?.let(ComponentName::unflattenFromString)
        } catch (_: Exception) {
            null
        }

    private fun appLabel(context: Context, packageName: String): String {
        val pm = context.packageManager
        return try {
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        } catch (_: Exception) {
            packageName
        }
    }

    /** Whether the phone can recognise speech without the network (Android 12+). */
    fun hasOnDeviceRecognition(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    /**
     * The recognizer the settings ask for: the phone's on-device one when
     * [onDeviceOnly], else [service] if installed, else the phone's default.
     * Null when on-device recognition was asked for and the phone has none,
     * so the caller fails rather than quietly using a network recognizer.
     * Must be called on the main thread.
     */
    fun createRecognizer(context: Context, service: String, onDeviceOnly: Boolean): SpeechRecognizer? {
        if (onDeviceOnly) {
            return if (hasOnDeviceRecognition(context)) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            } else {
                null
            }
        }
        val component = installedRecognizer(context, service)
        return if (component != null) {
            SpeechRecognizer.createSpeechRecognizer(context, component)
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }
    }

    /**
     * Whether the recognizer [createRecognizer] would pick has [language], or
     * null when it can't say: Android before 13 has no way to ask, and many
     * recognizers don't answer or answer with empty lists.
     */
    private suspend fun recognizerSupport(
        context: Context,
        service: String,
        onDeviceOnly: Boolean,
        language: Language
    ): SpeechAvailability? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        if (!onDeviceOnly && !SpeechRecognizer.isRecognitionAvailable(context)) return null
        return withContext(Dispatchers.Main) {
            val recognizer = try {
                createRecognizer(context, service, onDeviceOnly)
            } catch (_: Exception) {
                null
            } ?: return@withContext null
            try {
                withTimeoutOrNull(PROBE_TIMEOUT_MS) { checkSupport(context, recognizer, language) }
            } finally {
                recognizer.destroy()
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private suspend fun checkSupport(
        context: Context,
        recognizer: SpeechRecognizer,
        language: Language
    ): SpeechAvailability? = suspendCancellableCoroutine { cont ->
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        try {
            recognizer.checkRecognitionSupport(
                intent,
                context.mainExecutor,
                object : RecognitionSupportCallback {
                    override fun onSupportResult(support: RecognitionSupport) {
                        if (cont.isActive) {
                            cont.resume(
                                recognizerAvailability(
                                    language,
                                    installed = support.installedOnDeviceLanguages,
                                    online = support.onlineLanguages,
                                    downloadable = support.supportedOnDeviceLanguages +
                                        support.pendingOnDeviceLanguages
                                )
                            )
                        }
                    }

                    override fun onError(error: Int) {
                        if (cont.isActive) cont.resume(null)
                    }
                }
            )
        } catch (_: Exception) {
            if (cont.isActive) cont.resume(null)
        }
    }

    /**
     * Asks [recognizer] which variant of [language] it has installed on the
     * phone, for [onResult] on the main thread: `en-IN` on a phone whose
     * offline English is Indian, though [Language.bcp47] says `en-US`. Null
     * before Android 13, when nothing is installed, or when the recognizer
     * can't say; the caller then asks for [Language.bcp47]. Main thread only.
     */
    fun installedTag(context: Context, recognizer: SpeechRecognizer, language: Language, onResult: (String?) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return onResult(null)
        var answered = false
        val answer = { tag: String? ->
            if (!answered) {
                answered = true
                onResult(tag)
            }
        }
        // Some recognizers never call back; listening must not wait on them.
        Handler(Looper.getMainLooper()).postDelayed({ answer(null) }, TAG_TIMEOUT_MS)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        try {
            recognizer.checkRecognitionSupport(
                intent,
                context.mainExecutor,
                object : RecognitionSupportCallback {
                    override fun onSupportResult(support: RecognitionSupport) {
                        answer(pickInstalledTag(language, support.installedOnDeviceLanguages))
                    }

                    override fun onError(error: Int) = answer(null)
                }
            )
        } catch (_: Exception) {
            answer(null)
        }
    }

    /** [language] as installed: its own tag when listed, else the first variant of it, else null. */
    internal fun pickInstalledTag(language: Language, installed: List<String>): String? =
        installed.firstOrNull { it.replace('_', '-').equals(language.bcp47, ignoreCase = true) }
            ?: installed.firstOrNull(language::matchesCode)

    /**
     * The message for a recognizer [error] no retry will fix, or null. Without
     * it the push-to-talk loop would restart the recognizer forever on a
     * language the phone doesn't have.
     */
    fun fatalError(context: Context, error: Int, language: Language, onDeviceOnly: Boolean): String? =
        when (error) {
            ERROR_LANGUAGE_NOT_SUPPORTED, ERROR_LANGUAGE_UNAVAILABLE -> context.getString(
                if (onDeviceOnly) R.string.stt_language_not_on_device else R.string.stt_language_unsupported,
                context.getString(language.nameRes)
            )
            else -> null
        }

    // SpeechRecognizer's Android 12 codes, inlined so they read on Android 11 too.
    private const val ERROR_LANGUAGE_NOT_SUPPORTED = 12
    private const val ERROR_LANGUAGE_UNAVAILABLE = 13

    /** Null when the recognizer lists no languages at all, since that says nothing. */
    internal fun recognizerAvailability(
        language: Language,
        installed: List<String>,
        online: List<String>,
        downloadable: List<String>
    ): SpeechAvailability? = when {
        installed.isEmpty() && online.isEmpty() && downloadable.isEmpty() -> null
        installed.any(language::matchesCode) -> SpeechAvailability.ON_DEVICE
        online.any(language::matchesCode) -> SpeechAvailability.ONLINE
        downloadable.any(language::matchesCode) -> SpeechAvailability.DOWNLOADABLE
        else -> SpeechAvailability.UNSUPPORTED
    }
}
