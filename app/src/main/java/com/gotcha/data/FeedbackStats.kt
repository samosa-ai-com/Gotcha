package com.gotcha.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Point-in-time usage stats gathered for the feedback pre-fill.
 *
 * Computed entirely in-memory at the moment the user taps "Send feedback",
 * dropped as soon as the form URL is built. Nothing is persisted and nothing
 * leaves the device until the user explicitly submits the form.
 */
data class FeedbackStats(
    val chatSessions: Int,
    val voiceCallSessions: Int,
    val totalRuns: Int,
    val successfulRuns: Int,
    val delegatedRuns: Int,
    val operatorRuns: Int,
    val totalToolCalls: Int,
    val successfulToolCalls: Int,
    val toolCallsByName: Map<String, Int>,
    val models: Set<String>
) {
    /** Renders the compact multi-line block that pre-fills the Usage stats field. */
    fun toPrefillText(): String = buildString {
        appendLine("Chats: $chatSessions · Voice calls: $voiceCallSessions")
        appendLine(
            "Runs: $totalRuns · succeeded: $successfulRuns · " +
                "delegated: $delegatedRuns · operator: $operatorRuns"
        )
        val failed = totalToolCalls - successfulToolCalls
        appendLine("Tool calls: $totalToolCalls (OK $successfulToolCalls / FAIL $failed)")
        val top = toolCallsByName.entries
            .sortedByDescending { it.value }
            .take(10)
            .joinToString(" · ") { "${it.key} ${it.value}" }
        if (top.isNotBlank()) appendLine(top)
        if (models.isNotEmpty()) appendLine("Models: ${models.sorted().joinToString(", ")}")
    }
}

/** Aggregates across the chat and voice-call repositories. Never writes. */
suspend fun computeFeedbackStats(
    chats: ChatHistoryRepository,
    calls: ChatHistoryRepository
): FeedbackStats = withContext(Dispatchers.IO) {
    computeFeedbackStats(chats.listSessions(), calls.listSessions())
}

/** Aggregates across the app's default chat and voice-call directories. */
suspend fun computeFeedbackStats(context: Context): FeedbackStats =
    computeFeedbackStats(
        ChatHistoryRepository(context),
        ChatHistoryRepository(context, "calls")
    )

internal fun computeFeedbackStats(
    chatSessions: List<ChatSession>,
    callSessions: List<ChatSession>
): FeedbackStats {
    val runs = (chatSessions + callSessions).flatMap { it.runSummaries }
    val toolCalls = runs.flatMap { it.toolCalls }
    return FeedbackStats(
        chatSessions = chatSessions.size,
        voiceCallSessions = callSessions.size,
        totalRuns = runs.size,
        successfulRuns = runs.count { it.succeeded },
        delegatedRuns = runs.count { it.delegated },
        operatorRuns = runs.count { it.agentMode == "OPERATOR" },
        totalToolCalls = toolCalls.size,
        successfulToolCalls = toolCalls.count { it.success },
        toolCallsByName = toolCalls.groupingBy { it.name }.eachCount(),
        models = runs.map { it.model }.filter { it.isNotBlank() }.toSet()
    )
}
