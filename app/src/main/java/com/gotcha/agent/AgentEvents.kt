package com.gotcha.agent

enum class MessageKind { USER, ASSISTANT, TOOL, ERROR, SUBAGENT }

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

/**
 * Host callbacks for [AgentEngine]. The engine owns the LLM history and tool
 * loop; everything user-facing (message bubbles, activity spinners, dialogs,
 * TTS) is delegated through this interface so the engine can run inside the
 * in-app [ChatViewModel] or a background service equally well.
 */
interface AgentEvents {
    /** Append a message bubble to whatever transcript the host renders. */
    fun onUi(
        kind: MessageKind,
        text: String,
        imageBase64: String? = null,
        subAgentSteps: List<String> = emptyList(),
        reasoningContent: String? = null
    )

    /** Current activity line ("Thinking…", "Running: x…"); null clears it. */
    fun onActivity(activity: String?)

    /** Latest total-token usage reported by the API for this session. */
    fun onTokenCount(totalTokens: Int)

    /** Final non-tool assistant reply; the host decides whether to speak it. */
    fun onAssistantReply(text: String)

    /** Sub-agent progress for the host's status UI; both null when finished. */
    fun onSubAgentUpdate(running: String?, currentAction: String?)

    /** Special-access marker ("special:*") the host should surface/request. */
    fun onPermissionRequest(marker: String)

    /** Ask the user the agent's question; returns "" when unanswered. */
    suspend fun awaitQuestionAnswer(question: PendingQuestion): String

    /** Gate a destructive action on explicit user approval. */
    suspend fun awaitConfirmation(toolNames: List<String>, description: String): Boolean
}
