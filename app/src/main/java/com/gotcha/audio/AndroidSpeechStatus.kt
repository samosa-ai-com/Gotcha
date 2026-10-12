package com.gotcha.audio

import com.gotcha.i18n.Language

/** An installed text-to-speech engine or speech recognizer. [id] is what settings store. */
data class AndroidEngine(val id: String, val label: String)

/** A voice of a text-to-speech engine, for the voice picker. */
data class AndroidVoice(val name: String, val availability: SpeechAvailability)

/** Where a language can be spoken or recognised, best first. */
enum class SpeechAvailability {
    /** Installed on the phone; works offline. */
    ON_DEVICE,

    /** Works through the engine's server only. */
    ONLINE,

    /** The engine has it but it isn't downloaded yet. */
    DOWNLOADABLE,

    /** The engine says it has it, without saying where. */
    AVAILABLE,

    /** The engine doesn't have it. */
    UNSUPPORTED
}

/** What the Speech and Language pages ask [AndroidSpeechInfo] about. */
data class AndroidSpeechQuery(
    val language: Language,
    val ttsEngine: String = "",
    val sttService: String = "",
    val sttOnDeviceOnly: Boolean = false
)

/**
 * What Android Built-in will use for one language (#122): the phone's engines,
 * the ones in use, and whether each has the language. Null fields are unknown,
 * which shows no name and no warning.
 */
data class AndroidSpeechStatus(
    val ttsEngines: List<AndroidEngine> = emptyList(),
    /** The phone's preferred engine, which a blank choice follows. */
    val ttsDefaultEngine: AndroidEngine? = null,
    val ttsEngine: AndroidEngine? = null,
    /** The voices of [ttsEngine] in the queried language, best first. */
    val ttsVoices: List<AndroidVoice> = emptyList(),
    val ttsAvailability: SpeechAvailability? = null,
    val recognizers: List<AndroidEngine> = emptyList(),
    /** The phone's default recognizer, which a blank choice follows. */
    val defaultRecognizer: AndroidEngine? = null,
    val recognizer: AndroidEngine? = null,
    /** Whether the phone can recognise speech on the device at all; null before Android 12. */
    val onDeviceRecognition: Boolean? = null,
    /** For the recognizer in use, or the on-device one when the query asks for on-device only. */
    val recognizerAvailability: SpeechAvailability? = null,
    val sttOnDeviceOnly: Boolean = false
) {
    /** False when Android text-to-speech can't speak the language as things stand. */
    val ttsSupports: Boolean?
        get() = when (ttsAvailability) {
            null -> null
            SpeechAvailability.DOWNLOADABLE, SpeechAvailability.UNSUPPORTED -> false
            else -> true
        }

    /** False when voice input in the language will fail or mistranscribe as things stand. */
    val recognizerSupports: Boolean?
        get() = when (recognizerAvailability) {
            null -> null
            SpeechAvailability.ONLINE -> !sttOnDeviceOnly
            SpeechAvailability.DOWNLOADABLE, SpeechAvailability.UNSUPPORTED -> false
            else -> true
        }
}
