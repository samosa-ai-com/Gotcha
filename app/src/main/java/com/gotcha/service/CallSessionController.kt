package com.gotcha.service

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.gotcha.agent.AgentEngine
import com.gotcha.agent.AgentEvents
import com.gotcha.agent.MessageKind
import com.gotcha.agent.PendingQuestion
import com.gotcha.agent.ScreenSnapshot
import com.gotcha.agent.friendlyAgentError
import com.gotcha.audio.AudioProvider
import com.gotcha.audio.SttEngine
import com.gotcha.audio.SttOutcome
import com.gotcha.audio.TtsEngine
import com.gotcha.data.ChatHistoryRepository
import com.gotcha.data.SettingsRepository
import com.gotcha.llm.ChatMessage
import com.gotcha.llm.LLMClient
import com.gotcha.llm.visionUserMessage
import com.gotcha.tools.AgentMode
import com.gotcha.ui.ConfirmationOverlay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonPrimitive

enum class CallState { IDLE, STARTING, LISTENING, THINKING, SPEAKING, WAITING_USER, PAUSED, ENDING }

data class CallTranscriptItem(val id: Long, val kind: MessageKind, val text: String)

/**
 * A hands-free voice "call" with the full agent: mic listens (Android STT
 * endpointing) → transcript + screen context go through [AgentEngine] with
 * tools → the reply is spoken (TTS) → mic re-arms, until the call ends.
 *
 * Call sessions persist to a separate "calls" directory (never the main chat
 * list) and are deleted — history and working dir — when the call ends.
 * The loop is strictly sequential so the mic never records our own speech.
 */
@Suppress("TooManyFunctions")
class CallSessionController(
    private val appContext: Context,
    private val scope: CoroutineScope,
    private val settingsRepository: SettingsRepository,
    private val sttEngine: SttEngine,
    private val ttsEngine: TtsEngine
) : AgentEvents {

    private val callsRepo = ChatHistoryRepository(appContext, "calls")
    private val confirmationOverlay = ConfirmationOverlay(appContext)

    /** Survives service teardown so end-of-call deletion always completes. */
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var engine: AgentEngine? = null
    private var loopJob: Job? = null
    private var nextTranscriptId = 0L

    @Volatile
    private var pendingReply: String? = null

    private val _state = MutableStateFlow(CallState.IDLE)
    val state: StateFlow<CallState> = _state.asStateFlow()

    private val _transcript = MutableStateFlow<List<CallTranscriptItem>>(emptyList())
    val transcript: StateFlow<List<CallTranscriptItem>> = _transcript.asStateFlow()

    /** Engine activity / sub-agent progress for the chat window header. */
    private val _statusLine = MutableStateFlow<String?>(null)
    val statusLine: StateFlow<String?> = _statusLine.asStateFlow()

    /** Set by the service: hide/show the ball + chat window around screen capture. */
    var onCaptureChrome: (hide: Boolean) -> Unit = { }

    /** Set by the service: surface a start-call failure to the user. */
    var onError: (String) -> Unit = { }

    fun isActive(): Boolean = _state.value != CallState.IDLE

    /**
     * Start a new call. Returns false (after reporting through [onError])
     * when configuration or permissions prevent it.
     */
    fun startCall(): Boolean {
        if (isActive()) return false
        val settings = settingsRepository.load()
        if (buildClient() == null) {
            onError("Set up your API key in Gotcha first.")
            return false
        }
        if (settings.sttProvider != AudioProvider.ANDROID) {
            onError("Calls need Android speech recognition — switch the STT provider in Settings.")
            return false
        }
        val micGranted = ContextCompat.checkSelfPermission(
            appContext, android.Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!micGranted) {
            onError("Microphone permission not granted. Enable it in Gotcha → Settings → Permissions.")
            return false
        }

        val newEngine = AgentEngine(
            appContext = appContext,
            events = this,
            historyRepository = callsRepo,
            settingsProvider = { settingsRepository.load() },
            clientProvider = { buildClient() },
            workingDirRoot = CALLS_WORKING_ROOT
        )
        newEngine.sessionId = java.util.UUID.randomUUID().toString()
        newEngine.setupWorkingDir()
        engine = newEngine
        _state.value = CallState.STARTING
        loopJob = scope.launch {
            try {
                runCallLoop(newEngine)
            } catch (_: CancellationException) {
                // endCall() handles state + cleanup.
            }
        }
        return true
    }

    /** Pause: stop speech and listening, park the loop until [resume]. */
    fun pause() {
        if (_state.value == CallState.IDLE || _state.value == CallState.ENDING) return
        _state.value = CallState.PAUSED
        ttsEngine.stop()
        sttEngine.cancelListening()
    }

    /** Resume a paused call at the listening step. */
    fun resume() {
        if (_state.value == CallState.PAUSED) {
            _state.value = CallState.LISTENING
        }
    }

    /** End the call and delete its chat session + working directory. */
    fun endCall() {
        val endingEngine = engine ?: return
        _state.value = CallState.ENDING
        loopJob?.cancel()
        loopJob = null
        ttsEngine.stop()
        sttEngine.cancelListening()
        confirmationOverlay.dismiss()
        engine = null

        val id = endingEngine.sessionId
        cleanupScope.launch {
            if (id != null) {
                callsRepo.deleteSession(id)
                try {
                    java.io.File(CALLS_WORKING_ROOT, id).deleteRecursively()
                } catch (_: Exception) { }
            }
            _transcript.value = emptyList()
            _statusLine.value = null
            pendingReply = null
            _state.value = CallState.IDLE
        }
    }

    // ---- The call loop ----

    private suspend fun runCallLoop(engine: AgentEngine) {
        var consecutiveFailures = 0
        speakText("Call started. I'm listening.")
        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            awaitNotPaused()
            _state.value = CallState.LISTENING
            _statusLine.value = null
            val outcome = sttEngine.listenOnceAndroid()

            if (outcome is SttOutcome.Error && outcome.code == SttEngine.ERROR_CANCELLED) {
                continue // pause/end interrupted the mic; loop parks or dies above
            }
            val text = (outcome as? SttOutcome.Text)?.text?.trim().orEmpty()
            if (text.isEmpty()) {
                consecutiveFailures++
                if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                    consecutiveFailures = 0
                    speakText("I'll pause the call. Tap Start when you're ready.")
                    pause()
                } else if (outcome is SttOutcome.Error && !outcome.isBenign) {
                    delay(ERROR_RETRY_DELAY_MS) // recognizer hiccup; brief backoff
                }
                continue
            }
            consecutiveFailures = 0

            addTranscript(MessageKind.USER, text)
            _state.value = CallState.THINKING
            engine.history += buildTurnMessage(text)
            pendingReply = null
            try {
                engine.run(AgentMode.OPERATOR)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val msg = friendlyAgentError(e)
                addTranscript(MessageKind.ERROR, msg)
                pendingReply = msg
            }

            val reply = pendingReply ?: continue
            pendingReply = null
            awaitNotPaused()
            _state.value = CallState.SPEAKING
            speakText(reply)
        }
    }

    private suspend fun awaitNotPaused() {
        _state.first { it != CallState.PAUSED }
    }

    /**
     * Builds the user turn: current screen (screenshot + on-screen text via
     * the accessibility service, chrome hidden during capture) + what was said.
     */
    private suspend fun buildTurnMessage(text: String): ChatMessage {
        var screenshot: String? = null
        var screenText: String? = null
        if (ScreenSnapshot.isAvailable()) {
            onCaptureChrome(true)
            delay(CAPTURE_SETTLE_MS)
            screenshot = ScreenSnapshot.captureScreenBase64()
            screenText = ScreenSnapshot.captureScreenText()
            onCaptureChrome(false)
        }
        val userText = buildString {
            if (!screenText.isNullOrBlank()) {
                append("Current screen text:\n")
                append(screenText)
                append("\n\n")
            } else {
                append("(The current screen could not be captured this turn.)\n\n")
            }
            append("User said (voice call — reply briefly, it will be read aloud): ")
            append(text)
        }
        return if (screenshot != null) {
            visionUserMessage(userText, screenshot, "jpeg")
        } else {
            ChatMessage(role = "user", content = JsonPrimitive(userText))
        }
    }

    // ---- AgentEvents (engine → call UI) ----

    override fun onUi(
        kind: MessageKind,
        text: String,
        imageBase64: String?,
        subAgentSteps: List<String>,
        reasoningContent: String?
    ) {
        val display = if (kind == MessageKind.TOOL && text.length > TOOL_TEXT_LIMIT) {
            text.take(TOOL_TEXT_LIMIT) + "…"
        } else {
            text
        }
        addTranscript(kind, display)
        // Errors the engine reports internally (LLM failures) would otherwise
        // be silent in a voice-first UI — speak them as the turn's reply.
        if (kind == MessageKind.ERROR && pendingReply == null) {
            pendingReply = text
        }
    }

    override fun onActivity(activity: String?) {
        _statusLine.value = activity
    }

    override fun onTokenCount(totalTokens: Int) {
        // No context meter in the call window.
    }

    override fun onAssistantReply(text: String) {
        pendingReply = text // the loop speaks it after engine.run returns
    }

    override fun onSubAgentUpdate(running: String?, currentAction: String?) {
        _statusLine.value = running?.let { r ->
            if (currentAction.isNullOrBlank()) r else "$r — $currentAction"
        }
    }

    override fun onPermissionRequest(marker: String) {
        addTranscript(
            MessageKind.ERROR,
            "A permission is needed that can't be granted during a call — open Gotcha to grant it."
        )
    }

    /** The agent asked a question: speak it and take the answer by voice. */
    override suspend fun awaitQuestionAnswer(question: PendingQuestion): String {
        _state.value = CallState.WAITING_USER
        val prompt = buildString {
            append(question.question)
            if (question.options.isNotEmpty()) {
                append(" Options: ")
                append(question.options.joinToString(", "))
            }
        }
        addTranscript(MessageKind.ASSISTANT, prompt)
        speakText(prompt)
        val outcome = withTimeoutOrNull(QUESTION_TIMEOUT_MS) { sttEngine.listenOnceAndroid() }
        val answer = ((outcome as? SttOutcome.Text)?.text ?: "").trim()
        if (answer.isNotBlank()) addTranscript(MessageKind.USER, answer)
        _state.value = CallState.THINKING
        return answer
    }

    /**
     * Destructive actions still require a deliberate visual tap (not a
     * possibly-misheard "yes"): reuse the over-other-apps ConfirmationOverlay.
     */
    override suspend fun awaitConfirmation(toolNames: List<String>, description: String): Boolean {
        _state.value = CallState.WAITING_USER
        addTranscript(MessageKind.ASSISTANT, "Confirmation needed: $description")
        speakText("I need a confirmation — check the dialog on your screen.")
        val gate = CompletableDeferred<Boolean>()
        confirmationOverlay.show(
            summary = description,
            onAllow = { gate.complete(true) },
            onDeny = { gate.complete(false) }
        )
        val approved = withTimeoutOrNull(CONFIRM_TIMEOUT_MS) { gate.await() } ?: false
        confirmationOverlay.dismiss()
        _state.value = CallState.THINKING
        return approved
    }

    // ---- Helpers ----

    private fun addTranscript(kind: MessageKind, text: String) {
        _transcript.value = _transcript.value + CallTranscriptItem(nextTranscriptId++, kind, text)
    }

    /** Speak with the configured TTS provider; suspends until speech finishes. */
    private suspend fun speakText(text: String) {
        val s = settingsRepository.load()
        if (s.ttsProvider == AudioProvider.NONE) return
        val voice = if (s.ttsProvider == AudioProvider.API) {
            if (ttsEngine.apiTtsModels.isEmpty()) ttsEngine.refreshApiModels()
            ttsEngine.apiTtsModels.firstOrNull { it.id == s.ttsApiModel }?.defaultVoice ?: "af_heart"
        } else {
            ""
        }
        ttsEngine.speak(
            text = text,
            provider = s.ttsProvider,
            apiModel = s.ttsApiModel,
            voice = voice
        )
    }

    private fun buildClient(): LLMClient? {
        val s = settingsRepository.load()
        return if (s.isConfigured) {
            LLMClient(
                apiKey = s.apiKey,
                baseUrl = s.baseUrl,
                model = s.model,
                context = appContext,
                apiTimeoutSeconds = s.apiTimeoutSeconds
            )
        } else {
            null
        }
    }

    companion object {
        const val CALLS_WORKING_ROOT = "/storage/emulated/0/Gotcha/calls"
        const val MAX_CONSECUTIVE_FAILURES = 3
        private const val ERROR_RETRY_DELAY_MS = 600L
        private const val CAPTURE_SETTLE_MS = 350L
        private const val QUESTION_TIMEOUT_MS = 30_000L
        private const val CONFIRM_TIMEOUT_MS = 60_000L
        private const val TOOL_TEXT_LIMIT = 300
    }
}
