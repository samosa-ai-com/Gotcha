package com.gotcha.tools

import android.content.Context
import com.gotcha.audio.AudioApi
import com.gotcha.audio.AudioModel
import com.gotcha.audio.AudioProvider
import com.gotcha.audio.ModelCategory
import com.gotcha.data.Settings
import com.gotcha.data.SettingsRepository
import java.io.File

/**
 * Transcribes an audio file already on disk through the configured STT API.
 *
 * This is the missing middle of the voice-memo-to-podcast flow:
 * `start_audio_recording(output_path=…)` puts a recording on disk,
 * `synthesize_podcast` wants a script, and nothing in between could read a
 * saved file back as text — [com.gotcha.audio.SttEngine] only transcribes
 * recordings it made itself, and its `transcribeApi` *deletes its input* after
 * upload (its files are its own throwaways). This tool wraps
 * [AudioApi.transcribe] directly, which reads the file and touches nothing.
 *
 * Requires an API-based STT provider: Android's `SpeechRecognizer` listens to
 * the microphone and has no file input. Only available to Operator — the file
 * content leaves the device for the configured STT endpoint.
 */
class TranscribeTool(
    private val context: Context,
    private val loadSettings: () -> Settings = { SettingsRepository(context).load() },
    private val onUnauthorized: (() -> Unit)? = null,
    private val sttBackendFactory: (baseUrl: String, apiKey: String) -> SttBackend = { url, key ->
        ApiSttBackend(AudioApi(url, key, timeoutSeconds = STT_TIMEOUT_SECONDS, onUnauthorized = onUnauthorized))
    }
) {

    /** The two calls transcription needs from the STT API, extracted for tests. */
    interface SttBackend {
        fun listModels(): List<AudioModel>
        fun transcribe(file: File, model: String, language: String?, contentType: String): Result<String>
    }

    private class ApiSttBackend(private val api: AudioApi) : SttBackend {
        override fun listModels(): List<AudioModel> = api.listAudioModels()
        override fun transcribe(file: File, model: String, language: String?, contentType: String): Result<String> =
            api.transcribe(file, model, language, contentType)
    }

    private val resolver = FileResolver(context)

    companion object {
        /**
         * Upload ceiling. Matches the common OpenAI-compatible transcription
         * limit; a bigger file should be trimmed or split with media_edit
         * first rather than bounced by the server after a slow upload.
         */
        const val MAX_INPUT_BYTES = 25L * 1024 * 1024

        /** Transcripts are pasted into the conversation; past this they drown it. */
        const val MAX_TRANSCRIPT_CHARS = 30_000

        private const val STT_TIMEOUT_SECONDS = 300L
        private const val ERROR_DETAIL_CHARS = 200

        /** Extension → MIME type for the multipart upload. Also the supported-format list. */
        private val CONTENT_TYPES = mapOf(
            "m4a" to "audio/m4a",
            "mp4" to "audio/mp4",
            "aac" to "audio/aac",
            "mp3" to "audio/mpeg",
            "wav" to "audio/wav",
            "ogg" to "audio/ogg",
            "opus" to "audio/ogg",
            "flac" to "audio/flac",
            "webm" to "audio/webm"
        )
    }

    @Suppress("ReturnCount")
    fun transcribe(path: String, model: String? = null, language: String? = null): ToolResult {
        val settings = loadSettings()
        credentialError(settings)?.let { return it }

        val file = when (val r = resolver.resolveForRead(path)) {
            is FileResolver.ResolveResult.PermissionNeeded -> return r.result
            is FileResolver.ResolveResult.Error -> return ToolResult.error(r.message)
            is FileResolver.ResolveResult.Ok -> r.file
        }
        resolver.checkReadPermission(file)?.let { return it }
        fileError(file, path)?.let { return it }
        val contentType = CONTENT_TYPES[file.extension.lowercase()]
            ?: return ToolResult.error(
                "'.${file.extension}' is not a format this tool can send for transcription. Supported: " +
                    CONTENT_TYPES.keys.sorted().joinToString(", ") { ".$it" } +
                    ". Convert it first with media_convert."
            )

        val backend = sttBackendFactory(settings.effectiveSttBaseUrl, settings.effectiveSttApiKey)
        val resolvedModel = resolveModel(model, settings, backend)
            ?: return ToolResult.error(
                "No STT model is configured and none could be discovered from the API. Pass model " +
                    "explicitly, or ask the user to pick one in Settings → Speech → Speech-to-Text."
            )
        val resolvedLanguage = (language ?: settings.sttLanguage).trim().ifBlank { null }

        val transcript = backend.transcribe(file, resolvedModel, resolvedLanguage, contentType)
            .getOrElse { e ->
                return ToolResult.error(
                    "Transcription failed: ${e.message?.take(ERROR_DETAIL_CHARS) ?: "no detail"}. " +
                        "The file was not modified."
                )
            }
        if (transcript.isBlank()) {
            return ToolResult.error(
                "The STT endpoint returned an empty transcript for '${file.name}' — the file may contain " +
                    "no speech, or the model/language may not match it."
            )
        }
        val clipped = transcript.length > MAX_TRANSCRIPT_CHARS
        val text = if (clipped) transcript.take(MAX_TRANSCRIPT_CHARS) else transcript
        val truncationNote = if (clipped) {
            "\n\n[Transcript truncated at $MAX_TRANSCRIPT_CHARS characters — the audio continues beyond " +
                "this point. Trim the file with media_edit and transcribe the rest separately if needed.]"
        } else {
            ""
        }
        return ToolResult.ok(
            "Transcript of '${file.name}' (${resolver.formatSize(file.length())}, spoken content sent to " +
                "the configured STT API; the file itself was not modified):\n\n$text$truncationNote"
        )
    }

    private fun credentialError(settings: Settings): ToolResult? {
        if (!settings.sttProvider.isApiBased()) {
            return ToolResult.error(
                "The Speech-to-Text provider is '${settings.sttProvider.label}', which only listens to the " +
                    "microphone — it cannot read an audio file. Ask the user to set Settings → Speech → " +
                    "Speech-to-Text to Samosa AI or External API, then try again."
            )
        }
        if (settings.effectiveSttBaseUrl.isBlank()) {
            return ToolResult.error(
                if (settings.sttProvider == AudioProvider.SAMOSA_AI) {
                    "STT is set to Samosa AI but there is no active session. Ask the user to sign in again " +
                        "in Settings, then retry."
                } else {
                    "STT is set to External API but no base URL is configured. Ask the user to complete " +
                        "Settings → Speech → Speech-to-Text, then retry."
                }
            )
        }
        return null
    }

    private fun fileError(file: File, path: String): ToolResult? = when {
        !file.exists() -> ToolResult.error(
            "'$path' does not exist (resolved: ${file.canonicalPath}). You may use list_files or glob to find it."
        )
        !file.isFile -> ToolResult.error("'$path' is not a regular file.")
        file.length() == 0L -> ToolResult.error("'${file.name}' is empty — there is nothing to transcribe.")
        file.length() > MAX_INPUT_BYTES -> ToolResult.error(
            "'${file.name}' is ${resolver.formatSize(file.length())}; the transcription limit is " +
                "${resolver.formatSize(MAX_INPUT_BYTES)}. Trim it into parts with media_edit and " +
                "transcribe each part."
        )
        else -> null
    }

    private fun resolveModel(modelArg: String?, settings: Settings, backend: SttBackend): String? {
        val explicit = (modelArg ?: settings.sttApiModel).trim()
        if (explicit.isNotBlank()) return explicit
        return runCatching { backend.listModels() }.getOrDefault(emptyList())
            .firstOrNull { it.category == ModelCategory.STT }?.id
    }
}
