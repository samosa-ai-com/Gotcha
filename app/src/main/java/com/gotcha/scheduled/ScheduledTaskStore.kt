package com.gotcha.scheduled

import android.content.Context
import android.content.SharedPreferences
import com.gotcha.agent.ComposerAttachment
import com.gotcha.data.SafeEncryptedSharedPreferences
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The scheduled tasks (issue #132). The tasks are kept in encrypted prefs, like
 * settings, because their prompts are personal. Their files are kept next to
 * the chats, one JSON file per task under [attachmentsDir]: images there are
 * already downscaled to JPEG and documents already turned into text, so a task
 * still has its files when the picker's permission to read them is long gone.
 *
 * Every change goes through [update], which holds one lock across the read and
 * the write, so the alarm receiver and the Settings page can't overwrite each
 * other's changes.
 */
class ScheduledTaskStore internal constructor(
    private val prefs: SharedPreferences,
    private val attachmentsDir: File
) {

    constructor(context: Context) : this(
        SafeEncryptedSharedPreferences.create(context, PREFS_FILE),
        File(context.filesDir, ATTACHMENTS_DIR)
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val tasksSerializer = ListSerializer(ScheduledTask.serializer())
    private val attachmentsSerializer = ListSerializer(ComposerAttachment.serializer())

    /** Oldest first. */
    fun all(): List<ScheduledTask> = synchronized(LOCK) { read() }

    fun get(id: String): ScheduledTask? = all().firstOrNull { it.id == id }

    /**
     * Replaces the task [id] with what [transform] makes of it (null deletes it)
     * and returns the result. Returns null, changing nothing, when there is no such task.
     */
    fun update(id: String, transform: (ScheduledTask) -> ScheduledTask?): ScheduledTask? = synchronized(LOCK) {
        val tasks = read()
        val current = tasks.firstOrNull { it.id == id } ?: return null
        val changed = transform(current)
        write(if (changed == null) tasks.filterNot { it.id == id } else tasks.map { if (it.id == id) changed else it })
        if (changed == null) attachmentsFile(id).delete()
        changed
    }

    /** Adds [task], or replaces the one with its id. */
    fun put(task: ScheduledTask) = synchronized(LOCK) {
        val tasks = read()
        write(if (tasks.any { it.id == task.id }) tasks.map { if (it.id == task.id) task else it } else tasks + task)
    }

    fun delete(id: String) {
        update(id) { null }
    }

    /** The pinned chat [sessionId] was deleted: a same-chat task starts a new one next time. */
    fun forgetChat(sessionId: String) = synchronized(LOCK) {
        val tasks = read()
        if (tasks.none { it.chatSessionId == sessionId }) return
        write(tasks.map { if (it.chatSessionId == sessionId) it.copy(chatSessionId = null) else it })
    }

    // ---- files ----

    fun saveAttachments(id: String, attachments: List<ComposerAttachment>) {
        val file = attachmentsFile(id)
        if (attachments.isEmpty()) {
            file.delete()
            return
        }
        attachmentsDir.mkdirs()
        file.writeText(json.encodeToString(attachmentsSerializer, attachments))
    }

    /**
     * The files stored with task [id]: empty when it has none, null when it
     * should have some but they can't be read any more.
     */
    fun loadAttachments(task: ScheduledTask): List<ComposerAttachment>? {
        if (task.attachmentNames.isEmpty()) return emptyList()
        val file = attachmentsFile(task.id)
        if (!file.isFile) return null
        return runCatching { json.decodeFromString(attachmentsSerializer, file.readText()) }.getOrNull()
    }

    private fun attachmentsFile(id: String) = File(
        attachmentsDir,
        "${id.filter { it.isLetterOrDigit() || it == '-' }}.json"
    )

    private fun read(): List<ScheduledTask> =
        prefs.getString(KEY_TASKS, null)
            ?.let { runCatching { json.decodeFromString(tasksSerializer, it) }.getOrNull() }
            .orEmpty()

    private fun write(tasks: List<ScheduledTask>) {
        // commit, not apply: the alarm receiver writes here just before its process may go.
        prefs.edit().putString(KEY_TASKS, json.encodeToString(tasksSerializer, tasks)).commit()
    }

    private companion object {
        const val PREFS_FILE = "gotcha_scheduled_tasks"
        const val KEY_TASKS = "tasks"
        const val ATTACHMENTS_DIR = "scheduled_tasks"
        val LOCK = Any()
    }
}
