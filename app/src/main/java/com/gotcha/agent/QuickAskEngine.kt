package com.gotcha.agent

import android.graphics.Bitmap
import android.os.Build
import com.gotcha.audio.AudioProvider
import com.gotcha.audio.SttEngine
import com.gotcha.audio.TtsEngine
import com.gotcha.data.SettingsRepository
import com.gotcha.llm.ChatMessage
import com.gotcha.llm.LLMClient
import com.gotcha.llm.visionUserMessage
import com.gotcha.service.GotchaAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayOutputStream

/**
 * A ViewModel-free, single-turn "quick ask" pipeline used by the assistive ball.
 *
 * Unlike [ChatViewModel]'s full agentic tool loop, this does exactly one round-trip:
 * transcribe speech → (optionally) capture the screen → one LLM call (no tools) →
 * speak the answer. It is intentionally stateless (no history is persisted).
 *
 * It reuses the same engines the chat UI uses: [SttEngine], [TtsEngine] and [LLMClient].
 * The [llmProvider] lambda lets the host rebuild the client after a settings change
 * (mirroring [ChatViewModel.refreshSettings]).
 */
class QuickAskEngine(
    private val settingsRepository: SettingsRepository,
    private val sttEngine: SttEngine,
    private val ttsEngine: TtsEngine,
    private val llmProvider: () -> LLMClient?
) {
    /** Stable session ID for prefix caching across consecutive quick-ask calls. */
    private val sessionId: String = "quickask_${java.util.UUID.randomUUID()}"

    private fun settings() = settingsRepository.load()

    /** True when speech input is available (a usable STT provider is configured). */
    fun canListen(): Boolean = when (settings().sttProvider) {
        AudioProvider.ANDROID -> true
        AudioProvider.API -> settings().sttApiBaseUrl.isNotBlank() && settings().sttApiModel.isNotBlank()
        AudioProvider.NONE -> false
    }

    /**
     * Begin capturing speech using the configured STT provider.
     * Returns false if recording could not be started.
     */
    fun startListening(): Boolean {
        return when (settings().sttProvider) {
            AudioProvider.ANDROID -> sttEngine.startAndroidListening()
            AudioProvider.API -> sttEngine.startRecording()
            AudioProvider.NONE -> false
        }
    }

    /**
     * Stop capturing speech and return the recognized transcript (empty on failure).
     * Mirrors the branching in [ChatViewModel.stopRecording].
     */
    suspend fun stopAndTranscribe(): String {
        return when (settings().sttProvider) {
            AudioProvider.ANDROID -> sttEngine.stopAndroidListening()
            AudioProvider.API -> {
                val audioFile = sttEngine.stopRecording() ?: return ""
                sttEngine.transcribeApi(audioFile, settings().sttApiModel).getOrDefault("")
            }
            AudioProvider.NONE -> ""
        }
    }

    /** True when the Gotcha accessibility service is enabled and bound. */
    fun isAccessibilityAvailable(): Boolean = GotchaAccessibilityService.instance != null

    /**
     * Capture the current screen as a downscaled JPEG base64 string via the
     * accessibility screenshot API (API 30+). Returns null if unavailable
     * (older API, service not bound, or capture failed).
     *
     * The accessibility screenshot API is rate-limited (roughly one call per second),
     * so a single failure is retried once after a short delay.
     */
    suspend fun captureScreenBase64(): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val service = GotchaAccessibilityService.instance ?: return null
        var bitmap = service.takeScreenshotBitmap()
        if (bitmap == null) {
            delay(1100) // clear the screenshot rate-limit window, then retry once
            bitmap = service.takeScreenshotBitmap()
        }
        if (bitmap == null) return null
        return withContext(Dispatchers.Default) {
            try {
                val maxDim = 1024
                val (w, h) = if (bitmap.width > maxDim || bitmap.height > maxDim) {
                    val ratio = minOf(maxDim.toFloat() / bitmap.width, maxDim.toFloat() / bitmap.height)
                    (bitmap.width * ratio).toInt() to (bitmap.height * ratio).toInt()
                } else bitmap.width to bitmap.height
                val scaled = Bitmap.createScaledBitmap(bitmap, w, h, true)
                if (scaled != bitmap) bitmap.recycle()
                val output = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, 85, output)
                scaled.recycle()
                android.util.Base64.encodeToString(output.toByteArray(), android.util.Base64.NO_WRAP)
            } catch (_: Exception) {
                null
            }
        }
    }

    /** Read on-screen text via the accessibility service, or null if unavailable. */
    fun captureScreenText(limit: Int = 60): String? {
        val service = GotchaAccessibilityService.instance ?: return null
        val lines = service.dumpScreenText(limit)
        return if (lines.isEmpty()) null else lines.joinToString("\n") { "- $it" }
    }

    /**
     * Run a single LLM turn for [question], optionally grounded in a screenshot and/or
     * on-screen text. Returns the assistant's answer text, or throws on network/API error.
     *
     * When [screenRequested] is true but neither a screenshot nor screen text was captured,
     * the model is told it could not read the screen — so it says so plainly instead of
     * hallucinating a generic screen.
     */
    suspend fun ask(
        question: String,
        screenshotBase64: String?,
        screenText: String?,
        screenRequested: Boolean = false
    ): String {
        val llm = llmProvider() ?: throw IllegalStateException("Not configured")

        val noScreenCaptured = screenshotBase64 == null && screenText.isNullOrBlank()
        val systemText = if (screenRequested && noScreenCaptured) {
            SYSTEM_PROMPT + "\n\nNOTE: The user asked about their screen, but no screenshot or " +
                "on-screen text could be captured this time. Tell them briefly that you " +
                "couldn't read the screen right now, then help as best you can. Do NOT invent or " +
                "guess what is on the screen."
        } else SYSTEM_PROMPT
        val system = ChatMessage(role = "system", content = JsonPrimitive(systemText))

        val userText = buildString {
            if (!screenText.isNullOrBlank()) {
                append("Current screen text:\n")
                append(screenText)
                append("\n\n")
            }
            append("Question: ")
            append(question)
        }

        val user = if (screenshotBase64 != null) {
            visionUserMessage(userText, screenshotBase64, "jpeg")
        } else {
            ChatMessage(role = "user", content = JsonPrimitive(userText))
        }

        val response = llm.chat(listOf(system, user), sessionId = sessionId)
        return response.choices.firstOrNull()?.message?.textContent?.ifBlank { "(no reply)" }
            ?: "(no reply)"
    }

    /** Speak [text] with the configured TTS provider (no-op when provider is NONE). */
    suspend fun speak(text: String) {
        val s = settings()
        if (s.ttsProvider == AudioProvider.NONE) return
        val voice = if (s.ttsProvider == AudioProvider.API) {
            if (ttsEngine.apiTtsModels.isEmpty()) ttsEngine.refreshApiModels()
            ttsEngine.apiTtsModels.firstOrNull { it.id == s.ttsApiModel }?.defaultVoice ?: "af_heart"
        } else ""
        ttsEngine.speak(
            text = text,
            provider = s.ttsProvider,
            apiModel = s.ttsApiModel,
            voice = voice
        )
    }

    fun stopSpeaking() {
        ttsEngine.stop()
    }

    private companion object {
        const val SYSTEM_PROMPT =
            "You are a helpful on-screen assistant running on the user's Android phone. " +
                "Answer the user's spoken question concisely and conversationally. " +
                "If they ask you to translate something, provide the translation. " +
                "When screen content is provided, base your answer on it. " +
                "Keep replies short since they will be read aloud."
    }
}
