package com.gotcha.data

import kotlinx.serialization.Serializable

/**
 * A structured record of one completed agent run — the raw material the
 * "Share your Gotcha moment" feature feeds to the marketing copy LLM call.
 *
 * Captured by [com.gotcha.agent.AgentEngine.run] at each terminal path and
 * persisted on [com.gotcha.data.ChatSession.runSummaries] so a whole chat's
 * history of accomplishments is available long after the run ended.
 */
@Serializable
data class RunSummary(
    /** Epoch millis when the run started. */
    val startedAt: Long,
    /** Epoch millis when the run ended. Duration = endedAt - startedAt. */
    val endedAt: Long,
    /** The user's request that seeded the run (truncated). */
    val userPrompt: String,
    /** The final assistant reply / finish_task summary the user was told. */
    val finalReply: String,
    /** The LLM model used for the run. */
    val model: String,
    /** "MONITOR" or "OPERATOR". */
    val agentMode: String,
    /** True when the run delegated work to a sub-agent. */
    val delegated: Boolean,
    /** Whether the run reached a successful terminal path (content/finish_task). */
    val succeeded: Boolean,
    /** Every tool execution in the run, with its success/failure and result. */
    val toolCalls: List<ToolSummary> = emptyList()
)

/** One tool execution inside a [RunSummary]. */
@Serializable
data class ToolSummary(
    val name: String,
    val success: Boolean,
    /** Short result message (truncated for compactness). */
    val result: String
)
