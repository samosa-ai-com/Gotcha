package com.gotcha.scheduled

import java.time.ZoneId

/**
 * What the Settings editor sets on a task. Everything else on a task (its next
 * run, retry and busy waits, history) belongs to the runs, which can change it
 * while the editor is open, so a save lays these fields over the task as it is
 * stored then, not as it was when the editor opened.
 *
 * [openedWithChatId] is the task's chat when the editor opened. While the user
 * leaves the chat picker on it, the stored chat wins, so a chat a same-chat run
 * started meanwhile is kept.
 */
data class ScheduledTaskForm(
    val name: String,
    val prompt: String,
    val startLocal: String,
    val repeat: Repeat,
    val agentMode: String,
    val personaId: String?,
    val sameChat: Boolean,
    val chatSessionId: String?,
    val openedWithChatId: String?,
    val maxRetries: Int,
    val attachmentNames: List<String>
) {
    fun applyTo(task: ScheduledTask): ScheduledTask = task.copy(
        name = name,
        prompt = prompt,
        startLocal = startLocal,
        repeat = repeat,
        agentMode = agentMode,
        personaId = personaId,
        sameChat = sameChat,
        chatSessionId = when {
            !sameChat -> null
            chatSessionId == openedWithChatId -> task.chatSessionId
            else -> chatSessionId
        },
        maxRetries = maxRetries,
        attachmentNames = attachmentNames
    )

    /**
     * Saves the form over the stored task [id] and returns the result, or null
     * when the task was deleted meanwhile (it is not brought back). The schedule
     * is re-worked only when the form changed it, so a pending retry or busy
     * wait survives an edit to, say, the name.
     *
     * [onCurrent] sees the task as stored before the save, inside the same
     * update: callers syncing a busy wait's notification read it there, so a
     * wait starting mid-save cannot slip through.
     */
    fun saveOver(
        store: ScheduledTaskStore,
        id: String,
        now: Long,
        zone: ZoneId,
        onCurrent: ((ScheduledTask) -> Unit)? = null
    ): ScheduledTask? =
        store.update(id) { current ->
            onCurrent?.invoke(current)
            ScheduledTaskPlanner.edited(current, applyTo(current), now, zone)
        }
}
