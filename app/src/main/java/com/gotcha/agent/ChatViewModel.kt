package com.gotcha.agent

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gotcha.audio.AudioProvider
import com.gotcha.audio.AudioModel
import com.gotcha.audio.SttEngine
import com.gotcha.audio.TtsEngine
import com.gotcha.data.ChatHistoryRepository
import com.gotcha.data.Settings
import com.gotcha.data.SettingsRepository
import com.gotcha.llm.ChatMessage
import com.gotcha.data.ChatSession
import com.gotcha.llm.LLMClient
import com.gotcha.llm.ToolCall
import com.gotcha.llm.visionUserMessage
import com.gotcha.tools.AgentMode
import com.gotcha.tools.FileResolver
import com.gotcha.tools.ToolExecutor
import com.gotcha.tools.ToolRegistry
import com.gotcha.tools.ToolResult
import com.gotcha.ui.ConfirmationOverlay
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream

enum class MessageKind { USER, ASSISTANT, TOOL, ERROR }

/** Outcome of the sensitive-action confirmation step. */
private enum class ConfirmDecision { APPROVED, DENIED, TIMED_OUT }

data class UiMessage(
    val id: Long,
    val kind: MessageKind,
    val text: String,
    val imageBase64: String? = null
)

/** A batch of tool calls waiting for the user's confirm/deny (Phase 7). */
data class PendingConfirmation(
    val toolNames: List<String>,
    val description: String
)

/** A question the agent is asking the user mid-task. */
data class PendingQuestion(
    val question: String,
    val options: List<String> = emptyList(),
    val allowCustom: Boolean = true
)

data class ChatUiState(
    val messages: List<UiMessage> = emptyList(),
    val isBusy: Boolean = false,
    val activity: String? = null, // e.g. "Running: set_brightness…"
    val pendingConfirmation: PendingConfirmation? = null,
    val pendingQuestion: PendingQuestion? = null,
    val isConfigured: Boolean = false,
    val activeSessionId: String? = null,
    val activeAgent: AgentMode = AgentMode.OPERATOR,
    val contextUsagePercent: Float = 0f,
    // TTS / STT state
    val isListening: Boolean = false, // Android STT active
    val isRecording: Boolean = false, // API STT recording
    val ttsModels: List<AudioModel> = emptyList(),
    val sttModels: List<AudioModel> = emptyList()
)

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val TAG = "Gotcha"
    private val settingsRepository = SettingsRepository(application)
    private val historyRepository = ChatHistoryRepository(application)
    private val toolExecutor = ToolExecutor(application)
    private val confirmationOverlay = ConfirmationOverlay(application)
    private val json = Json { ignoreUnknownKeys = true }

    private var settings: Settings = Settings()
    private var client: LLMClient? = null
    /** True when the most recent user message was sent via voice (STT). */
    @Volatile
    private var lastInputWasVoice = false
    private val ttsEngine: TtsEngine = TtsEngine(
        getApplication(), settings.ttsApiBaseUrl, settings.apiKey
    )
    private val sttEngine: SttEngine = SttEngine(
        getApplication(), settings.sttApiBaseUrl, settings.apiKey
    )

    /** Set by the Activity in onStart/onStop; drives whether confirmations use the overlay. */
    @Volatile
    private var appInForeground = true

    /** LLM-shaped history (excludes the system prompt, which is prepended per call). */
    private var llmHistory = mutableListOf<ChatMessage>()
    private var activeSessionId: String? = null
    private var activeSessionTokenCount: Int = 0
    private var nextId = 0L
    private var confirmationGate: CompletableDeferred<Boolean>? = null
    private var questionGate: CompletableDeferred<String>? = null
    private var permissionGate: CompletableDeferred<Boolean>? = null
    private var agentJob: Job? = null

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private val _sessions = MutableStateFlow<List<ChatSession>>(emptyList())
    val sessions: StateFlow<List<ChatSession>> = _sessions.asStateFlow()

    /** Permission names (or ToolResult.WRITE_SETTINGS) the Activity should request. */
    private val _permissionRequests = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val permissionRequests: SharedFlow<String> = _permissionRequests.asSharedFlow()

    init {
        refreshSettings()
        viewModelScope.launch {
            val sessions = historyRepository.listSessions()
            if (sessions.isNotEmpty()) {
                val latest = sessions.first()
                activeSessionId = latest.id
                activeSessionTokenCount = latest.tokenCount
                llmHistory.addAll(latest.messages)
            } else {
                activeSessionId = java.util.UUID.randomUUID().toString()
                activeSessionTokenCount = 0
            }
            setupWorkingDir(activeSessionId!!)
            _uiState.update { it.copy(activeSessionId = activeSessionId) }
            updateContextUsage()
            rebuildUiMessages()
            refreshSessions()
        }
    }

    private fun updateContextUsage() {
        val limit = settings.maxContextTokens.toFloat()
        val percent = if (limit > 0) activeSessionTokenCount.toFloat() / limit else 0f
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
        } else null
        ttsEngine.configureApi(settings.ttsApiBaseUrl, settings.apiKey)
        sttEngine.configureApi(settings.sttApiBaseUrl, settings.apiKey)
        _uiState.update { it.copy(isConfigured = settings.isConfigured) }
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
        llmHistory += msg
        appendUi(MessageKind.USER, msg.textContent.ifEmpty { "(image attached)" }, imageBase64)
        agentJob = viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true) }
            try {
                runToolLoop()
            } catch (e: CancellationException) {
                appendUi(MessageKind.ERROR, "Agent was interrupted by the user.")
            } finally {
                withContext(NonCancellable) {
                    saveCurrentSession()
                    _uiState.update { it.copy(isBusy = false, activity = null) }
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
            } else bitmap.width to bitmap.height

            val scaled = Bitmap.createScaledBitmap(bitmap, w, h, true)
            if (scaled != bitmap) bitmap.recycle()

            val output = ByteArrayOutputStream()
            scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, output)
            scaled.recycle()
            android.util.Base64.encodeToString(output.toByteArray(), android.util.Base64.NO_WRAP)
        } catch (e: Exception) {
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

    /** Called from MainActivity after the user grants or denies a runtime permission. */
    fun onPermissionResult(granted: Boolean) {
        permissionGate?.complete(granted)
        permissionGate = null
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
                    _permissionRequests.tryEmit(perm)
                    appendUi(MessageKind.ERROR, "Microphone permission needed for speech input.")
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
            AgentMode.MONITOR -> "[System: Switched to Monitor (read-only). You may now ONLY inspect and observe. No changes to the device are permitted.]"
            AgentMode.OPERATOR -> "[System: Switched to Operator. You are now permitted to make changes to the device.]"
        }
        llmHistory += ChatMessage(role = "system", content = JsonPrimitive(switchMsg))
        appendUi(MessageKind.ASSISTANT, switchMsg)
    }

    override fun onCleared() {
        confirmationOverlay.dismiss()
        super.onCleared()
    }

    fun clearChat() {
        if (_uiState.value.isBusy) return
        llmHistory.clear()
        nextId = 0
        activeSessionId = java.util.UUID.randomUUID().toString()
        activeSessionTokenCount = 0
        setupWorkingDir(activeSessionId!!)
        _uiState.update { it.copy(messages = emptyList(), activeSessionId = activeSessionId) }
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
                    llmHistory.clear()
                    llmHistory.addAll(session.messages)
                    activeSessionId = session.id
                    activeSessionTokenCount = session.tokenCount
                    nextId = 0
                    setupWorkingDir(session.id)
                    updateContextUsage()
                    rebuildUiMessages()
                }
            }
        }
    }

    fun deleteSession(id: String) {
        viewModelScope.launch {
            historyRepository.deleteSession(id)
            if (activeSessionId == id) {
                clearChat()
            }
            refreshSessions()
        }
    }

    private suspend fun saveCurrentSession() {
        val id = activeSessionId ?: return
        val title = llmHistory.firstOrNull { it.role == "user" }?.textContent?.take(30) ?: "New Chat"
        historyRepository.saveSession(ChatSession(id, title, System.currentTimeMillis(), llmHistory.toList(), activeSessionTokenCount))
    }

    private suspend fun checkAndCompactHistory(llm: LLMClient) {
        val threshold = (settings.maxContextTokens * 0.8).toInt()
        if (activeSessionTokenCount <= threshold) return

        // Pop the latest user message so it isn't lost in the compaction summary
        val lastMessage = llmHistory.lastOrNull()
        val preserveLast = if (lastMessage?.role == "user") {
            llmHistory.removeLast()
            lastMessage
        } else null

        _uiState.update { it.copy(activity = "Compacting history…") }
        val compactionSystem = ChatMessage(
            role = "system",
            content = JsonPrimitive("You are an advanced context compaction agent. Your task is to compress the preceding conversation history into a highly dense, structured continuation summary. You must preserve critical context, decisions, and codebase states while eliminating conversational filler and repetitive tool logs.\n\nGenerate a structured summary containing exactly the following sections:\n1. **Goal**: What is the ultimate objective of this engineering session?\n2. **Instructions & Constraints**: What specific guidelines, patterns, user preferences, or technical limitations have been established?\n3. **Discoveries & Architecture**: What have we learned about the codebase? Detail any symbol mappings, logic structures, or debugging conclusions.\n4. **Accomplished**: What changes have already been completely implemented, verified, or fixed?\n5. **Relevant Files**: Which files are currently being modified or are active in the workspace?\n\nCRITICAL: Do not lose technical specifics, user-stated constraints, or deep investigation states.\n\nContinue if you have next steps, or stop and ask for clarification if you are unsure how to proceed.")
        )

        val historyText = trimmedHistory().joinToString("\n\n") { msg ->
            val role = msg.role.uppercase()
            val text = msg.textContent.ifEmpty {
                if (!msg.toolCalls.isNullOrEmpty()) {
                    "Called tools: " + msg.toolCalls.joinToString(", ") { it.function.name }
                } else ""
            }
            "[$role]: $text"
        }

        val requestMessage = ChatMessage(
            role = "user",
            content = JsonPrimitive("Please summarize the following conversation history according to the system prompt instructions:\n\n$historyText")
        )

        try {
            val response = llm.chat(listOf(compactionSystem, requestMessage))
            val summary = response.choices.firstOrNull()?.message?.textContent
            if (!summary.isNullOrBlank()) {
                llmHistory.clear()
                llmHistory.add(ChatMessage(role = "assistant", content = JsonPrimitive(summary)))
                if (preserveLast != null) {
                    llmHistory.add(preserveLast)
                }
                // The new token count is roughly the size of the summary + preserved message
                val newTokensApprox = (summary.length / 4) + ((preserveLast?.textContent?.length ?: 0) / 4)
                activeSessionTokenCount = newTokensApprox
                updateContextUsage()
                // Show the compacted message in the chat UI as an assistant message
                appendUi(MessageKind.ASSISTANT, "[System: History Compacted]\n$summary")
            } else if (preserveLast != null) {
                llmHistory.add(preserveLast)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // If compaction fails, restore the user message
            if (preserveLast != null) {
                llmHistory.add(preserveLast)
            }
        }
    }

    private suspend fun runToolLoop() {
        val llm = client ?: return
        val agent = _uiState.value.activeAgent
        checkAndCompactHistory(llm)
        repeat(settings.maxToolRounds) { iteration ->
            if (iteration > 0) delay(INTER_CALL_DELAY_MS) // throttle (PRD §11.2 #7)
            _uiState.update { it.copy(activity = "Thinking…") }

            val response = try {
                llm.chat(systemPrompt(agent) + trimmedHistory(), ToolRegistry.toolsForAgent(agent))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                appendUi(MessageKind.ERROR, friendlyError(e))
                return
            }
            
            response.usage?.totalTokens?.let {
                activeSessionTokenCount = it
                updateContextUsage()
            }

            val message = response.choices.firstOrNull()?.message
            if (message == null) {
                appendUi(MessageKind.ERROR, "The model returned an empty response.")
                return
            }

            val toolCalls = message.toolCalls.orEmpty()
            if (toolCalls.isEmpty()) {
                val content = message.textContent.ifEmpty { "(no reply)" }
                llmHistory += ChatMessage(role = "assistant", content = JsonPrimitive(content))
                appendUi(MessageKind.ASSISTANT, content)
                val shouldRead = lastInputWasVoice ||
                    (settings.autoReadReplies && settings.ttsProvider != AudioProvider.NONE)
                if (shouldRead && settings.ttsProvider != AudioProvider.NONE) {
                    speak(content)
                }
                lastInputWasVoice = false
                return
            }

            llmHistory += message
            if (message.hasText) {
                appendUi(MessageKind.ASSISTANT, message.textContent)
            }

            val decision = requestConfirmation(toolCalls)
            suspend fun executeToolCalls() {
                for (call in toolCalls) {
                    var result = when (decision) {
                        ConfirmDecision.APPROVED -> executeCall(call)
                        ConfirmDecision.DENIED ->
                            ToolResult.error("The user declined to run '${call.function.name}'. Do not retry.")
                        ConfirmDecision.TIMED_OUT -> ToolResult.error(
                            "Confirmation for '${call.function.name}' timed out with no response. " +
                                "Do not retry automatically; tell the user to ask again when ready."
                        )
                    }

                    // Permission gate: if the tool needs a runtime permission (not a special marker),
                    // emit the request, suspend until the user responds, and retry if granted.
                    val perm = result.needsPermission
                    if (perm != null && !perm.startsWith("special:")) {
                        Log.d(TAG, "Permission needed: $perm, waiting for user…")
                        _permissionRequests.tryEmit(perm)
                        val gate = CompletableDeferred<Boolean>()
                        permissionGate = gate
                        val granted = withTimeoutOrNull(120_000L) { gate.await() } ?: false
                        permissionGate = null
                        Log.d(TAG, "Permission $perm -> granted=$granted")
                        if (granted) {
                            result = executeCall(call)
                            // If the granted permission was a storage permission,
                            // ensure the per-chat working directory now exists.
                            setupWorkingDir(activeSessionId!!)
                        } else {
                            result = ToolResult(false, "${result.message} Permission was not granted.")
                        }
                    }

                    if (result.success && result.message.startsWith("IMAGE_DATA:")) {
                        handleImageResult(call, result)
                    } else if (result.success && result.message.startsWith("BINARY_DATA:")) {
                        handleBinaryResult(call, result)
                    } else if (result.success && result.message.startsWith("QUESTION:")) {
                        handleQuestionResult(call, result)
                    } else if (result.success && result.message.startsWith("CONFIRM_UNINSTALL:")) {
                        handleUninstallConfirm(call, result)
                    } else {
                        llmHistory += ChatMessage(
                            role = "tool",
                            content = JsonPrimitive(result.message),
                            toolCallId = call.id
                        )
                        appendUi(
                            if (result.success) MessageKind.TOOL else MessageKind.ERROR,
                            "${call.function.name}: ${result.message}"
                        )
                    }
                    // Automatic screenshot injection: when read_screen succeeds,
                    // capture a screenshot and inject it as vision context so the LLM
                    // can "see" the screen alongside the accessibility text dump.
                    if (result.success && call.function.name == "read_screen") {
                        val screenshotBase64 = captureScreenshotBase64()
                        if (screenshotBase64 != null) {
                            val screenText = result.message.take(500)
                            val visionMsg = visionUserMessage(
                                "Screen text:\n$screenText\n\nThe assistant also captured a screenshot. " +
                                    "Use it together with the screen text to understand the current screen.",
                                screenshotBase64, "png"
                            )
                            llmHistory.add(visionMsg)
                            appendUi(MessageKind.ASSISTANT, "[Screenshot captured for visual context]")
                        }
                    }
                    // Special-access markers: emit and continue (no wait needed since they open Settings)
                    if (perm != null && perm.startsWith("special:")) {
                        _permissionRequests.tryEmit(perm)
                    }
                }
            }
            executeToolCalls()
            saveCurrentSession()
        }
        appendUi(
            MessageKind.ERROR,
            "Stopped after ${settings.maxToolRounds} tool rounds to avoid an infinite loop."
        )
    }

    /**
     * Gates sensitive tool calls behind user approval. When Gotcha is in the
     * foreground the in-app Compose dialog is used; when it has been backgrounded (e.g.
     * after `open_app` launched another app) a floating overlay is shown instead so the
     * prompt is visible over that app rather than hidden behind it. Either way the wait
     * is bounded by [CONFIRM_TIMEOUT_MS] so the tool loop can never hang indefinitely.
     */
    private suspend fun requestConfirmation(toolCalls: List<ToolCall>): ConfirmDecision {
        // Monitor mode tools are read-only — no confirmation needed
        if (_uiState.value.activeAgent == AgentMode.MONITOR) return ConfirmDecision.APPROVED
        if (!settings.confirmSensitiveActions) return ConfirmDecision.APPROVED
        val sensitive = toolCalls.filter { ToolRegistry.isSensitive(it.function.name) }
        if (sensitive.isEmpty()) return ConfirmDecision.APPROVED

        val names = sensitive.map { it.function.name }
        val description = sensitive.joinToString("\n") { c ->
            "${c.function.name}(${c.function.arguments.take(200)})"
        }

        val gate = CompletableDeferred<Boolean>()
        confirmationGate = gate

        val useOverlay = !appInForeground && confirmationOverlay.canShow()
        if (useOverlay) {
            confirmationOverlay.show(
                summary = "Allow these actions?\n$description",
                onAllow = { confirmPendingActions(true) },
                onDeny = { confirmPendingActions(false) }
            )
        } else {
            _uiState.update {
                it.copy(
                    activity = null,
                    pendingConfirmation = PendingConfirmation(names, description)
                )
            }
        }

        val approved = withTimeoutOrNull(CONFIRM_TIMEOUT_MS) { gate.await() }

        confirmationOverlay.dismiss()
        _uiState.update { it.copy(pendingConfirmation = null) }
        confirmationGate = null

        return when (approved) {
            true -> ConfirmDecision.APPROVED
            false -> ConfirmDecision.DENIED
            null -> ConfirmDecision.TIMED_OUT
        }
    }

    /**
     * Handles a tool result that contains an image (prefixed with IMAGE_DATA:).
     * Injects the image as a vision user message so the LLM can "see" it,
     * and stores a clean text-only tool result in history for display.
     *
     * Format: IMAGE_DATA:<mime>:<width>x<height>:<bytes>:<filename>:<base64>
     */
    private fun handleImageResult(call: ToolCall, result: ToolResult) {
        val parts = result.message.split(":", limit = 6)
        if (parts.size < 6) {
            llmHistory += ChatMessage(
                role = "tool", content = JsonPrimitive("Failed to parse image data."),
                toolCallId = call.id
            )
            appendUi(MessageKind.ERROR, "${call.function.name}: Failed to parse image data.")
            return
        }
        val mime = parts[1]
        val dimensions = parts[2]
        val bytesStr = parts[3]
        val filename = parts[4]
        val base64 = parts[5]
        val fileSize = try { bytesStr.toLong() } catch (_: Exception) { 0L }
        val sizeDisplay = when {
            fileSize >= 1024 * 1024 -> "${fileSize / (1024 * 1024)} MB"
            fileSize >= 1024 -> "${fileSize / 1024} KB"
            else -> "$fileSize B"
        }

        // Inject vision user message so the model "sees" the image
        val format = mime.substringAfter("image/")
        val visionMsg = visionUserMessage(
            "The assistant read an image file: $filename ($dimensions, $sizeDisplay).",
            base64, format
        )
        llmHistory.add(visionMsg)

        // Store a clean text-only tool result for display/conversation context
        llmHistory += ChatMessage(
            role = "tool",
            content = JsonPrimitive("Read image: $filename ($dimensions, $sizeDisplay)"),
            toolCallId = call.id
        )
        appendUi(MessageKind.TOOL, "${call.function.name}: Read image $filename ($dimensions)")
    }

    /**
     * Handles a tool result that contains binary data (prefixed with BINARY_DATA:).
     * Format: BINARY_DATA:<mime>:<bytes>:<filename>:<base64>
     * Stores a text summary in history; the raw base64 is available if needed.
     */
    private fun handleBinaryResult(call: ToolCall, result: ToolResult) {
        val body = result.message.removePrefix("BINARY_DATA:")
        val parts = body.split(":", limit = 4)
        if (parts.size < 4) {
            llmHistory += ChatMessage(
                role = "tool", content = JsonPrimitive("Failed to parse binary data."),
                toolCallId = call.id
            )
            return
        }
        val mime = parts[0]
        val bytesStr = parts[1]
        val filename = parts[2]
        val fileSize = try { bytesStr.toLong() } catch (_: Exception) { 0L }
        val sizeDisplay = when {
            fileSize >= 1024 * 1024 -> "${fileSize / (1024 * 1024)} MB"
            fileSize >= 1024 -> "${fileSize / 1024} KB"
            else -> "$fileSize B"
        }
        llmHistory += ChatMessage(
            role = "tool",
            content = JsonPrimitive("Read binary file: $filename ($mime, $sizeDisplay)"),
            toolCallId = call.id
        )
        appendUi(MessageKind.TOOL, "${call.function.name}: Read $filename ($mime)")
    }

    /**
     * Handles a tool result that contains a question (prefixed with QUESTION:).
     * Shows the question to the user in a dialog, waits for their answer,
     * and returns the answer as the tool result.
     *
     * Format: QUESTION:<question>\n--OPTIONS--\nopt1\nopt2\n--ALLOW_CUSTOM--\ntrue
     */
    private suspend fun handleQuestionResult(call: ToolCall, result: ToolResult) {
        val body = result.message.removePrefix("QUESTION:")
        val question: String
        val options: List<String>
        val allowCustom: Boolean

        val optionsMarker = "\n--OPTIONS--\n"
        val customMarker = "\n--ALLOW_CUSTOM--\n"

        if (body.contains(optionsMarker)) {
            val before = body.substringBefore(optionsMarker)
            val after = body.substringAfter(optionsMarker)
            question = before
            if (after.contains(customMarker)) {
                options = after.substringBefore(customMarker).split("\n").filter { it.isNotBlank() }
                allowCustom = after.substringAfter(customMarker).trim().toBooleanStrictOrNull() ?: true
            } else {
                options = after.split("\n").filter { it.isNotBlank() }
                allowCustom = true
            }
        } else {
            question = body.substringBefore(customMarker)
            allowCustom = body.substringAfter(customMarker).trim().toBooleanStrictOrNull() ?: true
            options = emptyList()
        }

        val gate = CompletableDeferred<String>()
        questionGate = gate
        _uiState.update { it.copy(activity = null, pendingQuestion = PendingQuestion(question, options, allowCustom)) }

        val answer = withTimeoutOrNull(120_000L) { gate.await() } ?: ""

        _uiState.update { it.copy(pendingQuestion = null) }
        questionGate = null

        val displayAnswer = answer.ifBlank { "(no response)" }
        appendUi(MessageKind.TOOL, "${call.function.name}: User answered: $displayAnswer")
        llmHistory += ChatMessage(
            role = "tool",
            content = JsonPrimitive("The user answered: $displayAnswer"),
            toolCallId = call.id
        )
    }

    /**
     * Handles a tool result prefixed with CONFIRM_UNINSTALL:label:package.
     * Shows a confirmation dialog, and if approved, executes the actual uninstall.
     */
    private suspend fun handleUninstallConfirm(call: ToolCall, result: ToolResult) {
        val body = result.message.removePrefix("CONFIRM_UNINSTALL:")
        val parts = body.split(":", limit = 2)
        if (parts.size < 2) {
            llmHistory += ChatMessage(
                role = "tool", content = JsonPrimitive("Failed to parse uninstall confirmation."),
                toolCallId = call.id
            )
            appendUi(MessageKind.ERROR, "${call.function.name}: Failed to parse uninstall confirmation.")
            return
        }
        val label = parts[0]
        val pkg = parts[1]
        Log.d(TAG, "handleUninstallConfirm: label=$label pkg=$pkg")
        val description = "Request to uninstall \"$label\". After you approve, a system dialog will open — you must tap \"OK\" in that dialog to complete the removal. This action is irreversible."
        val gate = CompletableDeferred<Boolean>()
        confirmationGate = gate
        _uiState.update { it.copy(activity = null, pendingConfirmation = PendingConfirmation(listOf("uninstall_app"), description)) }

        val approved = withTimeoutOrNull(120_000L) { gate.await() } ?: false
        confirmationOverlay.dismiss()
        _uiState.update { it.copy(pendingConfirmation = null) }
        confirmationGate = null

        if (approved) {
            val actualResult = toolExecutor.executeUninstall(pkg)
            llmHistory += ChatMessage(
                role = "tool",
                content = JsonPrimitive(actualResult.message),
                toolCallId = call.id
            )
            appendUi(
                if (actualResult.success) MessageKind.TOOL else MessageKind.ERROR,
                "Uninstalling $label: ${actualResult.message}"
            )
        } else {
            val msg = "User declined to uninstall $label."
            llmHistory += ChatMessage(
                role = "tool",
                content = JsonPrimitive(msg),
                toolCallId = call.id
            )
            appendUi(MessageKind.TOOL, "${call.function.name}: $msg")
        }
    }

    private suspend fun executeCall(call: ToolCall): ToolResult {
        _uiState.update { it.copy(activity = "Running: ${call.function.name}…") }
        val args: JsonObject = try {
            json.decodeFromString(JsonObject.serializer(), call.function.arguments.ifBlank { "{}" })
        } catch (e: Exception) {
            return ToolResult.error("Malformed tool arguments: ${call.function.arguments.take(200)}")
        }
        return toolExecutor.execute(call.function.name, args, _uiState.value.activeAgent)
    }

    /**
     * Sets up the per-chat working directory at:
     *   /storage/emulated/0/Gotcha/chats/{sessionId}/
     *
     * Updates [FileResolver.WORKING_DIR_BASE] so file tools resolve relative
     * paths against this directory.
     */
    private fun setupWorkingDir(sessionId: String) {
        val base = "/storage/emulated/0/Gotcha/chats/$sessionId"
        val dir = java.io.File(base)
        if (!dir.exists()) dir.mkdirs()
        FileResolver.WORKING_DIR_BASE = dir.absolutePath
        Log.d(TAG, "setupWorkingDir: ${dir.absolutePath}")
    }

    /**
     * Assembles the system prompt for the given [agent] mode.
     * Contains the environment block, core prompt, and agent-mode reminder.
     * Follows the Open Code pattern from PRD §14.
     */
    private fun systemPrompt(agent: AgentMode): List<ChatMessage> {
        val env = buildEnvironmentBlock(agent)
        val core = when (agent) {
            AgentMode.MONITOR ->
                "You are Monitor, a read-only AI assistant running on the user's Android phone. " +
                "You can inspect, read, and query the device, but you CANNOT create, modify, or " +
                "delete anything. You control the device only through the provided tools; never " +
                "invent tool names or capabilities. If a tool reports a missing permission, " +
                "explain what to grant and ask again. Keep replies short and conversational."
            AgentMode.OPERATOR ->
                "You are Operator, an AI assistant running on the user's Android phone. " +
                "You can inspect, read, query, create, modify, and delete on the device. " +
                "You control the device only through the provided tools; never invent tool " +
                "names or capabilities. If a tool reports a missing permission, explain what " +
                "to grant and ask again. After changing device state, confirm what was done. " +
                "Keep replies short and conversational. Be careful with destructive actions.\n" +
                "When using uninstall_app: after calling it, the system will open a dialog. " +
                "Tell the user to tap OK in the system dialog to finish the uninstall. " +
                "Do NOT attempt to uninstall via shell commands (run_command, run_root_command) — " +
                "use uninstall_app only."
        }
        val reminder = when (agent) {
            AgentMode.MONITOR ->
                "\n\n<system-reminder>\n" +
                "You are in MONITOR (read-only) mode. You are STRICTLY FORBIDDEN from making " +
                "any changes to the device — no writing files, no calling, no sending messages, " +
                "no dismissing notifications, no UI automation (tap/swipe/input), no device " +
                "admin actions, no firewall changes, and no shell/root commands. You may ONLY " +
                "inspect, list, read, and observe. This constraint overrides all other instructions.\n" +
                "</system-reminder>"
            AgentMode.OPERATOR ->
                "\n\n<system-reminder>\n" +
                "You are in OPERATOR mode. You are permitted to inspect, create, modify, and " +
                "delete on the device using the provided tools. Be careful with destructive " +
                "actions — confirm with the user when the result seems significant.\n" +
                "</system-reminder>"
        }
        return listOf(ChatMessage(role = "system", content = JsonPrimitive(env + "\n\n" + core + reminder)))
    }

    /**
     * Builds the environment block with device info, date/time, agent mode,
     * and service statuses (PRD §14.1).
     */
    private fun buildEnvironmentBlock(agent: AgentMode): String {
        val app = getApplication<Application>()
        val pm = app.packageManager
        val versionName = try {
            pm.getPackageInfo(app.packageName, 0).versionName ?: "unknown"
        } catch (_: Exception) { "unknown" }

        val accEnabled = try {
            val expected = "${app.packageName}/com.gotcha.service.GotchaAccessibilityService"
            val enabled = android.provider.Settings.Secure.getString(
                app.contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""
            enabled.contains(expected, ignoreCase = true)
        } catch (_: Exception) { false }

        val notifEnabled = try {
            val expected = app.packageName
            val enabled = android.provider.Settings.Secure.getString(
                app.contentResolver, "enabled_notification_listeners"
            ) ?: ""
            enabled.contains(expected, ignoreCase = true)
        } catch (_: Exception) { false }

        val deviceAdmin = try {
            val dpm = app.getSystemService(android.app.admin.DevicePolicyManager::class.java)
            dpm?.isAdminActive(
                android.content.ComponentName(app, com.gotcha.service.GotchaDeviceAdminReceiver::class.java)
            ) ?: false
        } catch (_: Exception) { false }

        val vpnActive = try {
            val cm = app.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
            cm?.getNetworkCapabilities(cm.activeNetwork)
                ?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) ?: false
        } catch (_: Exception) { false }

        val dateTime = java.text.SimpleDateFormat("EEE MMM dd yyyy, HH:mm:ss 'UTC'Z", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getDefault() }
            .format(java.util.Date())

        return buildString {
            appendLine("You are powered by the model named ${settings.model}.")
            appendLine("Here is some useful information about the environment you are running in:")
            appendLine("<env>")
            appendLine("  Device model: ${android.os.Build.MODEL} (${android.os.Build.MANUFACTURER})")
            appendLine("  Android version: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
            appendLine("  Current date/time: $dateTime")
            appendLine("  Active agent: ${agent.name}")
            appendLine("  Accessibility service enabled: ${if (accEnabled) "yes" else "no"}")
            appendLine("  Notification listener enabled: ${if (notifEnabled) "yes" else "no"}")
            appendLine("  Device admin active: ${if (deviceAdmin) "yes" else "no"}")
            appendLine("  VPN active: ${if (vpnActive) "yes" else "no"}")
            appendLine("  App version: $versionName")
            appendLine("  Working directory: ${FileResolver.WORKING_DIR_BASE}")
            appendLine("  (Relative paths in read_file/write_file/list_files resolve against the working directory.)")
            append("</env>")
        }
    }

    /** Trims history just in case compaction failed and we strictly need to fit it without splitting tool pairs. */
    private fun trimmedHistory(): List<ChatMessage> {
        // Fallback approximation since exact token counts of subsets are unknown
        val maxTokens = settings.maxContextTokens
        var currentTokens = 0
        var start = llmHistory.size - 1
        
        while (start >= 0) {
            currentTokens += llmHistory[start].textContent.length / 4
            if (currentTokens > maxTokens) {
                start++
                break
            }
            start--
        }
        
        if (start < 0) start = 0
        
        // Skip leading tool messages so we don't have an orphaned tool result
        while (start < llmHistory.size && llmHistory[start].role == "tool") start++
        
        val trimmed = llmHistory.subList(start, llmHistory.size)
        // Many LLM APIs strictly require history to start with a user message.
        // If our truncation or compaction leaves an assistant message first, prepend a dummy user message.
        if (trimmed.isNotEmpty() && trimmed.first().role == "assistant") {
            return listOf(ChatMessage(role = "user", content = JsonPrimitive("[System Note: Conversation context restored from previous turn]"))) + trimmed
        }
        return trimmed
    }

    private fun friendlyError(e: Exception): String = when {
        e is retrofit2.HttpException && e.code() == 401 ->
            "The API rejected the key (401). Check your API key in settings."
        e is retrofit2.HttpException ->
            "The API returned an error (HTTP ${e.code()}). ${e.message()}"
        e is java.io.IOException ->
            "Network problem: ${e.message ?: "could not reach the API"}. Check your connection."
        else -> "Something went wrong: ${e.message}"
    }

    private fun appendUi(kind: MessageKind, text: String, imageBase64: String? = null) {
        _uiState.update {
            it.copy(messages = it.messages + UiMessage(nextId++, kind, text, imageBase64))
        }
    }

    private fun rebuildUiMessages() {
        val rebuilt = llmHistory.mapNotNull { msg ->
            when {
                msg.role == "user" -> UiMessage(nextId++, MessageKind.USER, msg.textContent.ifEmpty { "(image attached)" })
                msg.role == "assistant" && msg.hasText ->
                    UiMessage(nextId++, MessageKind.ASSISTANT, msg.textContent)
                msg.role == "tool" -> UiMessage(nextId++, MessageKind.TOOL, msg.textContent.ifEmpty { "(no result)" })
                else -> null
            }
        }
        _uiState.update { it.copy(messages = rebuilt) }
    }

    /**
     * Capture a screenshot via the `screencap -p` shell command.
     * Returns the image as a base64-encoded PNG string, or null on failure.
     * The app runs as an unprivileged uid; on many devices `screencap` requires
     * the `DUMP` permission or root. This is best-effort.
     */
    private suspend fun captureScreenshotBase64(): String? {
        return try {
            val bytes = withContext(kotlinx.coroutines.Dispatchers.IO) {
                val process = java.lang.Runtime.getRuntime().exec("screencap -p")
                val b = process.inputStream.use { it.readBytes() }
                process.waitFor()
                b
            }
            if (bytes.isEmpty()) return null
            val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            val maxDim = 1024
            val (w, h) = if (bitmap.width > maxDim || bitmap.height > maxDim) {
                val ratio = minOf(maxDim.toFloat() / bitmap.width, maxDim.toFloat() / bitmap.height)
                (bitmap.width * ratio).toInt() to (bitmap.height * ratio).toInt()
            } else bitmap.width to bitmap.height
            val scaled = android.graphics.Bitmap.createScaledBitmap(bitmap, w, h, true)
            if (scaled != bitmap) bitmap.recycle()
            val output = java.io.ByteArrayOutputStream()
            scaled.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, output)
            scaled.recycle()
            android.util.Base64.encodeToString(output.toByteArray(), android.util.Base64.NO_WRAP)
        } catch (_: Exception) { null }
    }

    private companion object {
        const val INTER_CALL_DELAY_MS = 400L
        const val CONFIRM_TIMEOUT_MS = 60_000L
    }
}
