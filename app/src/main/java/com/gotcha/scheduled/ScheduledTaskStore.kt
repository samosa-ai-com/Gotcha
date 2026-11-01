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
/**
 * The two prefs the Morning briefing preset used before examples existed.
 * Kept at file scope so the one-time adoption can be tested.
 */
internal const val KEY_BRIEFING_SEEDED = "briefing_preset_seeded"
internal const val KEY_BRIEFING_TASK_ID = "briefing_preset_task_id"

@Suppress("TooManyFunctions") // A storage facade: one small verb per operation, no shared state of its own.
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
     * Seeds every example in [ScheduledPresets] once, paused, so the page opens
     * on four things that already show what scheduling is good for.
     *
     * Never duplicates: a task already tracked for the preset is adopted, and
     * the briefing keeps the id it was seeded under before examples existed.
     * A deleted example is never re-added unasked; [restorePresets] is the
     * explicit way back. The marker is written after the task, so a failed
     * write retries on the next open. Returns the tasks newly created.
     */
    fun seedPresets(memoryRoot: String, now: Long, zone: ZoneId): List<ScheduledTask> = synchronized(LOCK) {
        val localNow = LocalDateTime.ofInstant(Instant.ofEpochMilli(now), zone)
        val existing = read()
        var tasks = existing
        val created = mutableListOf<ScheduledTask>()
        for (preset in ScheduledPresets.all) {
            if (tasks.any { it.presetId == preset.id }) continue
            val seeded = seedOne(preset, tasks, memoryRoot, localNow, now)
            tasks = seeded.tasks
            seeded.created?.let { created += it }
        }
        if (created.isNotEmpty() || tasks != existing) write(tasks)
        created
    }

    /**
     * One preset's place in [tasks]: already tracked, already seen, a task the
     * user made under the same name, or a new paused copy. Never returns a
     * second copy of anything.
     */
    private fun seedOne(
        preset: ScheduledPreset,
        tasks: List<ScheduledTask>,
        memoryRoot: String,
        localNow: LocalDateTime,
        now: Long
    ): PresetSeed {
        // The briefing seeded before examples existed is tracked under its
        // own key, so it is adopted rather than duplicated once.
        val trackedId = prefs.getString(taskKey(preset.id), null) ?: legacyTaskId(preset.id)
        val tracked = trackedId?.let { id -> tasks.firstOrNull { it.id == id } }
        if (tracked != null) {
            markSeeded(preset.id, tracked.id)
            return PresetSeed(tasks.map { if (it.id == tracked.id) it.copy(presetId = preset.id) else it })
        }
        if (isSeeded(preset.id)) return PresetSeed(tasks)
        // A task the user already made with the example's name is theirs: adopt
        // it as the example instead of adding a duplicate beside it. Only on the
        // first seed, so promoting or deleting later stays put.
        val sameName = tasks.firstOrNull { it.name == preset.name }
        if (sameName != null) {
            markSeeded(preset.id, sameName.id)
            return PresetSeed(tasks.map { if (it.id == sameName.id) it.copy(presetId = preset.id) else it })
        }
        val task = ScheduledPresets.task(preset, memoryRoot, localNow)
            .copy(id = UUID.randomUUID().toString(), createdAt = now, presetId = preset.id)
        markSeeded(preset.id, task.id)
        return PresetSeed(tasks + task, task)
    }

    /** The task list after seeding one preset, plus the task it created, if any. */
    private data class PresetSeed(val tasks: List<ScheduledTask>, val created: ScheduledTask? = null)

    /**
     * Examples that are not in [tasks] at all: what the restore button would
     * add back. A task carrying the example's name counts as present, since
     * restore would adopt it rather than duplicate it.
     */
    fun missingPresets(): List<ScheduledPreset> = synchronized(LOCK) {
        val tasks = read()
        ScheduledPresets.all.filter { preset ->
            tasks.none { it.presetId == preset.id || it.name == preset.name }
        }
    }

    /**
     * Puts back every example that is missing, paused. A task already carrying
     * the example's name counts as present: restoring must not create a
     * duplicate of something the user made themselves.
     */
    fun restorePresets(memoryRoot: String, now: Long, zone: ZoneId): Int = synchronized(LOCK) {
        val localNow = LocalDateTime.ofInstant(Instant.ofEpochMilli(now), zone)
        var tasks = read()
        var added = 0
        for (preset in ScheduledPresets.all) {
            if (tasks.any { it.presetId == preset.id }) continue
            val sameName = tasks.firstOrNull { it.name == preset.name }
            if (sameName != null) {
                markSeeded(preset.id, sameName.id)
                continue
            }
            val task = ScheduledPresets.task(preset, memoryRoot, localNow)
                .copy(id = UUID.randomUUID().toString(), createdAt = now, presetId = preset.id)
            tasks = tasks + task
            added++
            markSeeded(preset.id, task.id)
        }
        if (added > 0) write(tasks)
        added
    }

    /**
     * Stops treating [presetId] as an example: called when the user turns it on
     * or saves an edit. The example is theirs now, so neither seeding nor the
     * restore button may drag it back. The seeded marker stays, which is what
     * keeps a promoted example from being re-added as a duplicate.
     */
    fun forgetPreset(presetId: String) = synchronized(LOCK) {
        prefs.edit()
            .remove(taskKey(presetId))
            .apply { if (presetId == ScheduledPresets.MORNING_BRIEFING) remove(KEY_BRIEFING_TASK_ID) }
            .apply()
    }

    private fun isSeeded(presetId: String): Boolean =
        prefs.getBoolean(seededKey(presetId), false) || legacySeeded(presetId)

    /** The briefing seeded before examples existed, under its old keys. */
    private fun legacySeeded(presetId: String): Boolean =
        presetId == ScheduledPresets.MORNING_BRIEFING && prefs.getBoolean(KEY_BRIEFING_SEEDED, false)

    private fun legacyTaskId(presetId: String): String? =
        if (presetId == ScheduledPresets.MORNING_BRIEFING) {
            prefs.getString(KEY_BRIEFING_TASK_ID, null)
        } else {
            null
        }

    private fun markSeeded(presetId: String, taskId: String) {
        prefs.edit()
            .putBoolean(seededKey(presetId), true)
            .putString(taskKey(presetId), taskId)
            .apply()
    }

    private fun seededKey(presetId: String) = "preset_seeded_$presetId"

    private fun taskKey(presetId: String) = "preset_task_$presetId"

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
        const val ATTACHMENTS_DIR = "scheduled_tasks"
        val LOCK = Any()
    }
}
