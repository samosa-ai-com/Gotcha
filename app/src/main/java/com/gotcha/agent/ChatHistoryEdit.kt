package com.gotcha.agent

import com.gotcha.llm.ChatMessage

/**
 * Pure helpers that map between the on-screen transcript ([UiMessage] bubbles)
 * and the LLM-shaped [AgentEngine.history] for the Edit / Revert message
 * features.
 *
 * The two lists do not map 1:1: a single user turn expands in [history] into
 * many messages — the prompt, assistant tool-call rounds, `tool` results, and
 * *injected* vision `user` messages for screenshots and read image files. So
 * truncation is always computed on **real user prompts** only, using the same
 * heuristics `rebuildUiMessagesFrom` applies when reconstructing a transcript.
 */

/**
 * True when [msg] is a genuine user prompt rather than an injected screen or
 * image observation. The markers mirror the screen-observation heuristics the
 * transcript rebuild applies to `user` messages, plus the image-file injection
 * prefix used by `AgentEngine.handleImageResult`.
 */
internal fun isRealUserPrompt(msg: ChatMessage): Boolean {
    if (msg.role != "user") return false
    val t = msg.textContent
    return !(
        t.startsWith("[Screen State]") ||
            t.startsWith("Screen text:") ||
            t.contains("── UI Elements ──") ||
            t.startsWith("The assistant read an image file:")
        )
}

/**
 * Indices into [history] of the real user prompts, in order. These correlate
 * 1:1 with the `USER` bubbles in the transcript, since the only `USER` bubbles
 * ever emitted come from `ChatViewModel.sendMessage`/`editMessage`.
 */
internal fun userTurnStarts(history: List<ChatMessage>): List<Int> =
    history.indices.filter { isRealUserPrompt(history[it]) }

/**
 * Truncates [history] to the [turnIndex]-th real user turn.
 *
 * - [dropTurn] = true (edit): the target prompt is removed so its turn is
 *   replaced by the edited text and a fresh reply.
 * - [dropTurn] = false (revert): the target prompt becomes the last message.
 *
 * Turn tails (tool rounds, sub-agent results, injected observations) are
 * removed together with their prompt because truncation is by the **next** real
 * prompt's start. Returns the original list when [turnIndex] is out of range.
 */
internal fun truncateHistoryAtTurn(
    history: List<ChatMessage>,
    turnIndex: Int,
    dropTurn: Boolean
): List<ChatMessage> {
    val start = userTurnStarts(history).getOrNull(turnIndex) ?: return history
    // Materialise an independent copy: the callers swap this result into the live
    // history (clear() + addAll), and a subList view would go stale — and throw a
    // ConcurrentModificationException — the moment the backing list is cleared.
    val kept = history.subList(0, start).toList()
    return if (dropTurn) kept else kept + history[start]
}
