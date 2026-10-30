package com.gotcha.tools

import android.content.Context
import com.gotcha.agent.AttachmentFiles
import com.gotcha.agent.ChatTitle
import com.gotcha.agent.ComposerAttachment
import com.gotcha.data.ChatHistoryRepository
import com.gotcha.data.GotchaStorage
import com.gotcha.data.MemoryStore
import com.gotcha.i18n.stringLookup
import com.gotcha.scheduled.Repeat
import com.gotcha.scheduled.RepeatKind
import com.gotcha.scheduled.ScheduledTask
import com.gotcha.scheduled.ScheduledTaskPlanner
import com.gotcha.scheduled.ScheduledTaskScheduler
import com.gotcha.scheduled.ScheduledTaskStore
import com.gotcha.scheduled.isWallTimeFuture
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import java.io.File
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.TextStyle
import java.util.Base64
import java.util.Locale
import java.util.UUID

/**
 * A create or an edit of a scheduled task the agent asked for (issue #132),
 * carried in a [ScheduledTaskTool.CONFIRM_PREFIX] result until the user answers.
 * Null fields of an edit are left as they are. [files] are canonical paths,
 * checked when the call was made and read only once the user approves.
 */
@Serializable
data class ScheduleRequest(
    val taskId: String? = null,
    val name: String? = null,
    val prompt: String? = null,
    val startLocal: String? = null,
    val repeat: Repeat? = null,
    val sameChat: Boolean? = null,
    val mode: String? = null,
    val files: List<String>? = null,
    val enabled: Boolean? = null,
    /** Absolute path of the memory note linked both ways, or null to leave the link as it is. */
    val memoryNote: String? = null,
    /** True when `memory_note` was passed blank: unlink the task from its note. */
    val clearMemoryNote: Boolean = false
) {
    val isCreate: Boolean get() = taskId == null
}

/**
 * The agent's side of scheduled tasks (issue #132): schedule_agent_task,
 * edit_scheduled_task, list_scheduled_tasks and delete_scheduled_task.
 *
 * A task runs the agent later with nobody watching, so nothing here saves a
 * task by itself. A create or an edit is checked and returned as a
 * [CONFIRM_PREFIX] marker; `AgentEngine` shows the user exactly what would be
 * scheduled and only then calls [apply], with the chat's own mode as the
 * ceiling for the task's. A delete goes through the engine's delete
 * confirmation. None of the four is offered to Monitor (bar the list), to a
 * sub-agent, or to a run a schedule started.
 */
class ScheduledTaskTool(
    private val context: Context,
    private val store: ScheduledTaskStore = ScheduledTaskStore(context),
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val clock: () -> Long = { System.currentTimeMillis() },
    /** The title of chat [String], or null when it is no longer saved. */
    private val chatTitle: suspend (String) -> String? = { id ->
        ChatHistoryRepository(context).loadSession(id)?.title?.let { ChatTitle.display(it, context.stringLookup()) }
    }
) {

    private val fileResolver by lazy { FileResolver(context) }

    fun prepareCreate(args: JsonObject): ToolResult {
        val name = args.string("name")?.trim()?.take(MAX_NAME) ?: return missing("name")
        val prompt = args.string("prompt")?.trim()?.take(MAX_PROMPT) ?: return missing("prompt")
        if (name.isEmpty()) return missing("name")
        if (prompt.isEmpty()) return missing("prompt")
        val startText = args.string("start") ?: return missing("start")
        val start = parseStart(startText) ?: return ToolResult.error(
            "Invalid 'start' \"$startText\": use the phone's local time as YYYY-MM-DDTHH:MM, e.g. 2026-10-19T08:00."
        )
        val repeat = parseRepeat(args).getOrElse { return ToolResult.error(it.message.orEmpty()) }
        if (repeat.kind == RepeatKind.NONE && !isWallTimeFuture(start, zone(), clock())) {
            return ToolResult.error("That time has already passed. Pick a time in the future, or a repeat rule.")
        }
        if (store.all().count { it.createdByAgent } >= MAX_AGENT_TASKS) {
            return ToolResult.error(
                "You already set up $MAX_AGENT_TASKS scheduled tasks, the most allowed. Ask the user to delete " +
                    "one first (list_scheduled_tasks shows them), or to add this one in Settings."
            )
        }
        val mode = parseMode(args).getOrElse { return ToolResult.error(it.message.orEmpty()) }
        val files = checkFiles(args).getOrElse { return ToolResult.error(it.message.orEmpty()) }
        val memoryNote = checkMemoryNoteArg(
            args,
            fileResolver
        ).getOrElse { return ToolResult.error(it.message.orEmpty()) }
        val clearMemoryNote = args["memory_note"]?.let { element ->
            element is JsonPrimitive && element !is JsonNull && element.content.trim().isEmpty()
        } ?: false
        return confirm(
            ScheduleRequest(
                name = name,
                prompt = prompt,
                startLocal = start.toString(),
                repeat = repeat,
                sameChat = args.boolean("same_chat") ?: false,
                mode = mode?.name,
                files = files,
                memoryNote = memoryNote,
                clearMemoryNote = clearMemoryNote
            )
        )
    }

    fun prepareEdit(args: JsonObject): ToolResult {
        val id = args.string("task_id") ?: return missing("task_id")
        val existing = store.get(id) ?: return unknownTask(id)
        val start = args.string("start")?.let { text ->
            parseStart(text) ?: return ToolResult.error(
                "Invalid 'start' \"$text\": use the phone's local time as YYYY-MM-DDTHH:MM."
            )
        }
        val repeat = if (args.containsKey("repeat") || args.containsKey("interval") || args.containsKey("weekdays")) {
            parseRepeat(args, existing.repeat).getOrElse { return ToolResult.error(it.message.orEmpty()) }
        } else {
            null
        }
        val mode = parseMode(args).getOrElse { return ToolResult.error(it.message.orEmpty()) }
        val files = checkFiles(args).getOrElse { return ToolResult.error(it.message.orEmpty()) }
        val memoryNote = checkMemoryNoteArg(
            args,
            fileResolver
        ).getOrElse { return ToolResult.error(it.message.orEmpty()) }
        val request = ScheduleRequest(
            taskId = existing.id,
            name = args.string("name")?.trim()?.take(MAX_NAME)?.ifEmpty { null },
            prompt = args.string("prompt")?.trim()?.take(MAX_PROMPT)?.ifEmpty { null },
            startLocal = start?.toString(),
            repeat = repeat,
            sameChat = args.boolean("same_chat"),
            mode = mode?.name,
            files = files,
            enabled = args.boolean("enabled"),
            memoryNote = memoryNote,
            clearMemoryNote = args["memory_note"]?.let { element ->
                element is JsonPrimitive && element !is JsonNull && element.content.trim().isEmpty()
            } ?: false
        )
        if (request == ScheduleRequest(taskId = existing.id)) {
            return ToolResult.error("Nothing to change: pass at least one field to edit.")
        }
        return confirm(request)
    }

    fun list(): ToolResult {
        val tasks = store.all()
        if (tasks.isEmpty()) return ToolResult.ok("No scheduled tasks.")
        return ToolResult.ok(
            tasks.joinToString("\n") { task ->
                buildString {
                    append("- ${task.id}: \"${task.name}\"")
                    append(if (task.enabled) "" else " (paused)")
                    append(" — ${describeSchedule(task.start, task.repeat)}")
                    append("; next: ${task.dueAt?.let(::formatInstant) ?: "none"}")
                    append("; ${task.mode.name.lowercase()} mode")
                    append(if (task.sameChat) "; same chat" else "; new chat each time")
                    if (task.attachmentNames.isNotEmpty()) append("; files: ${task.attachmentNames.joinToString()}")
                    task.memoryNotePath?.let { append("; memory note: ${shortenMemoryPath(it)}") }
                    append(if (task.createdByAgent) "; set up by you" else "; set up by the user")
                    task.lastRun?.let { append("; last run: ${it.status.name.lowercase()} at ${formatInstant(it.at)}") }
                    append("\n  prompt: ${task.prompt.take(LIST_PROMPT_CHARS)}")
                }
            }
        )
    }

    fun prepareDelete(args: JsonObject): ToolResult {
        val id = args.string("task_id") ?: return missing("task_id")
        val task = store.get(id) ?: return unknownTask(id)
        return ToolResult.ok("$DELETE_CONFIRM_PREFIX${task.id}:${task.name}")
    }

    /** Deletes task [id]; only called once the user has confirmed. */
    fun doDelete(id: String): ToolResult {
        val task = store.get(id) ?: return unknownTask(id)
        store.delete(id)
        ScheduledTaskScheduler.reschedule(context)
        return ToolResult.ok("Deleted the scheduled task \"${task.name}\".")
    }

    /**
     * What the user is asked to approve for [request]: the whole task as it
     * would be saved, in plain words. [sessionId] is the chat it was asked
     * from, as for [apply]; [chatMode] caps the task's mode.
     */
    suspend fun describe(request: ScheduleRequest, sessionId: String?, chatMode: AgentMode): String {
        val existing = request.taskId?.let { store.get(it) }
        val start = (request.startLocal ?: existing?.startLocal)?.let(LocalDateTime::parse)
        val repeat = request.repeat ?: existing?.repeat ?: Repeat()
        val mode = resolveMode(request, existing, chatMode)
        val sameChat = request.sameChat ?: existing?.sameChat ?: false
        // The chat a same-chat task will run in, as apply() works it out, when it is not this one.
        val otherChat = existing?.chatSessionId?.takeIf { sameChat && request.sameChat != true && it != sessionId }
        val files = request.files?.map { File(it).name } ?: existing?.attachmentNames.orEmpty()
        return buildString {
            append(if (existing == null) "Schedule a new task" else "Change the scheduled task \"${existing.name}\"")
            append(". It will run on its own, without asking you first.\n")
            append("Name: ${request.name ?: existing?.name}\n")
            if (start != null) append("When: ${describeSchedule(start, repeat)}\n")
            append("Mode: ${mode.name.lowercase().replaceFirstChar { it.uppercase() }}\n")
            append(
                when {
                    !sameChat -> "Runs in: a new chat each time\n"
                    otherChat == null -> "Runs in: this chat, each time\n"
                    else -> chatTitle(otherChat)?.let { "Runs in: the chat \"$it\" (not this one), each time\n" }
                        ?: "Runs in: the chat it was set up for, which is no longer saved (not this one)\n"
                }
            )
            if (files.isNotEmpty()) append("Files: ${files.joinToString()}\n")
            appendMemoryNoteLine(request, existing)
            request.enabled?.let { append(if (it) "Turns the task on\n" else "Pauses the task\n") }
            append("Prompt: ${request.prompt ?: existing?.prompt}")
        }
    }

    /**
     * Saves [request] once the user approved it. [sessionId] is the chat it was
     * asked from (a same-chat task runs there), [chatMode] the ceiling for the
     * task's mode.
     */
    fun apply(request: ScheduleRequest, sessionId: String?, chatMode: AgentMode): ToolResult {
        val now = clock()
        val existing = request.taskId?.let { store.get(it) ?: return unknownTask(it) }
        val attachments = request.files?.let {
            loadFiles(it).getOrElse { e -> return ToolResult.error(e.message.orEmpty()) }
        }
        val mode = resolveMode(request, existing, chatMode).name
        fun edit(base: ScheduledTask): ScheduledTask {
            val sameChat = request.sameChat ?: base.sameChat
            return base.copy(
                name = request.name ?: base.name,
                prompt = request.prompt ?: base.prompt,
                startLocal = request.startLocal ?: base.startLocal,
                repeat = request.repeat ?: base.repeat,
                agentMode = mode,
                sameChat = sameChat,
                // Asked from this chat: "the same chat" is this one.
                chatSessionId = if (sameChat && (request.sameChat == true || base.chatSessionId == null)) {
                    sessionId
                } else {
                    base.chatSessionId
                },
                enabled = request.enabled ?: base.enabled,
                attachmentNames = attachments?.map { it.name } ?: base.attachmentNames,
                memoryNotePath = if (request.clearMemoryNote) {
                    null
                } else {
                    request.memoryNote ?: base.memoryNotePath
                }
            )
        }
        val saved = if (existing == null) {
            val created = ScheduledTask(
                id = UUID.randomUUID().toString(),
                name = request.name.orEmpty(),
                prompt = request.prompt.orEmpty(),
                startLocal = request.startLocal ?: return missing("start"),
                createdByAgent = true,
                createdAt = now
            )
            ScheduledTaskPlanner.edited(null, edit(created), now, zone()).also(store::put)
        } else {
            // Over the task as stored now, so a run that ended since the
            // approval was asked keeps its history and next time.
            store.update(existing.id) { current -> ScheduledTaskPlanner.edited(current, edit(current), now, zone()) }
                ?: return unknownTask(existing.id)
        }
        if (attachments != null) store.saveAttachments(saved.id, attachments)
        ScheduledTaskScheduler.reschedule(context)
        val next = saved.dueAt?.let(::formatInstant) ?: "none"
        return ToolResult.ok(
            (if (existing == null) "Scheduled" else "Updated") +
                " \"${saved.name}\" (task_id ${saved.id}), ${saved.mode.name.lowercase()} mode. Next run: $next. " +
                "The user can pause or delete it in Settings ▸ Scheduled tasks."
        )
    }

    // ---- parsing ----

    private fun confirm(request: ScheduleRequest): ToolResult =
        ToolResult.ok(CONFIRM_PREFIX + encodePayload(request))

    private fun parseMode(args: JsonObject): Result<AgentMode?> {
        val text = args.string("mode") ?: return Result.success(null)
        return AgentMode.entries.firstOrNull { it.name.equals(text.trim(), ignoreCase = true) }
            ?.let { Result.success(it) }
            ?: Result.failure(IllegalArgumentException("Invalid 'mode' \"$text\": use monitor or operator."))
    }

    private fun parseRepeat(args: JsonObject, current: Repeat = Repeat()): Result<Repeat> {
        val kindText = args.string("repeat")
        val kind = if (kindText == null) {
            current.kind
        } else {
            RepeatKind.entries.firstOrNull { it.name.equals(kindText.trim(), ignoreCase = true) }
                ?: return Result.failure(
                    IllegalArgumentException(
                        "Invalid 'repeat' \"$kindText\": use none, hourly, daily, weekly or monthly."
                    )
                )
        }
        val interval = (args["interval"] as? JsonPrimitive)?.let { it.intOrNull ?: it.content.trim().toIntOrNull() }
            ?: if (kind == current.kind) current.interval else 1
        if (interval !in 1..ScheduledTask.MAX_INTERVAL) {
            return Result.failure(
                IllegalArgumentException("Invalid 'interval' $interval: use 1 to ${ScheduledTask.MAX_INTERVAL}.")
            )
        }
        val weekdays = (args["weekdays"] as? JsonArray)?.map { element ->
            val text = (element as? JsonPrimitive)?.content?.trim().orEmpty()
            parseWeekday(text) ?: return Result.failure(
                IllegalArgumentException("Invalid weekday \"$text\": use mon, tue, wed, thu, fri, sat or sun.")
            )
        }?.distinct()?.sorted() ?: if (kind == current.kind) current.weekdays else emptyList()
        return Result.success(
            Repeat(
                kind = kind,
                interval = if (kind == RepeatKind.HOURLY || kind == RepeatKind.DAILY) interval else 1,
                weekdays = if (kind == RepeatKind.WEEKLY) weekdays else emptyList()
            )
        )
    }

    /** Approval line for the task's memory link: set, kept, or removed. */
    private fun StringBuilder.appendMemoryNoteLine(request: ScheduleRequest, existing: ScheduledTask?) {
        if (request.clearMemoryNote && existing?.memoryNotePath != null) {
            append("Memory note: (removed)\n")
            return
        }
        (request.memoryNote ?: existing?.memoryNotePath)?.let { append("Memory note: $it\n") }
    }

    /** The `files` argument, each checked to be a readable file and made canonical. */
    private fun checkFiles(args: JsonObject): Result<List<String>?> {
        val paths = (args["files"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content?.trim() }
            ?.filter { it.isNotEmpty() }
            ?: return Result.success(null)
        if (paths.size > ComposerAttachment.MAX_PER_MESSAGE) {
            return Result.failure(
                IllegalArgumentException("At most ${ComposerAttachment.MAX_PER_MESSAGE} files can go with a task.")
            )
        }
        return Result.success(
            paths.map { path ->
                val file = when (val resolved = fileResolver.resolveForRead(path)) {
                    is FileResolver.ResolveResult.Ok -> resolved.file
                    is FileResolver.ResolveResult.PermissionNeeded ->
                        return Result.failure(IllegalArgumentException(resolved.result.message))
                    is FileResolver.ResolveResult.Error -> return Result.failure(
                        IllegalArgumentException(resolved.message)
                    )
                }
                if (!file.isFile || !file.canRead()) {
                    return Result.failure(IllegalArgumentException("No readable file at '$path'."))
                }
                if (file.length() > MAX_FILE_BYTES) {
                    return Result.failure(
                        IllegalArgumentException("'$path' is too large to go with a task (over 20 MB).")
                    )
                }
                file.canonicalPath
            }
        )
    }

    private fun loadFiles(paths: List<String>): Result<List<ComposerAttachment>> = runCatching {
        val loaded = paths.map { path ->
            val file = File(path)
            val bytes = file.readBytes()
            requireNotNull(AttachmentFiles.fromBytes(bytes, file.name, AttachmentFiles.mimeTypeFor(file.name))) {
                "Could not read '${file.name}' as an image."
            }
        }
        require(AttachmentFiles.documentChars(loaded) <= ComposerAttachment.MAX_TOTAL_DOCUMENT_CHARS) {
            "Those documents hold too much text to go with one task. Use fewer."
        }
        loaded
    }

    private fun resolveMode(request: ScheduleRequest, existing: ScheduledTask?, chatMode: AgentMode): AgentMode {
        val asked = request.mode?.let { runCatching { AgentMode.valueOf(it) }.getOrNull() }
            ?: existing?.mode
            ?: chatMode
        // Never stronger than the chat the request came from.
        return if (asked.ordinal > chatMode.ordinal) chatMode else asked
    }

    private fun unknownTask(id: String) =
        ToolResult.error("No scheduled task with task_id '$id'. Call list_scheduled_tasks for the ids.")

    private fun missing(param: String) = ToolResult.error("Missing or invalid required parameter '$param'.")

    private fun formatInstant(at: Long): String =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(at), zone()).format(DISPLAY_FORMAT)

    private fun JsonObject.string(key: String): String? =
        when (val element = this[key]) {
            is JsonNull -> null
            is JsonPrimitive -> element.takeIf { it.isString || it.content.isNotEmpty() }?.content
            else -> null
        }

    private fun JsonObject.boolean(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.let { it.booleanOrNull ?: it.content.trim().toBooleanStrictOrNull() }

    companion object {
        const val CONFIRM_PREFIX = "CONFIRM_SCHEDULE_TASK:"
        const val DELETE_CONFIRM_PREFIX = "CONFIRM_DELETE_SCHEDULED_TASK:"

        val TOOL_NAMES = setOf(
            "schedule_agent_task",
            "edit_scheduled_task",
            "list_scheduled_tasks",
            "delete_scheduled_task"
        )

        /** The most tasks the agent may have set up at once. The user's own, from Settings, don't count. */
        const val MAX_AGENT_TASKS = 10

        private const val MAX_NAME = 80
        private const val MAX_PROMPT = 4000
        private const val LIST_PROMPT_CHARS = 200
        private const val MAX_FILE_BYTES = 20L * 1024L * 1024L

        private val DISPLAY_FORMAT = DateTimeFormatter.ofPattern("EEE d MMM yyyy, HH:mm", Locale.ENGLISH)
        private val json = Json { ignoreUnknownKeys = true }

        fun encodePayload(request: ScheduleRequest): String =
            Base64.getEncoder().encodeToString(json.encodeToString(ScheduleRequest.serializer(), request).toByteArray())

        fun decodePayload(payload: String): ScheduleRequest? = runCatching {
            json.decodeFromString(ScheduleRequest.serializer(), String(Base64.getDecoder().decode(payload)))
        }.getOrNull()

        /** Local date-time as the model writes it: "2026-10-19T08:00", with a space or seconds also taken. */
        internal fun parseStart(text: String): LocalDateTime? = try {
            LocalDateTime.parse(text.trim().replace(' ', 'T')).withSecond(0).withNano(0)
        } catch (_: DateTimeParseException) {
            null
        }

        internal fun parseWeekday(text: String): Int? {
            text.toIntOrNull()?.let { return it.takeIf { n -> n in 1..7 } }
            val lower = text.lowercase()
            if (lower.length < 2) return null
            return DayOfWeek.entries.firstOrNull { it.name.lowercase().startsWith(lower) }?.value
        }

        /** The schedule in English, for the model and the approval prompt. */
        internal fun describeSchedule(start: LocalDateTime, repeat: Repeat): String {
            val first = start.format(DISPLAY_FORMAT)
            val time = start.toLocalTime().toString()
            return when (repeat.kind) {
                RepeatKind.NONE -> "once, on $first"
                RepeatKind.HOURLY ->
                    if (repeat.interval == 1) "every hour from $first" else "every ${repeat.interval} hours from $first"
                RepeatKind.DAILY ->
                    if (repeat.interval == 1) "every day at $time, from $first" else "every ${repeat.interval} days at $time, from $first"
                RepeatKind.WEEKLY -> {
                    val days = repeat.weekdays.ifEmpty { listOf(start.dayOfWeek.value) }
                        .joinToString { DayOfWeek.of(it).getDisplayName(TextStyle.FULL, Locale.ENGLISH) }
                    "every week on $days at $time, from $first"
                }
                RepeatKind.MONTHLY -> "every month on day ${start.dayOfMonth} at $time, from $first"
            }
        }
    }
}

/** The `memory_note` argument: an existing memory record (people/, areas/, notes/), canonicalised. */
private fun checkMemoryNoteArg(args: JsonObject, fileResolver: FileResolver): Result<String?> {
    val element = args["memory_note"] ?: return Result.success(null)
    if (element is JsonNull) return Result.success(null)
    val raw = (element as? JsonPrimitive)?.content?.trim().orEmpty()
    if (raw.isEmpty()) return Result.success(null)
    val file = when (val resolved = fileResolver.resolveForRead(raw)) {
        is FileResolver.ResolveResult.Ok -> resolved.file
        is FileResolver.ResolveResult.PermissionNeeded ->
            return Result.failure(IllegalArgumentException(resolved.result.message))
        is FileResolver.ResolveResult.Error ->
            return Result.failure(IllegalArgumentException(resolved.message))
    }
    if (!file.isFile || !file.canRead()) {
        return Result.failure(IllegalArgumentException("No readable file at '$raw'."))
    }
    if (MemoryStore.recordDirKind(file) == null) {
        return Result.failure(
            IllegalArgumentException(
                "'$raw' is not a memory note, person card or area hub: " +
                    "link a file under Gotcha/memory people/, areas/ or notes/."
            )
        )
    }
    return Result.success(file.canonicalPath)
}

/** Memory-root-relative display path (`notes/plan.md`), so same-named files stay apart in listings. */
private fun shortenMemoryPath(path: String): String {
    return runCatching {
        val root = GotchaStorage.memoryRoot().canonicalPath
        val canonical = File(path).canonicalPath
        if (canonical.startsWith(root + File.separator)) {
            canonical.removePrefix(root + File.separator)
        } else {
            File(path).name
        }
    }.getOrDefault(File(path).name)
}
