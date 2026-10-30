package com.gotcha.scheduled

import android.content.Context
import android.content.SharedPreferences
import com.gotcha.agent.ComposerAttachment
import com.gotcha.data.SafeEncryptedSharedPreferences
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

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

    /**
     * Seeds the Morning briefing preset once (Second Brain Phase 3): present
     * in the catalog but paused, so the user opts in with the normal toggle.
     * Never duplicates: an existing briefing (seeded, renamed, re-added, or
     * user-made) is adopted by id tracking and only the marker is healed.
     * A deleted preset is never re-added unasked. The flag is written after
     * the task, so a failed seed retries next time. Returns the preset when
     * newly created, null otherwise.
     */
    fun seedBriefingPreset(memoryRoot: String, now: Long, zone: ZoneId): ScheduledTask? = synchronized(LOCK) {
        val seededId = prefs.getString(KEY_BRIEFING_TASK_ID, null)
        read().firstOrNull { it.id == seededId || it.name == MorningBriefing.TEMPLATE_NAME }?.let { existing ->
            markBriefingSeeded(existing.id)
            return null
        }
        if (prefs.getBoolean(KEY_BRIEFING_SEEDED, false)) return null
        val preset = MorningBriefing.template(
            memoryRoot,
            LocalDateTime.ofInstant(Instant.ofEpochMilli(now), zone)
        ).copy(id = UUID.randomUUID().toString(), enabled = false, createdAt = now)
        put(preset)
        markBriefingSeeded(preset.id)
        preset
    }

    /** True when a briefing preset exists right now (even renamed): hides the re-add button. */
    fun hasBriefingPreset(): Boolean = synchronized(LOCK) {
        val seededId = prefs.getString(KEY_BRIEFING_TASK_ID, null)
        read().any { it.id == seededId || it.name == MorningBriefing.TEMPLATE_NAME }
    }

    private fun markBriefingSeeded(taskId: String?) {
        prefs.edit()
            .putBoolean(KEY_BRIEFING_SEEDED, true)
            .apply { if (taskId != null) putString(KEY_BRIEFING_TASK_ID, taskId) }
            .apply()
    }

    fun delete(id: String) {
        update(id) { null }
    }

    /**
     * Re-resolves every non-hourly task's next run in [zone] after the wall
     * clock moved under it (issue #150). Hourly tasks, retries and busy waits
     * are real durations and keep their moments. Returns true when anything
     * changed. One lock covers the read and the write, like [update].
     */
    fun rebaseForZoneChange(now: Long, zone: ZoneId): Boolean = synchronized(LOCK) {
        val tasks = read()
        val rebased = tasks.map { ScheduledTaskPlanner.rebasedForZone(it, now, zone) }
        if (rebased == tasks) return false
        write(rebased)
        true
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
        const val KEY_BRIEFING_SEEDED = "briefing_preset_seeded"
        const val KEY_BRIEFING_TASK_ID = "briefing_preset_task_id"
        const val ATTACHMENTS_DIR = "scheduled_tasks"
        val LOCK = Any()
    }
}
