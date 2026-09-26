package com.gotcha.agent

import com.gotcha.data.RunSummary
import kotlinx.serialization.Serializable

@Serializable
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
@Suppress("TooManyFunctions")
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

    /**
     * A tool just reported that it needs the runtime [permission] it names.
     * The host should explain why and raise the system dialog, returning
     * whether the permission ended up granted — the engine retries the call
     * once when it did.
     *
     * Defaults to "can't ask here": a runtime dialog needs a foreground
     * Activity, which a voice call or a background run does not have. Those
     * hosts leave the tool's own error message as the answer.
     */
    suspend fun awaitPermissionGrant(permission: String): Boolean = false

    /**
     * History was just compacted: the host should drop its on-screen transcript
     * so pre-compaction bubbles are no longer shown. The compaction summary is
     * delivered immediately afterward via [onUi]. Default no-op for hosts that
     * don't render a persistent transcript.
     */
    fun onHistoryReset() {}

    /**
     * A run just ended with a structured record of what it did. The host may
     * surface a "share this moment" affordance or persist the summary. Default
     * no-op for hosts that don't care (e.g. voice-call host).
     */
    fun onRunSummary(runSummary: RunSummary) {}

    /**
     * The host should hide/show its floating chrome (ball, call buttons) around
     * an agent screenshot capture so Gotcha's own controls never appear in the
     * frame. Default no-op for hosts that have no floating chrome to hide.
     */
    fun onScreenCaptureChrome(hide: Boolean) {}

    /** A read_screen / read_screen_raw just finished; the host may flash "screen read" feedback. */
    fun onScreenReadDone() {}

    /** Ask the user the agent's question; returns "" when unanswered. */
    suspend fun awaitQuestionAnswer(question: PendingQuestion): String

    /**
     * Ask before Gotcha first opens or controls another app in this request
     * (issue #98). Asked at most once per request; the answer covers every later
     * foreground step in it. Defaults to "can't ask here", which denies.
     */
    suspend fun awaitForegroundControl(request: ForegroundControlRequest): Boolean = false

    /**
     * Gotcha started ([active] true) or stopped controlling another app. The
     * host shows that it is in control, then that the user has their app back.
     * [appLabel] names the app when known. Default no-op.
     */
    fun onForegroundControlChanged(active: Boolean, appLabel: String?) {}

    /** Gate a destructive action on explicit user approval. */
    suspend fun awaitConfirmation(toolNames: List<String>, description: String): Boolean
}
