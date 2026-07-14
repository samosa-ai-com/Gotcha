package com.gotcha.agent

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gotcha.audio.AudioModel
import com.gotcha.audio.AudioProvider
import com.gotcha.audio.SttEngine
import com.gotcha.audio.TtsEngine
import com.gotcha.data.ChatHistoryRepository
import com.gotcha.data.ChatSession
import com.gotcha.data.Settings
import com.gotcha.data.SettingsRepository
import com.gotcha.llm.ChatMessage
import com.gotcha.llm.LLMClient
import com.gotcha.llm.visionUserMessage
import com.gotcha.tools.AgentMode
import com.gotcha.tools.ScreenPerception
import com.gotcha.ui.ConfirmationOverlay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayOutputStream

data class UiMessage(
    val id: Long,
    val kind: MessageKind,
    val text: String,
    val imageBase64: String? = null,
    val subAgentSteps: List<String> = emptyList(),
    val subAgentCollapsed: Boolean = true,
    val reasoningContent: String? = null
)

data class SubAgentStepUi(
    val action: String,
    val status: String,
    val detail: String = ""
)

data class ChatUiState(
    val messages: List<UiMessage> = emptyList(),
    val isBusy: Boolean = false,
    val activity: String? = null,
    val subAgentRunning: String? = null,
    val subAgentCurrentAction: String? = null,
    val pendingConfirmation: PendingConfirmation? = null,
    val pendingQuestion: PendingQuestion? = null,
    val isConfigured: Boolean = false,
    val activeSessionId: String? = null,
    val activeAgent: AgentMode = AgentMode.OPERATOR,
    val contextUsagePercent: Float = 0f,
    val isListening: Boolean = false,
    val isRecording: Boolean = false,
    val ttsModels: List<AudioModel> = emptyList(),
    val sttModels: List<AudioModel> = emptyList()
)

// In-app chat host: session/UI state, dialogs, and TTS/STT wiring. The agent
// loop itself lives in AgentEngine and reports back through AgentEvents.
@Suppress("TooManyFunctions")
class ChatViewModel(application: Application) : AndroidViewModel(application), AgentEvents {

    private val settingsRepository = SettingsRepository(application)
    private val historyRepository = ChatHistoryRepository(application)
    private val confirmationOverlay = ConfirmationOverlay(application)

    private var settings: Settings = Settings()
    private var client: LLMClient? = null

    /** True when the most recent user message was sent via voice (STT). */
    @Volatile
    private var lastInputWasVoice = false
    private val ttsEngine: TtsEngine = TtsEngine(
        getApplication(),
        settings.ttsApiBaseUrl,
        settings.apiKey
    )
    private val sttEngine: SttEngine = SttEngine(
        getApplication(),
        settings.sttApiBaseUrl,
        settings.apiKey
    )

    /** Set by the Activity in onStart/onStop; drives whether confirmations use the overlay. */
    @Volatile
    private var appInForeground = true

    private val agentEngine = AgentEngine(
        appContext = application,
        events = this,
        historyRepository = historyRepository,
        settingsProvider = { settings },
        clientProvider = { client }
    )

    private var nextId = 0L
    private var confirmationGate: CompletableDeferred<Boolean>? = null
    private var questionGate: CompletableDeferred<String>? = null
    private var agentJob: Job? = null

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private val _sessions = MutableStateFlow<List<ChatSession>>(emptyList())
    val sessions: StateFlow<List<ChatSession>> = _sessions.asStateFlow()

    /** Permission names (or ToolResult.WRITE_SETTINGS) the Activity should request. */
    private val _permissionRequests = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val permissionRequests: SharedFlow<String> = _permissionRequests.asSharedFlow()

    /** Exported chat markdown content the Activity should share. */
    private val _exportContent = MutableSharedFlow<String>(extraBufferCapacity = 2)
    val exportContent: SharedFlow<String> = _exportContent.asSharedFlow()

    init {
        ScreenPerception.appContext = application
        refreshSettings()
        viewModelScope.launch {
            // Always start on a fresh session so the home screen greets with an
            // empty chat; past sessions remain one tap away in the drawer.
            agentEngine.sessionId = java.util.UUID.randomUUID().toString()
            agentEngine.tokenCount = 0
            agentEngine.setupWorkingDir()
            _uiState.update { it.copy(activeSessionId = agentEngine.sessionId) }
            updateContextUsage()
            refreshSessions()
        }
    }

    // ---- AgentEvents (engine → UI) ----

    override fun onUi(
        kind: MessageKind,
        text: String,
        imageBase64: String?,
        subAgentSteps: List<String>,
        reasoningContent: String?
    ) {
        appendUi(kind, text, imageBase64, subAgentSteps, reasoningContent)
    }

    override fun onActivity(activity: String?) {
        _uiState.update { it.copy(activity = activity) }
    }

    override fun onTokenCount(totalTokens: Int) {
        updateContextUsage()
    }

    override fun onAssistantReply(text: String) {
        val shouldRead = lastInputWasVoice ||
            (settings.autoReadReplies && settings.ttsProvider != AudioProvider.NONE)
        if (shouldRead && settings.ttsProvider != AudioProvider.NONE) {
            speak(text)
        }
        lastInputWasVoice = false
    }

    override fun onSubAgentUpdate(running: String?, currentAction: String?) {
        _uiState.update { it.copy(subAgentRunning = running, subAgentCurrentAction = currentAction) }
    }

    override fun onPermissionRequest(marker: String) {
        _permissionRequests.tryEmit(marker)
    }

    override suspend fun awaitQuestionAnswer(question: PendingQuestion): String {
        val gate = CompletableDeferred<String>()
        questionGate = gate
        _uiState.update { it.copy(activity = null, pendingQuestion = question) }

        val answer = withTimeoutOrNull(GATE_TIMEOUT_MS) { gate.await() } ?: ""

        _uiState.update { it.copy(pendingQuestion = null) }
        questionGate = null
        return answer
    }

    override suspend fun awaitConfirmation(toolNames: List<String>, description: String): Boolean {
        val gate = CompletableDeferred<Boolean>()
        confirmationGate = gate
        _uiState.update {
            it.copy(activity = null, pendingConfirmation = PendingConfirmation(toolNames, description))
        }

        val approved = withTimeoutOrNull(GATE_TIMEOUT_MS) { gate.await() } ?: false
        confirmationOverlay.dismiss()
        _uiState.update { it.copy(pendingConfirmation = null) }
        confirmationGate = null
        return approved
    }

    // ---- Settings / models ----

    private fun updateContextUsage() {
        val limit = settings.maxContextTokens.toFloat()
        val percent = if (limit > 0) agentEngine.tokenCount.toFloat() / limit else 0f
        _uiState.update { it.copy(contextUsagePercent = percent.coerceIn(0f, 1f)) }
    }

    /** Re-reads settings; call after the settings screen saves. */
    fun refreshSettings() {
        settings = settingsRepository.load()
        client = if (settings.isConfigured) {
            LLMClient(
                apiKey = settings.apiKey,
                baseUrl = settings.baseUrl,
                model = settings.model,
                context = getApplication(),
                apiTimeoutSeconds = settings.apiTimeoutSeconds
            )
        } else {
            null
        }
        ttsEngine.configureApi(settings.ttsApiBaseUrl, settings.apiKey)
        sttEngine.configureApi(settings.sttApiBaseUrl, settings.apiKey)
        _uiState.update { it.copy(isConfigured = settings.isConfigured) }
    }

    suspend fun refreshChatModels(): Result<List<String>> {
        val cfg = settings
        if (!cfg.isConfigured) return Result.failure(Exception("API not configured"))
        val client = LLMClient(
            apiKey = cfg.apiKey,
            baseUrl = cfg.baseUrl,
            model = cfg.model,
            context = getApplication(),
            apiTimeoutSeconds = cfg.apiTimeoutSeconds
        )
        return client.listModels()
    }

    fun sendMessage(text: String, imageBase64: String? = null) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() && imageBase64 == null) return
        if (_uiState.value.isBusy) return
        if (client == null) {
            appendUi(MessageKind.ERROR, "No API key configured. Open settings to add one.")
            return
        }
        val msg = if (imageBase64 != null) {
            visionUserMessage(trimmed, imageBase64)
        } else {
            ChatMessage(role = "user", content = JsonPrimitive(trimmed))
        }
        agentEngine.history += msg
        appendUi(MessageKind.USER, msg.textContent.ifEmpty { "(image attached)" }, imageBase64)
        agentJob = viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true) }
            try {
                agentEngine.run(_uiState.value.activeAgent)
            } catch (_: CancellationException) {
                appendUi(MessageKind.ERROR, "Agent was interrupted by the user.")
            } finally {
                withContext(NonCancellable) {
                    agentEngine.saveCurrentSession()
                    _uiState.update {
                        it.copy(isBusy = false, activity = null, subAgentRunning = null, subAgentCurrentAction = null)
                    }
                    agentJob = null
                }
            }
        }
    }

    /** Load an image from a content:// URI, downscale, and return base64. */
    fun loadImageBase64(uri: Uri, maxDimension: Int = 1024): String? {
        return try {
            val resolver = getApplication<Application>().contentResolver
            val inputStream = resolver.openInputStream(uri) ?: return null
            val bytes = inputStream.use { it.readBytes() }

            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null

            val (w, h) = if (bitmap.width > maxDimension || bitmap.height > maxDimension) {
                val ratio = minOf(maxDimension.toFloat() / bitmap.width, maxDimension.toFloat() / bitmap.height)
                (bitmap.width * ratio).toInt() to (bitmap.height * ratio).toInt()
            } else {
                bitmap.width to bitmap.height
            }

            val scaled = Bitmap.createScaledBitmap(bitmap, w, h, true)
            if (scaled != bitmap) bitmap.recycle()

            val output = ByteArrayOutputStream()
            scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, output)
            scaled.recycle()
            android.util.Base64.encodeToString(output.toByteArray(), android.util.Base64.NO_WRAP)
        } catch (_: Exception) {
            null
        }
    }

    fun stopAgent() {
        agentJob?.cancel()
    }

    fun confirmPendingActions(approved: Boolean) {
        _uiState.update { it.copy(pendingConfirmation = null) }
        confirmationGate?.complete(approved)
        confirmationGate = null
    }

    fun submitAnswer(answer: String?) {
        _uiState.update { it.copy(pendingQuestion = null) }
        questionGate?.complete(answer ?: "")
        questionGate = null
    }

    /** Called from the Activity's onStart/onStop so confirmations know if they'd be hidden. */
    fun setForeground(foreground: Boolean) {
        appInForeground = foreground
    }

    /** Speak the given text aloud using the configured TTS provider. */
    fun speak(text: String) {
        viewModelScope.launch {
            val defaultVoice = _uiState.value.ttsModels
                .firstOrNull { it.id == settings.ttsApiModel }
                ?.defaultVoice ?: "af_heart"
            ttsEngine.speak(
                text = text,
                provider = settings.ttsProvider,
                apiModel = settings.ttsApiModel,
                voice = defaultVoice
            )
        }
    }

    /** Start listening for speech input using the configured STT provider. */
    fun startListening() {
        if (_uiState.value.isListening || _uiState.value.isRecording) return
        when (settings.sttProvider) {
            AudioProvider.ANDROID -> {
                val perm = android.Manifest.permission.RECORD_AUDIO
                val granted = androidx.core.content.ContextCompat.checkSelfPermission(
                    getApplication(), perm
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                if (!granted) {
                    appendUi(
                        MessageKind.ERROR,
                        "Microphone permission not granted. Enable it in Settings → Permissions."
                    )
                    return
                }
                val started = sttEngine.startAndroidListening()
                if (started) {
                    _uiState.update { it.copy(isListening = true) }
                } else {
                    appendUi(MessageKind.ERROR, "Failed to start speech recognition.")
                }
            }
            AudioProvider.API -> {
                if (settings.sttApiBaseUrl.isBlank()) {
                    appendUi(MessageKind.ERROR, "No STT API URL configured in settings.")
                    return
                }
                if (settings.sttApiModel.isBlank()) {
                    appendUi(MessageKind.ERROR, "No STT model selected. Refresh audio models in settings.")
                    return
                }
                val started = sttEngine.startRecording()
                if (started) {
                    _uiState.update { it.copy(isRecording = true) }
                } else {
                    appendUi(MessageKind.ERROR, "Failed to start recording.")
                }
            }
            AudioProvider.NONE -> {
                appendUi(MessageKind.ERROR, "No STT provider configured. Enable one in settings.")
            }
        }
    }

    /** Stop an ongoing recording (Android or API), transcribe, and send. */
    fun stopRecording() {
        viewModelScope.launch {
            val provider = settings.sttProvider
            if (provider == AudioProvider.API) {
                _uiState.update { it.copy(isRecording = false) }
                val audioFile = sttEngine.stopRecording()
                if (audioFile == null) {
                    appendUi(MessageKind.ERROR, "Failed to record audio.")
                    return@launch
                }
                appendUi(MessageKind.ASSISTANT, "[Transcribing speech…]")
                val transcript = sttEngine.transcribeApi(audioFile, settings.sttApiModel)
                    .onFailure { e -> appendUi(MessageKind.ERROR, "Transcription failed: ${e.message}") }
                    .getOrDefault("")
                if (transcript.isNotBlank()) {
                    lastInputWasVoice = true
                    sendMessage(transcript)
                }
            } else if (provider == AudioProvider.ANDROID) {
                _uiState.update { it.copy(isListening = false) }
                val transcript = sttEngine.stopAndroidListening()
                if (transcript.isNotBlank()) {
                    lastInputWasVoice = true
                    sendMessage(transcript)
                }
            }
        }
    }

    /** Refresh available TTS/STT models from the API. */
    fun refreshAudioModels() {
        viewModelScope.launch {
            val ttsModels = ttsEngine.refreshApiModels()
            val sttModels = sttEngine.refreshApiModels()
            _uiState.update { it.copy(ttsModels = ttsModels, sttModels = sttModels) }
        }
    }

    /** Switch between Monitor (read-only) and Operator (full) agent mode mid-conversation. */
    fun switchAgent() {
        val current = _uiState.value.activeAgent
        val next = when (current) {
            AgentMode.MONITOR -> AgentMode.OPERATOR
            AgentMode.OPERATOR -> AgentMode.MONITOR
        }
        _uiState.update { it.copy(activeAgent = next) }
        // Append a system message so the LLM knows the mode changed
        val switchMsg = when (next) {
            AgentMode.MONITOR ->
                "[System: Switched to Monitor (read-only). You may now ONLY inspect and observe. " +
                    "No changes to the device are permitted.]"
            AgentMode.OPERATOR -> "[System: Switched to Operator. You are now permitted to make changes to the device.]"
        }
        agentEngine.history += ChatMessage(role = "system", content = JsonPrimitive(switchMsg))
        appendUi(MessageKind.ASSISTANT, switchMsg)
    }

    override fun onCleared() {
        confirmationOverlay.dismiss()
        super.onCleared()
    }

    fun clearChat() {
        if (_uiState.value.isBusy) return
        agentEngine.history.clear()
        nextId = 0
        agentEngine.sessionId = java.util.UUID.randomUUID().toString()
        agentEngine.tokenCount = 0
        agentEngine.setupWorkingDir()
        _uiState.update { it.copy(messages = emptyList(), activeSessionId = agentEngine.sessionId) }
        updateContextUsage()
    }

    fun refreshSessions() {
        viewModelScope.launch {
            _sessions.value = historyRepository.listSessions()
        }
    }

    fun openSession(id: String?) {
        if (_uiState.value.isBusy) return
        viewModelScope.launch {
            if (id == null) {
                clearChat()
            } else {
                val session = historyRepository.loadSession(id)
                if (session != null) {
                    agentEngine.history.clear()
                    agentEngine.history.addAll(session.messages)
                    agentEngine.sessionId = session.id
                    agentEngine.tokenCount = session.tokenCount
                    nextId = 0
                    agentEngine.setupWorkingDir()
                    _uiState.update { it.copy(activeSessionId = session.id) }
                    updateContextUsage()
                    rebuildUiMessages()
                }
            }
        }
    }

    fun deleteSession(id: String) {
        viewModelScope.launch {
            historyRepository.deleteSession(id)
            if (agentEngine.sessionId == id) {
                clearChat()
            }
            refreshSessions()
        }
    }

    private fun appendUi(
        kind: MessageKind,
        text: String,
        imageBase64: String? = null,
        subAgentSteps: List<String> = emptyList(),
        reasoningContent: String? = null
    ) {
        _uiState.update {
            it.copy(
                messages = it.messages + UiMessage(
                    nextId++, kind, text, imageBase64, subAgentSteps, subAgentCollapsed = true, reasoningContent = reasoningContent
                )
            )
        }
    }

    private fun rebuildUiMessages() {
        val rebuilt = agentEngine.history.mapNotNull { msg ->
            val text = msg.textContent
            when {
                msg.role == "user" && (text.startsWith("[Screen State]") || text.startsWith("Screen text:")) -> {
                    val saved = if (text.contains("Saved to:")) " → " + text.substringAfter("Saved to:").trim() else ""
                    val msgText = if (text.startsWith("Screen text:")) {
                        "[Full-resolution screenshot captured]$saved"
                    } else {
                        "[Screenshot captured for visual context]"
                    }
                    UiMessage(nextId++, MessageKind.ASSISTANT, msgText)
                }
                msg.role == "user" -> UiMessage(nextId++, MessageKind.USER, text.ifEmpty { "(image attached)" })
                msg.role == "assistant" && text.isNotBlank() ->
                    UiMessage(nextId++, MessageKind.ASSISTANT, text, reasoningContent = msg.reasoningContent)
                msg.role == "assistant" && !msg.reasoningContent.isNullOrBlank() ->
                    UiMessage(nextId++, MessageKind.ASSISTANT, "(no reply)", reasoningContent = msg.reasoningContent)
                msg.role == "tool" && text.startsWith("SUBAGENT_STEPS:") -> {
                    val descEnd = text.indexOf('\n', "SUBAGENT_STEPS:".length)
                    val rest = if (descEnd > 0) {
                        text.substring(
                            descEnd + 1
                        )
                    } else {
                        text.substring("SUBAGENT_STEPS:".length)
                    }
                    val stepsMarker = "── Steps ──\n"
                    val resultMarker = "\n── Result ──\n"
                    val steps: List<String>
                    val answer: String
                    if (rest.startsWith(stepsMarker)) {
                        val afterSteps = rest.removePrefix(stepsMarker)
                        val resIdx = afterSteps.indexOf(resultMarker)
                        if (resIdx >= 0) {
                            steps = afterSteps.substring(0, resIdx).split("\n").filter { it.isNotBlank() }
                            answer = afterSteps.substring(resIdx + resultMarker.length)
                        } else {
                            steps = emptyList()
                            answer = afterSteps
                        }
                    } else {
                        steps = emptyList()
                        answer = rest
                    }
                    UiMessage(
                        nextId++,
                        MessageKind.SUBAGENT,
                        answer,
                        subAgentSteps = steps,
                        reasoningContent = msg.reasoningContent
                    )
                }
                msg.role == "tool" -> UiMessage(nextId++, MessageKind.TOOL, text.ifEmpty { "(no result)" })
                else -> null
            }
        }
        _uiState.update { it.copy(messages = rebuilt) }
    }

    fun exportChat() {
        val sessionId = agentEngine.sessionId ?: "unknown"
        val sb = StringBuilder()
        sb.appendLine("# Gotcha Chat Export")
        sb.appendLine("**Session:** $sessionId")
        sb.appendLine(
            "**Date:** ${java.text.SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss",
                java.util.Locale.US
            ).format(java.util.Date())}"
        )
        sb.appendLine("**Messages:** ${agentEngine.history.size}")
        sb.appendLine()
        sb.appendLine("---")
        sb.appendLine()

        for (msg in agentEngine.history) {
            val role = msg.role
            val text = msg.textContent

            when (role) {
                "user" -> {
                    val isVision = msg.content is JsonArray
                    sb.appendLine("### User")
                    if (text.isNotBlank()) sb.appendLine(text)
                    if (isVision) sb.appendLine("*(Image attached)*")
                    sb.appendLine()
                }
                "assistant" -> {
                    sb.appendLine("### Assistant")
                    if (text.isNotBlank()) sb.appendLine(text)
                    val calls = msg.toolCalls
                    if (!calls.isNullOrEmpty()) {
                        sb.appendLine()
                        sb.appendLine("**Called tools:**")
                        for (call in calls) {
                            sb.appendLine("- `${call.function.name}(${call.function.arguments.take(200)})`")
                        }
                    }
                    sb.appendLine()
                }
                "tool" -> {
                    if (text.startsWith("SUBAGENT_STEPS:")) {
                        appendSubAgentExport(sb, text)
                    } else {
                        sb.appendLine("### Tool Result")
                        sb.appendLine(text.ifEmpty { "(no result)" })
                    }
                    sb.appendLine()
                }
                "system" -> {
                    sb.appendLine("### System")
                    sb.appendLine(text.ifEmpty { "(system message)" })
                    sb.appendLine()
                }
            }
        }

        _exportContent.tryEmit(sb.toString())
    }

    /** Formats a SUBAGENT_STEPS tool message (description, steps, result) for chat export. */
    private fun appendSubAgentExport(sb: StringBuilder, text: String) {
        val descEnd = text.indexOf('\n', "SUBAGENT_STEPS:".length)
        val desc = if (descEnd > 0) {
            text.substring("SUBAGENT_STEPS:".length, descEnd)
        } else {
            text.substring("SUBAGENT_STEPS:".length)
        }
        sb.appendLine("### Sub-Agent: $desc")
        val rest = if (descEnd > 0) text.substring(descEnd + 1) else ""
        val stepsMarker = "── Steps ──\n"
        val resultMarker = "\n── Result ──\n"
        if (!rest.startsWith(stepsMarker)) {
            sb.appendLine(rest)
            return
        }
        val afterSteps = rest.removePrefix(stepsMarker)
        val resIdx = afterSteps.indexOf(resultMarker)
        if (resIdx < 0) {
            sb.appendLine(afterSteps)
            return
        }
        val steps = afterSteps.substring(0, resIdx).split("\n").filter { it.isNotBlank() }
        val answer = afterSteps.substring(resIdx + resultMarker.length)
        sb.appendLine()
        sb.appendLine("**Steps:**")
        for (s in steps) sb.appendLine("- $s")
        sb.appendLine()
        sb.appendLine("**Result:**")
        sb.appendLine(answer)
    }

    private companion object {
        const val GATE_TIMEOUT_MS = 120_000L
    }
}
