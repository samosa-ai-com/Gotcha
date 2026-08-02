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
import com.gotcha.audio.CompletionFeedback
import com.gotcha.audio.SttEngine
import com.gotcha.audio.TtsEngine
import com.gotcha.data.ChatHistoryRepository
import com.gotcha.data.SettingsRepository
import com.gotcha.i18n.Language
import com.gotcha.i18n.SpokenPhrases
import com.gotcha.llm.ChatMessage
import com.gotcha.llm.LLMClient
import com.gotcha.llm.visionUserMessage
import com.gotcha.tools.AgentMode
import com.gotcha.tools.Category
import com.gotcha.tools.ToolCategories
import com.gotcha.ui.ConfirmationOverlay
import com.gotcha.ui.ScreenReadFlashOverlay
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
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

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
    private val screenReadFlash = ScreenReadFlashOverlay(appContext)

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

        val s = settingsRepository.load()
        val sttError = audioConfigError("Speech-to-text", s.sttProvider, s.effectiveSttBaseUrl, s.sttApiModel)
        if (sttError != null) {
            onError(sttError)
            return false
        }
        val ttsError = audioConfigError("Text-to-speech", s.ttsProvider, s.effectiveTtsBaseUrl, s.ttsApiModel)
        if (ttsError != null) {
            onError(ttsError)
            return false
        }

        sttEngine.configureApi(s.effectiveSttBaseUrl, s.effectiveSttApiKey)
        ttsEngine.configureApi(s.effectiveTtsBaseUrl, s.effectiveTtsApiKey)

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
            val language = Language.fromLabel(s.preferredLanguage)
            if (!speakText(SpokenPhrases.callStarted(language), language)) {
                reportError("Couldn't play voice audio — check your Text-to-Speech settings.")
            }
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
        screenReadFlash.dismiss()
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
        screenReadFlash.dismiss()
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
        sttEngine.configureApi(s.effectiveSttBaseUrl, s.effectiveSttApiKey)
        val started = sttEngine.startListening(
            s.sttProvider,
            currentLanguage()
        )
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
            sttEngine.configureApi(s.effectiveSttBaseUrl, s.effectiveSttApiKey)
            val language = Language.fromLabel(s.preferredLanguage)
            val sttLanguage = s.sttLanguage.ifBlank { language.iso639 }
            val result = sttEngine.stopListeningAndTranscribe(s.sttProvider, s.sttApiModel, sttLanguage)
            val text = result.getOrDefault("")

            if (text.isBlank()) {
                // "No speech detected" is a normal outcome, not a config error — only
                // surface a dialog for actual STT failures (e.g. a bad API key/model).
                val error = result.exceptionOrNull()
                if (error != null && error.message != "No speech detected") {
                    reportError(friendlyAgentError(error as? Exception ?: Exception(error.message)))
                }
                _state.value = CallState.READY
                return@launch
            }

            // API STT (Whisper-class) output is already punctuated and cased —
            // cleanText is redundant there and would cost an extra LLM round-trip.
            val cleanedText = if (s.sttProvider == AudioProvider.ANDROID) {
                val llmClient = buildClient()
                val navModel = s.navigatorModel.ifEmpty { s.model }
                llmClient?.cleanText(text, navModel, language) ?: text
            } else {
                text
            }

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
            narrate(pickTurnStartPhrase(language), language)
            onActionRingColor(Category.FOREGROUND.ringColorArgb)

            try {
                eng.run(AgentMode.OPERATOR)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val msg = friendlyAgentError(e)
                reportError(msg)
                pendingReply = msg
            }

            val reply = pendingReply ?: return@launch
            _state.value = CallState.SPEAKING
            if (speakText(reply, language)) {
                triggerEndVibration()
            } else {
                reportError("Couldn't play the voice reply — check your Text-to-Speech settings.")
            }
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
            // The model reads the screen from this per-turn capture on every
            // voice turn and usually answers without calling the read_screen
            // tool, so this — not the engine's inject path — is the screen read
            // the user actually sees. Flash the same "screen was read" pulse.
            onScreenReadDone()
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
        // be silent in a voice-first UI — speak them, but also surface them
        // visually (dialog) and haptically since the user may not be listening.
        if (kind == MessageKind.ERROR) {
            onError(text)
            triggerErrorVibration()
            if (pendingReply == null) {
                pendingReply = text
            }
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
        reportError("A permission is needed that can't be granted during a call — open Gotcha to grant it.")
    }

    override fun onScreenCaptureChrome(hide: Boolean) {
        // Never capture the pulse: drop any stale window before a capture starts.
        if (hide) screenReadFlash.dismiss()
        onCaptureChrome(hide)
    }

    override fun onScreenReadDone() {
        // Only flash during an active agent turn; the engine only runs in this
        // host's THINKING state.
        if (_state.value == CallState.THINKING) screenReadFlash.pulse()
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
        if (!speakText(prompt, currentLanguage())) {
            reportError("Couldn't play voice audio — check your Text-to-Speech settings.")
        }
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
        val language = currentLanguage()
        if (!speakText(SpokenPhrases.confirmationNeeded(language), language)) {
            reportError("Couldn't play voice audio — check your Text-to-Speech settings.")
        }
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

    /** Resolve the persisted [preferredLanguage] to a [Language]. */
    private fun currentLanguage(): Language =
        Language.fromLabel(settingsRepository.load().preferredLanguage)

    /** Surface an error the same way everywhere: transcript entry + dialog + haptic. */
    private fun reportError(message: String) {
        addTranscript(MessageKind.ERROR, message)
        onError(message)
        triggerErrorVibration()
    }

    /**
     * Speak with the configured TTS provider; suspends until speech finishes.
     * Returns false (without reporting — callers decide whether a given
     * utterance is important enough to surface) if playback failed, e.g. a
     * misconfigured API TTS provider. [language] is taken from the caller so
     * multiple speeches within one turn (e.g. start + reply) re-use the same
     * parsed value rather than re-loading settings and re-parsing.
     */
    private suspend fun speakText(text: String, language: Language): Boolean {
        val s = settingsRepository.load()
        if (s.ttsProvider == AudioProvider.NONE) return true
        ttsEngine.configureApi(s.effectiveTtsBaseUrl, s.effectiveTtsApiKey)
        val voice = if (s.ttsProvider.isApiBased()) {
            s.ttsVoice.ifBlank {
                if (ttsEngine.apiTtsModels.isEmpty()) ttsEngine.refreshApiModels()
                ttsEngine.apiTtsModels.firstOrNull { it.id == s.ttsApiModel }?.defaultVoiceFor(language) ?: "af_heart"
            }
        } else {
            ""
        }
        return ttsEngine.speak(
            text = text,
            provider = s.ttsProvider,
            apiModel = s.ttsApiModel,
            voice = voice,
            language = language
        )
    }

    /**
     * Null when [provider]/[baseUrl]/[model] can actually be used for a call;
     * otherwise a user-facing reason. A call is voice-first end to end, so an
     * unconfigured or broken STT/TTS setup must block it before it starts
     * rather than surface only once the user is already mid-call.
     */
    private fun audioConfigError(label: String, provider: AudioProvider, baseUrl: String, model: String): String? =
        when (provider) {
            AudioProvider.NONE -> "$label is not configured. Set it up in Gotcha → Settings → Speech (TTS / STT)."
            AudioProvider.SAMOSA_AI -> when {
                baseUrl.isBlank() ->
                    "$label Samosa AI is not configured. Sign in from Gotcha → Settings → Speech (TTS / STT)."
                model.isBlank() ->
                    "$label model is not selected. Choose one in Gotcha → Settings → Speech (TTS / STT)."
                else -> null
            }
            AudioProvider.API -> when {
                baseUrl.isBlank() || baseUrl.trim().toHttpUrlOrNull() == null ->
                    "$label API URL is missing or invalid. Fix it in Gotcha → Settings → Speech (TTS / STT)."
                model.isBlank() ->
                    "$label API model is not selected. Choose one in Gotcha → Settings → Speech (TTS / STT)."
                else -> null
            }
            AudioProvider.ANDROID -> null
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

    private fun narrate(text: String, language: Language = currentLanguage()) {
        if (text.isBlank() || _state.value != CallState.THINKING) return
        val s = settingsRepository.load()
        if (s.ttsProvider == AudioProvider.NONE) return
        ttsEngine.configureApi(s.effectiveTtsBaseUrl, s.effectiveTtsApiKey)
        narrationJob?.cancel()
        narrationJob = scope.launch {
            val voice = if (s.ttsProvider.isApiBased()) {
                s.ttsVoice.ifBlank {
                    if (ttsEngine.apiTtsModels.isEmpty()) ttsEngine.refreshApiModels()
                    ttsEngine.apiTtsModels.firstOrNull { it.id == s.ttsApiModel }?.defaultVoiceFor(language) ?: "af_heart"
                }
            } else {
                ""
            }
            ttsEngine.speak(text, s.ttsProvider, s.ttsApiModel, voice, language)
        }
    }

    private fun canNarrate(category: Category): Boolean {
        val now = System.currentTimeMillis()
        return category != lastNarratedCategory || (now - lastNarrationTimeMs) > NARRATION_THROTTLE_MS
    }

    private fun pickTurnStartPhrase(lang: Language): String {
        val phrases = SpokenPhrases.turnStart(lang)
        val total = phrases.sumOf { it.second }
        var roll = kotlin.random.Random.nextFloat() * total
        for ((phrase, weight) in phrases) {
            roll -= weight
            if (roll <= 0f) return phrase
        }
        return phrases.last().first
    }

    /** Buzz/chime the turn's end, subject to the user's notification settings. */
    private fun triggerEndVibration() {
        val s = settingsRepository.load()
        CompletionFeedback.replyArrived(
            context = appContext,
            vibrate = s.notifyVibrationEnabled,
            chime = s.notifyChimeEnabled
        )
    }

    private fun triggerErrorVibration() = CompletionFeedback.error(appContext)

    companion object {
        private const val NARRATION_THROTTLE_MS = 3_000L
        private const val CONFIRM_TIMEOUT_MS = 90_000L
        val CALLS_WORKING_ROOT: String get() = com.gotcha.data.GotchaStorage.callsRoot().absolutePath
        private const val CAPTURE_SETTLE_MS = 350L
        private const val QUESTION_TIMEOUT_MS = 30_000L
        private const val TOOL_TEXT_LIMIT = 300
    }
}
