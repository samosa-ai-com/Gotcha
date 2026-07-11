package com.gotcha.agent

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gotcha.data.ChatHistoryRepository
import com.gotcha.data.Settings
import com.gotcha.data.SettingsRepository
import com.gotcha.llm.ChatMessage
import com.gotcha.data.ChatSession
import com.gotcha.llm.LLMClient
import com.gotcha.llm.ToolCall
import com.gotcha.tools.ToolExecutor
import com.gotcha.tools.ToolRegistry
import com.gotcha.tools.ToolResult
import com.gotcha.ui.ConfirmationOverlay
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
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

enum class MessageKind { USER, ASSISTANT, TOOL, ERROR }

/** Outcome of the sensitive-action confirmation step. */
private enum class ConfirmDecision { APPROVED, DENIED, TIMED_OUT }

data class UiMessage(
    val id: Long,
    val kind: MessageKind,
    val text: String
)

/** A batch of tool calls waiting for the user's confirm/deny (Phase 7). */
data class PendingConfirmation(
    val toolNames: List<String>,
    val description: String
)

data class ChatUiState(
    val messages: List<UiMessage> = emptyList(),
    val isBusy: Boolean = false,
    val activity: String? = null, // e.g. "Running: toggle_dark_mode…"
    val pendingConfirmation: PendingConfirmation? = null,
    val isConfigured: Boolean = false,
    val activeSessionId: String? = null,
    val contextUsagePercent: Float = 0f
)

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsRepository = SettingsRepository(application)
    private val historyRepository = ChatHistoryRepository(application)
    private val toolExecutor = ToolExecutor(application)
    private val confirmationOverlay = ConfirmationOverlay(application)
    private val json = Json { ignoreUnknownKeys = true }

    private var settings: Settings = Settings()
    private var client: LLMClient? = null

    /** Set by the Activity in onStart/onStop; drives whether confirmations use the overlay. */
    @Volatile
    private var appInForeground = true

    /** LLM-shaped history (excludes the system prompt, which is prepended per call). */
    private var llmHistory = mutableListOf<ChatMessage>()
    private var activeSessionId: String? = null
    private var activeSessionTokenCount: Int = 0
    private var nextId = 0L
    private var confirmationGate: CompletableDeferred<Boolean>? = null
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
        _uiState.update { it.copy(isConfigured = settings.isConfigured) }
    }

    fun sendMessage(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _uiState.value.isBusy) return
        if (client == null) {
            appendUi(MessageKind.ERROR, "No API key configured. Open settings to add one.")
            return
        }
        llmHistory += ChatMessage(role = "user", content = trimmed)
        appendUi(MessageKind.USER, trimmed)
        agentJob = viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true) }
            try {
                runToolLoop()
            } catch (e: CancellationException) {
                appendUi(MessageKind.ERROR, "Agent was interrupted by the user.")
            } finally {
                saveCurrentSession()
                _uiState.update { it.copy(isBusy = false, activity = null) }
                agentJob = null
            }
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

    /** Called from the Activity's onStart/onStop so confirmations know if they'd be hidden. */
    fun setForeground(foreground: Boolean) {
        appInForeground = foreground
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
        val title = llmHistory.firstOrNull { it.role == "user" }?.content?.take(30) ?: "New Chat"
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
            content = "You are an advanced context compaction agent. Your task is to compress the preceding conversation history into a highly dense, structured continuation summary. You must preserve critical context, decisions, and codebase states while eliminating conversational filler and repetitive tool logs.\n\nGenerate a structured summary containing exactly the following sections:\n1. **Goal**: What is the ultimate objective of this engineering session?\n2. **Instructions & Constraints**: What specific guidelines, patterns, user preferences, or technical limitations have been established?\n3. **Discoveries & Architecture**: What have we learned about the codebase? Detail any symbol mappings, logic structures, or debugging conclusions.\n4. **Accomplished**: What changes have already been completely implemented, verified, or fixed?\n5. **Relevant Files**: Which files are currently being modified or are active in the workspace?\n\nCRITICAL: Do not lose technical specifics, user-stated constraints, or deep investigation states.\n\nContinue if you have next steps, or stop and ask for clarification if you are unsure how to proceed."
        )

        val historyText = trimmedHistory().joinToString("\n\n") { msg ->
            val role = msg.role.uppercase()
            val content = msg.content ?: if (!msg.toolCalls.isNullOrEmpty()) {
                "Called tools: " + msg.toolCalls.joinToString(", ") { it.function.name }
            } else ""
            "[$role]: $content"
        }

        val requestMessage = ChatMessage(
            role = "user",
            content = "Please summarize the following conversation history according to the system prompt instructions:\n\n$historyText"
        )

        try {
            val response = llm.chat(listOf(compactionSystem, requestMessage))
            val summary = response.choices.firstOrNull()?.message?.content
            if (!summary.isNullOrBlank()) {
                llmHistory.clear()
                llmHistory.add(ChatMessage(role = "assistant", content = summary))
                if (preserveLast != null) {
                    llmHistory.add(preserveLast)
                }
                // The new token count is roughly the size of the summary + preserved message
                val newTokensApprox = (summary.length / 4) + ((preserveLast?.content?.length ?: 0) / 4)
                activeSessionTokenCount = newTokensApprox
                updateContextUsage()
                // Show the compacted message in the chat UI as an assistant message
                appendUi(MessageKind.ASSISTANT, "[System: History Compacted]\n$summary")
            } else if (preserveLast != null) {
                llmHistory.add(preserveLast)
            }
        } catch (e: Exception) {
            // If compaction fails, restore the user message
            if (preserveLast != null) {
                llmHistory.add(preserveLast)
            }
        }
    }

    private suspend fun runToolLoop() {
        val llm = client ?: return
        checkAndCompactHistory(llm)
        repeat(settings.maxToolRounds) { iteration ->
            if (iteration > 0) delay(INTER_CALL_DELAY_MS) // throttle (PRD §11.2 #7)
            _uiState.update { it.copy(activity = "Thinking…") }

            val response = try {
                llm.chat(systemPrompt() + trimmedHistory(), ToolRegistry.allDefinitions())
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
                val content = message.content ?: "(no reply)"
                llmHistory += ChatMessage(role = "assistant", content = content)
                appendUi(MessageKind.ASSISTANT, content)
                return
            }

            llmHistory += message
            message.content?.takeIf { it.isNotBlank() }
                ?.let { appendUi(MessageKind.ASSISTANT, it) }

            val decision = requestConfirmation(toolCalls)
            for (call in toolCalls) {
                val result = when (decision) {
                    ConfirmDecision.APPROVED -> executeCall(call)
                    ConfirmDecision.DENIED ->
                        ToolResult.error("The user declined to run '${call.function.name}'. Do not retry.")
                    ConfirmDecision.TIMED_OUT -> ToolResult.error(
                        "Confirmation for '${call.function.name}' timed out with no response. " +
                            "Do not retry automatically; tell the user to ask again when ready."
                    )
                }
                llmHistory += ChatMessage(
                    role = "tool",
                    content = result.message,
                    toolCallId = call.id
                )
                appendUi(
                    if (result.success) MessageKind.TOOL else MessageKind.ERROR,
                    "${call.function.name}: ${result.message}"
                )
                result.needsPermission?.let { _permissionRequests.tryEmit(it) }
            }
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

    private suspend fun executeCall(call: ToolCall): ToolResult {
        _uiState.update { it.copy(activity = "Running: ${call.function.name}…") }
        val args: JsonObject = try {
            json.decodeFromString(JsonObject.serializer(), call.function.arguments.ifBlank { "{}" })
        } catch (e: Exception) {
            return ToolResult.error("Malformed tool arguments: ${call.function.arguments.take(200)}")
        }
        return toolExecutor.execute(call.function.name, args)
    }

    private fun systemPrompt() = listOf(
        ChatMessage(
            role = "system",
            content = "You are Gotcha, an assistant running on the user's Android phone. " +
                "You control the device only through the provided tools; never invent tool names or " +
                "capabilities. If a tool reports a missing permission, explain to the user what to " +
                "grant and suggest retrying. Keep replies short and conversational. " +
                "After changing device state, confirm what was done based on the tool results."
        )
    )

    /** Trims history just in case compaction failed and we strictly need to fit it without splitting tool pairs. */
    private fun trimmedHistory(): List<ChatMessage> {
        // Fallback approximation since exact token counts of subsets are unknown
        val maxTokens = settings.maxContextTokens
        var currentTokens = 0
        var start = llmHistory.size - 1
        
        while (start >= 0) {
            currentTokens += (llmHistory[start].content?.length ?: 0) / 4
            if (currentTokens > maxTokens) {
                start++
                break
            }
            start--
        }
        
        if (start < 0) start = 0
        
        // Skip leading tool messages so we don't have an orphaned tool result
        while (start < llmHistory.size && llmHistory[start].role == "tool") start++
        return llmHistory.subList(start, llmHistory.size)
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

    private fun appendUi(kind: MessageKind, text: String) {
        _uiState.update {
            it.copy(messages = it.messages + UiMessage(nextId++, kind, text))
        }
    }

    private fun rebuildUiMessages() {
        val rebuilt = llmHistory.mapNotNull { msg ->
            when {
                msg.role == "user" -> UiMessage(nextId++, MessageKind.USER, msg.content ?: "")
                msg.role == "assistant" && !msg.content.isNullOrBlank() ->
                    UiMessage(nextId++, MessageKind.ASSISTANT, msg.content)
                msg.role == "tool" -> UiMessage(nextId++, MessageKind.TOOL, msg.content ?: "")
                else -> null
            }
        }
        _uiState.update { it.copy(messages = rebuilt) }
    }

    private companion object {
        const val INTER_CALL_DELAY_MS = 400L
        const val CONFIRM_TIMEOUT_MS = 60_000L
    }
}
