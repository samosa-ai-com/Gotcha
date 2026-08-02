package com.gotcha.audio

import com.gotcha.i18n.Language
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Provider type for TTS / STT.
 * - [ANDROID]: device-native TextToSpeech / SpeechRecognizer (no network).
 * - [SAMOSA_AI]: backend session JWT against the Samosa `/v1/audio/`
 *   endpoints. No credentials to enter — the same Google Sign-In used for the
 *   LLM provider covers audio here.
 * - [API]: user-supplied OpenAI-compatible base URL + API key.
 * - [NONE]: disabled.
 */
enum class AudioProvider(val label: String) {
    ANDROID("Android Built-in"),
    SAMOSA_AI("Samosa AI"),
    API("External API"),
    NONE("None");

    /** True for providers that hit a remote `/v1/audio/` endpoint
     *  (Samosa AI or a user-supplied OpenAI-compatible server). */
    fun isApiBased(): Boolean = this == SAMOSA_AI || this == API
}

/**
 * Response from GET /v1/models (OpenAI-compatible format with extra fields).
 */
@Serializable
data class ModelListResponse(
    val data: List<ModelInfo>
)

@Serializable
data class ModelInfo(
    val id: String,
    val objectType: String = "model",
    @SerialName("owned_by")
    val ownedBy: String = "",
    /** Task type: "text-to-speech", "automatic-speech-recognition", etc. */
    val task: String? = null
)

/** Categorization of a model from the API. */
enum class ModelCategory { TTS, STT, LLM, UNKNOWN }

/** Detailed voice metadata for TTS models. */
data class VoiceInfo(
    val id: String,
    val name: String = "",
    val language: String = "",
    val gender: String = ""
) {
    val displayLabel: String
        get() {
            val details = listOfNotNull(
                language.takeIf { it.isNotBlank() },
                gender.takeIf { it.isNotBlank() }
            )
            return if (details.isEmpty()) {
                id
            } else {
                "$id (${details.joinToString(", ")})"
            }
        }
}

/** A discovered audio model with its category, supported languages, and default voice (TTS only). */
data class AudioModel(
    val id: String,
    val category: ModelCategory,
    /** Supported languages for the model (e.g. ["en", "es"] or ["multilingual"]). */
    val languages: List<String> = emptyList(),
    /** Available voice objects for TTS models; first one is the default. */
    val voices: List<VoiceInfo> = emptyList()
) {
    val defaultVoice: String get() = voices.firstOrNull()?.id ?: "af_heart"

    /** Best voice for [language] (matched by [VoiceInfo.language]), falling back to [defaultVoice]. */
    fun defaultVoiceFor(language: Language): String =
        voices.firstOrNull { it.language.startsWith(language.iso639, ignoreCase = true) }?.id
            ?: defaultVoice

    companion object {
        private val TTS_HINTS = setOf(
            "text-to-speech",
            "tts"
        )
        private val STT_HINTS = setOf(
            "automatic-speech-recognition",
            "stt",
            "speech-to-text",
            "transcription"
        )
        private val LLM_HINTS = setOf(
            "llm",
            "chat",
            "text-generation",
            "chat-completion",
            "chat_completion",
            "language-model",
            "completions"
        )

        /**
         * Categorize a model by the API-declared hint fields and fall back to
         * name heuristics. Different servers publish the hint under different
         * keys — `task` is the OpenAI-compatible norm, `provider_type` is what
         * the Samosa proxy uses, so both are checked. Name heuristics remain
         * the last resort when neither is present.
         */
        fun categorize(id: String, task: String? = null, providerType: String? = null): ModelCategory {
            classifyByHint(task)?.let { return it }
            classifyByHint(providerType)?.let { return it }
            return classifyByName(id)
        }

        private fun classifyByHint(hint: String?): ModelCategory? {
            if (hint.isNullOrBlank()) return null
            val normalized = hint.lowercase().trim()
            return when (normalized) {
                in TTS_HINTS -> ModelCategory.TTS
                in STT_HINTS -> ModelCategory.STT
                in LLM_HINTS -> ModelCategory.LLM
                else -> null
            }
        }

        private fun classifyByName(id: String): ModelCategory {
            val lower = id.lowercase()
            return when {
                lower.contains("whisper") -> ModelCategory.STT
                lower.contains("stt") -> ModelCategory.STT
                lower.contains("transcription") -> ModelCategory.STT
                lower.contains("translate") -> ModelCategory.STT
                lower.contains("tts") -> ModelCategory.TTS
                lower.contains("speech") -> ModelCategory.TTS
                lower.contains("kokoro") -> ModelCategory.TTS
                lower.contains("voice") -> ModelCategory.TTS
                lower.contains("audio-speech") -> ModelCategory.TTS
                else -> ModelCategory.UNKNOWN
            }
        }
    }
}
