package com.gotcha.service

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.content.ContextCompat
import com.gotcha.agent.AgentEngine
import com.gotcha.agent.AgentEvents
import com.gotcha.agent.MessageKind
import com.gotcha.agent.PendingQuestion
import com.gotcha.agent.ScreenSnapshot
import com.gotcha.agent.friendlyAgentError
import com.gotcha.audio.AudioProvider
import com.gotcha.audio.SttEngine
import com.gotcha.audio.TtsEngine
import com.gotcha.data.ChatHistoryRepository
import com.gotcha.data.SettingsRepository
import com.gotcha.llm.ChatMessage
import com.gotcha.llm.LLMClient
import com.gotcha.llm.visionUserMessage
import com.gotcha.tools.AgentMode
import com.gotcha.tools.Category
import com.gotcha.tools.ToolCategories
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonPrimitive

enum class CallState { IDLE, STARTING, READY, LISTENING, THINKING, SPEAKING, WAITING_USER, PAUSED, ENDING }

data class CallTranscriptItem(val id: Long, val kind: MessageKind, val text: String)

/**
 * A push-to-talk voice "call" with the full agent. Mic is off by default.
 * User taps a mic button to start recording, taps again to stop and send.
 * The transcript goes through [AgentEngine] with tools, the reply is spoken
 * (TTS), and the mic returns to the off state — ready for the next tap.
 *
 * Call sessions persist to a separate "calls" directory (never the main chat
 * list) and are deleted — history and working dir — when the call ends.
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
    private var currentTurnJob: Job? = null
    private var nextTranscriptId = 0L

    /** Gate for [awaitQuestionAnswer]: completed when the user taps mic stop. */
    private var questionGate: CompletableDeferred<String>? = null

    @Volatile
    private var pendingReply: String? = null

    /** Fire-and-forget TTS narration for tool progress (preemptible). */
    private var narrationJob: Job? = null
    private var lastNarrationTimeMs = 0L
    private var lastNarratedCategory: Category? = null

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

    /** Set by the service: update the mic-button ring color during tool execution. */
    var onActionRingColor: (Int?) -> Unit = { }

    fun isActive(): Boolean = _state.value != CallState.IDLE && _state.value != CallState.ENDING

    /**
     * Start a new call. Returns false (after reporting through [onError])
     * when configuration or permissions prevent it.
     */
    fun startCall(): Boolean {
        if (isActive()) return false
        if (buildClient() == null) {
            onError("Set up your API key in Gotcha first.")
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
        newEngine.callMode = true
        newEngine.setupWorkingDir()
        engine = newEngine
        _state.value = CallState.STARTING
        scope.launch {
            speakText("Call started. I'm ready when you are.")
            _state.value = CallState.READY
        }
        return true
    }

    /**
     * Stop/interrupt the current turn. Cancels the agent, stops TTS/STT,
     * completes any pending question, and returns to [READY].
     * Mirrors the chat-mode Stop button (ChatViewModel.stopAgent()).
     */
    fun stopAgent() {
        if (!isActive() || _state.value == CallState.ENDING) return
        narrationJob?.cancel()
        narrationJob = null
        currentTurnJob?.cancel()
        currentTurnJob = null
        ttsEngine.stop()
        val s = settingsRepository.load()
        sttEngine.cancelListening(s.sttProvider)
        questionGate?.complete("")
        questionGate = null
        pendingReply = null
        onActionRingColor(null)
        _state.value = CallState.READY
    }

    /** End the call and delete its chat session + working directory. */
    fun endCall() {
        val endingEngine = engine ?: return
        _state.value = CallState.ENDING
        narrationJob?.cancel()
        narrationJob = null
        loopJob?.cancel()
        loopJob = null
        currentTurnJob?.cancel()
        currentTurnJob = null
        ttsEngine.stop()
        val s = settingsRepository.load()
        sttEngine.cancelListening(s.sttProvider)
        questionGate?.complete("")
        questionGate = null
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

    // ---- Push-to-talk: called from the UI ----

    /**
     * Start the mic. User must tap [stopMic] to stop recording and send.
     * Works from [READY] (normal turn) or [WAITING_USER] (answering a question).
     */
    fun startMic() {
        val current = _state.value
        if (current != CallState.READY && current != CallState.WAITING_USER) return
        val s = settingsRepository.load()
        val started = sttEngine.startListening(s.sttProvider)
        if (started) {
            _state.value = CallState.LISTENING
        }
    }

    /** Stop the mic and send the recording for transcription + agent processing. */
    fun stopMic() {
        if (_state.value != CallState.LISTENING) return
        currentTurnJob?.cancel()
        currentTurnJob = scope.launch {
            _state.value = CallState.THINKING
            val s = settingsRepository.load()
            val result = sttEngine.stopListeningAndTranscribe(s.sttProvider, s.sttApiModel, s.sttLanguage)
            val text = result.getOrDefault("")

            if (text.isBlank()) {
                _state.value = CallState.READY
                return@launch
            }

            val llmClient = buildClient()
            val navModel = s.navigatorModel.ifEmpty { s.model }
            val cleanedText = llmClient?.cleanText(text, navModel) ?: text

            addTranscript(MessageKind.USER, cleanedText)

            // Answering an agent question?
            questionGate?.let { gate ->
                questionGate = null
                gate.complete(cleanedText)
                return@launch
            }

            // Normal turn
            val eng = engine ?: return@launch
            eng.history += buildTurnMessage(cleanedText)
            pendingReply = null

            // Narrate start-of-turn to give the user immediate feedback
            narrate(pickTurnStartPhrase())
            onActionRingColor(Category.FOREGROUND.ringColorArgb)

            try {
                eng.run(AgentMode.OPERATOR)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val msg = friendlyAgentError(e)
                addTranscript(MessageKind.ERROR, msg)
                pendingReply = msg
            }

            val reply = pendingReply ?: return@launch
            _state.value = CallState.SPEAKING
            speakText(reply)
            triggerEndVibration()
            onActionRingColor(null)
            _state.value = CallState.READY
        }
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
        if (_state.value != CallState.THINKING) return
        val toolName = activity?.removePrefix("Running:")?.removeSuffix("…")?.trim()
        if (toolName != null && toolName != "Thinking") {
            val category = ToolCategories.classify(toolName)
            onActionRingColor(category.ringColorArgb)
            if (category.isNarratable && canNarrate(category)) {
                val msg = category.narration ?: return
                narrate(msg)
                lastNarrationTimeMs = System.currentTimeMillis()
                lastNarratedCategory = category
            }
        }
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
        // Narration for sub-agent steps (throttled, category-based)
        if (running != null && _state.value == CallState.THINKING) {
            val inferredTool = currentAction?.substringBefore("(")?.trim() ?: running
            val category = ToolCategories.classify(inferredTool)
            onActionRingColor(category.ringColorArgb)
            if (category.isNarratable && canNarrate(category) && !currentAction.isNullOrBlank()) {
                narrate(currentAction.take(60))
                lastNarrationTimeMs = System.currentTimeMillis()
                lastNarratedCategory = category
            }
        }
    }

    override fun onPermissionRequest(marker: String) {
        addTranscript(
            MessageKind.ERROR,
            "A permission is needed that can't be granted during a call — open Gotcha to grant it."
        )
    }

    /** The agent asked a question: speak it and wait for a PTT answer. */
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
        val gate = CompletableDeferred<String>()
        questionGate = gate
        val answer = withTimeoutOrNull(QUESTION_TIMEOUT_MS) { gate.await() } ?: ""
        if (answer.isNotBlank()) addTranscript(MessageKind.USER, answer)
        _state.value = CallState.READY
        return answer
    }

    /**
     * Show a visual confirmation overlay over all apps for destructive actions.
     * Denies on timeout after 90 seconds.
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
            s.ttsVoice.ifBlank {
                if (ttsEngine.apiTtsModels.isEmpty()) ttsEngine.refreshApiModels()
                ttsEngine.apiTtsModels.firstOrNull { it.id == s.ttsApiModel }?.defaultVoice ?: "af_heart"
            }
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
                apiKey = s.effectiveApiKey,
                baseUrl = s.effectiveBaseUrl,
                model = s.model,
                context = appContext,
                apiTimeoutSeconds = s.apiTimeoutSeconds
            )
        } else {
            null
        }
    }

    private fun narrate(text: String) {
        if (text.isBlank() || _state.value != CallState.THINKING) return
        val s = settingsRepository.load()
        if (s.ttsProvider == AudioProvider.NONE) return
        narrationJob?.cancel()
        narrationJob = scope.launch {
            val voice = if (s.ttsProvider == AudioProvider.API) {
                s.ttsVoice.ifBlank {
                    if (ttsEngine.apiTtsModels.isEmpty()) ttsEngine.refreshApiModels()
                    ttsEngine.apiTtsModels.firstOrNull { it.id == s.ttsApiModel }?.defaultVoice ?: "af_heart"
                }
            } else {
                ""
            }
            ttsEngine.speak(text, s.ttsProvider, s.ttsApiModel, voice)
        }
    }

    private fun canNarrate(category: Category): Boolean {
        val now = System.currentTimeMillis()
        return category != lastNarratedCategory || (now - lastNarrationTimeMs) > NARRATION_THROTTLE_MS
    }

    private fun pickTurnStartPhrase(): String {
        val total = turnStartPhrases.sumOf { it.second }
        var roll = kotlin.random.Random.nextFloat() * total
        for ((phrase, weight) in turnStartPhrases) {
            roll -= weight
            if (roll <= 0f) return phrase
        }
        return turnStartPhrases.last().first
    }

    private val turnStartPhrases = listOf(
        "Gotcha" to 5,
        "Let me look into that" to 2,
        "Hmm hmm" to 2,
        "Got it" to 2,
        "On it" to 1,
        "Working on it" to 1,
        "One moment" to 1,
        "Let me check" to 1,
        "I'm on it" to 1,
        "Sure thing" to 1,
        "Okay" to 1,
        "Alright" to 1,
        "Let me see" to 1,
        "Give me a second" to 1,
        "Hang on" to 1
    )

    private fun triggerEndVibration() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val vibrator = appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        if (vibrator?.hasVibrator() == true) {
            vibrator.vibrate(
                VibrationEffect.createWaveform(
                    longArrayOf(0, 50, 100, 50),
                    -1
                )
            )
        }
    }

    companion object {
        private const val NARRATION_THROTTLE_MS = 3_000L
        private const val CONFIRM_TIMEOUT_MS = 90_000L
        val CALLS_WORKING_ROOT: String get() = com.gotcha.data.GotchaStorage.callsRoot().absolutePath
        private const val CAPTURE_SETTLE_MS = 350L
        private const val QUESTION_TIMEOUT_MS = 30_000L
        private const val TOOL_TEXT_LIMIT = 300
    }
}
