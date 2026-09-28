package com.gotcha.agent

import android.content.SharedPreferences
import android.os.Process
import com.gotcha.llm.ChatMessage
import kotlinx.serialization.json.JsonPrimitive

/**
 * Notes on disk which chat has a run in progress, so a run that died with the
 * process is noticed on the next start instead of leaving a half-finished chat
 * with no explanation (issue #105). Written synchronously, because the process
 * can die right after.
 *
 * Tagged with the process that wrote it: a run whose ViewModel was cleared while
 * the process lives on is ended, and its marker cleared, by the run's own
 * cleanup, so only a marker left by another process means one was killed.
 */
class RunInProgressMarker(
    private val prefs: SharedPreferences,
    private val processToken: () -> String = { "${Process.myPid()}:${Process.getStartElapsedRealtime()}" }
) {

    fun mark(sessionId: String) {
        prefs.edit().putString(KEY_SESSION, sessionId).putString(KEY_PROCESS, processToken()).commit()
    }

    fun clear() {
        prefs.edit().remove(KEY_SESSION).remove(KEY_PROCESS).commit()
    }

    /** The chat whose run an earlier process was killed in, or null. */
    fun interruptedSession(): String? {
        val sessionId = prefs.getString(KEY_SESSION, null) ?: return null
        return sessionId.takeIf { prefs.getString(KEY_PROCESS, null) != processToken() }
    }

    companion object {
        private const val KEY_SESSION = "run_in_progress_session"
        private const val KEY_PROCESS = "run_in_progress_process"
    }
}

/**
 * [history] with a result for every tool call of its last assistant turn. A run
 * killed mid-round can leave calls without results, which the provider rejects
 * on every later request of the chat.
 */
internal fun closeOrphanedToolCalls(history: List<ChatMessage>): List<ChatMessage> {
    val lastUser = history.indexOfLast { it.role == "user" }
    val assistantIdx = history.indexOfLast { it.role == "assistant" && !it.toolCalls.isNullOrEmpty() }
    if (assistantIdx < 0 || assistantIdx < lastUser) return history
    val answered = history.drop(assistantIdx + 1).filter { it.role == "tool" }.mapNotNull { it.toolCallId }.toSet()
    val missing = history[assistantIdx].toolCalls.orEmpty().filter { it.id !in answered }
    if (missing.isEmpty()) return history
    return history + missing.map {
        ChatMessage(
            role = "tool",
            content = JsonPrimitive("Cancelled: Gotcha was closed by Android before this tool completed."),
            toolCallId = it.id
        )
    }
}
