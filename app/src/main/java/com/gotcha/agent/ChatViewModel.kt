package com.gotcha.agent

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gotcha.data.ChatHistoryRepository
import com.gotcha.data.Settings
import com.gotcha.data.SettingsRepository
import com.gotcha.llm.ChatMessage
import com.gotcha.llm.LLMClient
import com.gotcha.llm.ToolCall
import com.gotcha.tools.ToolExecutor
import com.gotcha.tools.ToolRegistry
import com.gotcha.tools.ToolResult
import com.gotcha.ui.ConfirmationOverlay
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
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
    val isConfigured: Boolean = false
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
    private val llmHistory = mutableListOf<ChatMessage>()
    private var nextId = 0L
    private var confirmationGate: CompletableDeferred<Boolean>? = null

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    /** Permission names (or ToolResult.WRITE_SETTINGS) the Activity should request. */
    private val _permissionRequests = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val permissionRequests: SharedFlow<String> = _permissionRequests.asSharedFlow()

    init {
        refreshSettings()
        viewModelScope.launch {
            llmHistory.addAll(historyRepository.load())
            rebuildUiMessages()
        }
    }

    /** Re-reads settings; call after the settings screen saves. */
    fun refreshSettings() {
        settings = settingsRepository.load()
        client = if (settings.isConfigured) {
            LLMClient(
                apiKey = settings.apiKey,
                baseUrl = settings.baseUrl,
                model = settings.model,
                context = getApplication()
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
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true) }
            try {
                runToolLoop()
            } finally {
                historyRepository.save(llmHistory)
                _uiState.update { it.copy(isBusy = false, activity = null) }
            }
        }
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
        _uiState.update { it.copy(messages = emptyList()) }
        viewModelScope.launch { historyRepository.clear() }
    }

    private suspend fun runToolLoop() {
        val llm = client ?: return
        repeat(MAX_ITERATIONS) { iteration ->
            if (iteration > 0) delay(INTER_CALL_DELAY_MS) // throttle (PRD §11.2 #7)
            _uiState.update { it.copy(activity = "Thinking…") }

            val response = try {
                llm.chat(systemPrompt() + trimmedHistory(), ToolRegistry.allDefinitions())
            } catch (e: Exception) {
                appendUi(MessageKind.ERROR, friendlyError(e))
                return
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
            historyRepository.save(llmHistory)
        }
        appendUi(
            MessageKind.ERROR,
            "Stopped after $MAX_ITERATIONS tool rounds to avoid an infinite loop."
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

    /** Keeps the prompt bounded (PRD §11.2 #6) without splitting a tool-call/result pair. */
    private fun trimmedHistory(): List<ChatMessage> {
        if (llmHistory.size <= MAX_HISTORY_MESSAGES) return llmHistory
        var start = llmHistory.size - MAX_HISTORY_MESSAGES
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
        const val MAX_ITERATIONS = 6
        const val MAX_HISTORY_MESSAGES = 40
        const val INTER_CALL_DELAY_MS = 400L
        const val CONFIRM_TIMEOUT_MS = 60_000L
    }
}
