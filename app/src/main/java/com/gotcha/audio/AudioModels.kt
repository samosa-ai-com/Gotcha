package com.gotcha.audio

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Provider type for TTS / STT. */
enum class AudioProvider { ANDROID, API, NONE }

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
enum class ModelCategory { TTS, STT, UNKNOWN }

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

    companion object {
        /**
         * Categorize a model by its [task] field (from the API) and fall back
         * to name heuristics when the task field is absent.
         */
        fun categorize(id: String, task: String? = null): ModelCategory {
            if (task != null) {
                val t = task.lowercase().trim()
                when {
                    t == "text-to-speech" || t == "tts" -> return ModelCategory.TTS
                    t == "automatic-speech-recognition" || t == "stt" ||
                        t == "speech-to-text" || t == "transcription" -> return ModelCategory.STT
                }
            }
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
