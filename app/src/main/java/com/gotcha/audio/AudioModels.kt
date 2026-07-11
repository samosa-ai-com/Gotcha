package com.gotcha.audio

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Provider type for TTS / STT. */
enum class AudioProvider { ANDROID, API, NONE }

/**
 * Response from GET /v1/models (OpenAI-compatible format).
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
    val ownedBy: String = ""
)

/** Categorization of a model from the API. */
enum class ModelCategory { TTS, STT, UNKNOWN }

/** A discovered audio model with its category. */
data class AudioModel(
    val id: String,
    val category: ModelCategory
) {
    companion object {
        /** Categorize a model ID by its name heuristics. */
        fun categorize(id: String): ModelCategory {
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
