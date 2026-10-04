package com.gotcha.audio

import com.gotcha.R
import com.gotcha.i18n.Language
import com.gotcha.i18n.StringLookup
import com.gotcha.ui.nameRes

/**
 * What happens when the voice language and the chosen speech models disagree
 * (issue #113), worded for the settings pages that show it.
 *
 * Gotcha does not refuse the combination: a server may handle more than it
 * lists, and the model is the user's choice. It says, where the choice is made,
 * what will happen:
 *
 *  - **Text to speech, voice left on default.** Gotcha uses the model's first
 *    voice in the voice language ([AudioModel.defaultVoiceFor]). When the model
 *    has none, it uses the model's default voice, which reads the text as its
 *    own language would — Hindi read by an English voice.
 *  - **Text to speech, voice picked by hand.** That voice is used whatever the
 *    language.
 *  - **Speech to text.** The model is asked for the transcription override, or
 *    else the voice language. A model that does not list that language may
 *    transcribe it wrongly.
 *  - **Android built-in.** No model to compare; "Test voice" on the Language
 *    page reports missing voice data and falls back to English.
 *
 * Only what the server publishes is judged: a model that lists no languages,
 * and no voices with one, gets no warning, because nothing is known about it.
 */
object SpeechLanguageCheck {

    /** Language codes that mean "any language" rather than naming one. */
    private val ANY_LANGUAGE = setOf("multilingual", "auto", "all", "any")

    /**
     * Why [language] will not be spoken properly by text-to-speech [modelId] and
     * [voice] (blank = Gotcha picks one), or null when it will or nothing is known.
     */
    fun ttsWarning(
        provider: AudioProvider,
        modelId: String,
        voice: String,
        models: List<AudioModel>,
        language: Language,
        strings: StringLookup
    ): String? {
        if (!provider.isApiBased()) return null
        val model = models.firstOrNull { it.id == modelId.trim() } ?: return null
        val name = strings(language.nameRes)
        val picked = voice.trim()
        if (picked.isNotEmpty()) {
            val pickedInfo = (model.voices + models.flatMap { it.voices }).firstOrNull { it.id == picked }
                ?: VoiceInfo(id = picked)
            val code = pickedInfo.languageCode ?: return null
            if (language.matchesCode(code)) return null
            val hasMatchingVoice = model.voices.any { v -> v.languageCode?.let(language::matchesCode) == true }
            val remedy = strings(
                if (hasMatchingVoice) R.string.speech_check_remedy_clear else R.string.speech_check_remedy_pick,
                name
            )
            return strings(R.string.speech_check_voice_mismatch, picked, languageName(code, strings), name, remedy)
        }
        if (ttsSupports(model, language) != false) return null
        val fallback = model.defaultVoice
        val fallbackLanguage = model.voices.firstOrNull { it.id == fallback }?.languageCode
            ?: AudioLanguageLabels.languageFromVoiceId(fallback)
        val readBy = fallbackLanguage?.let { strings(R.string.speech_check_read_by_voice, languageName(it, strings), fallback) }
            ?: strings(R.string.speech_check_read_by_default, fallback)
        return strings(R.string.speech_check_model_no_voice, model.id, name, readBy)
    }

    /**
     * Why speech in [language] will not be transcribed properly by speech-to-text
     * [modelId] with transcription [override] (blank = follow [language]), or an
     * empty list when it will or nothing is known.
     */
    fun sttWarnings(
        provider: AudioProvider,
        modelId: String,
        override: String,
        models: List<AudioModel>,
        language: Language,
        strings: StringLookup
    ): List<String> {
        if (!provider.isApiBased()) return emptyList()
        val name = strings(language.nameRes)
        val forced = override.trim()
        val warnings = mutableListOf<String>()
        if (forced.isNotEmpty() && forced.lowercase() !in ANY_LANGUAGE && !language.matchesCode(forced)) {
            warnings += strings(R.string.speech_check_stt_forced, languageName(forced, strings), forced, name)
        }
        val heard = forced.ifEmpty { language.iso639 }
        val model = models.firstOrNull { it.id == modelId.trim() }
        val listed = model?.languages.orEmpty().map { it.trim() }.filter { it.isNotEmpty() }
        val heardName = if (forced.isEmpty()) name else languageName(forced, strings)
        if (model != null && listed.isNotEmpty() && listed.none { it.lowercase() in ANY_LANGUAGE } &&
            heard.lowercase() !in ANY_LANGUAGE && listed.none { sameLanguage(it, heard) }
        ) {
            warnings += strings(R.string.speech_check_stt_unlisted, model.id, heardName)
        }
        return warnings
    }

    /**
     * Whether [model] can speak [language]: its voices decide when they say what
     * they speak, since a voice is what reads the text; otherwise its languages.
     * Null when it publishes neither.
     */
    internal fun ttsSupports(model: AudioModel, language: Language): Boolean? {
        val voiceCodes = model.voices.mapNotNull { it.languageCode }
        if (voiceCodes.isNotEmpty()) return voiceCodes.any(language::matchesCode)
        val listed = model.languages.map { it.trim() }.filter { it.isNotEmpty() }
        if (listed.isEmpty()) return null
        return listed.any { it.lowercase() in ANY_LANGUAGE || language.matchesCode(it) }
    }

    /** "Hindi" for `hi`, "English" for `en-us`; the JDK's name, or the code, otherwise. */
    private fun languageName(code: String, strings: StringLookup): String =
        Language.entries.firstOrNull { it.matchesCode(code) }?.let { strings(it.nameRes) }
            ?: AudioLanguageLabels.describe(code.substringBefore('-').substringBefore('_'))
            ?: code

    private fun sameLanguage(a: String, b: String): Boolean =
        primary(a).equals(primary(b), ignoreCase = true)

    private fun primary(code: String): String = code.trim().replace('_', '-').substringBefore('-')
}
