package com.gotcha.audio

import java.util.Locale

/**
 * Human-readable names for the raw language codes and voice ids the audio
 * servers publish (issue #75). `/v1/models` hands back codes like `hi`, `en-us`
 * or `af_heart`; the pickers show those next to a name so the user can tell
 * which language each entry is without looking the code up.
 */
object AudioLanguageLabels {

    /** Where the Samosa AI docs explain choosing speech providers, voices and languages. */
    const val SPEECH_DOCS_URL = "https://samosa-ai.com/gotcha/docs/getting-started#5-Speech"

    /** Codes the JDK has no name for, or that are not language tags at all. */
    private val OVERRIDES = mapOf(
        "jw" to "Javanese", // Whisper's legacy code for `jv`
        "multilingual" to "Multiple languages",
        "auto" to "Auto-detect"
    )

    /**
     * Kokoro voice ids start with a language letter and a gender letter
     * (`af_heart` = American English, female). Used only when the server did
     * not send a `language` for the voice.
     */
    private val KOKORO_VOICE_ID = Regex("^([abefhijpz])([fm])_", RegexOption.IGNORE_CASE)
    private val KOKORO_LANGUAGES = mapOf(
        'a' to "en-US",
        'b' to "en-GB",
        'e' to "es",
        'f' to "fr-FR",
        'h' to "hi",
        'i' to "it",
        'j' to "ja",
        'p' to "pt-BR",
        'z' to "zh-CN"
    )

    /**
     * English name for [code] (`hi` → "Hindi", `en-us` → "English (United States)"),
     * or null when the code is blank or not a language the JDK recognises.
     */
    fun describe(code: String): String? {
        val trimmed = code.trim()
        if (trimmed.isEmpty()) return null
        OVERRIDES[trimmed.lowercase()]?.let { return it }
        val locale = Locale.forLanguageTag(trimmed.replace('_', '-'))
        val language = locale.language
        if (language.isEmpty()) return null
        // An unknown code comes back as itself rather than as a name.
        if (locale.getDisplayLanguage(Locale.ENGLISH).equals(language, ignoreCase = true)) return null
        return locale.getDisplayName(Locale.ENGLISH).takeIf { it.isNotBlank() }
    }

    /** "`hi` — Hindi" style picker text; the bare code when it has no known name. */
    fun label(code: String): String = describe(code)?.let { "$code — $it" } ?: code

    /** Language tag implied by a Kokoro-style voice id, or null. */
    fun languageFromVoiceId(voiceId: String): String? =
        KOKORO_VOICE_ID.find(voiceId)?.let { KOKORO_LANGUAGES[it.groupValues[1].lowercase()[0]] }

    /** Gender implied by a Kokoro-style voice id, or null. */
    fun genderFromVoiceId(voiceId: String): String? =
        KOKORO_VOICE_ID.find(voiceId)?.let { match ->
            if (match.groupValues[2].equals("f", ignoreCase = true)) "female" else "male"
        }
}
