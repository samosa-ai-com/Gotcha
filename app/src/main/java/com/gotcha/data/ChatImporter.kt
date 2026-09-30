package com.gotcha.data

import com.gotcha.agent.MessageKind
import com.gotcha.llm.ChatMessage
import com.gotcha.llm.DOCUMENTS_ONLY_PROMPT
import com.gotcha.llm.DOCUMENT_ONLY_PROMPT
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.util.UUID

/** Which kind of file an import was read from. */
enum class ImportFormat(val label: String) {
    /** A [ChatArchive]: "Back up chat" or "Back up all chats". Lossless. */
    BACKUP("Gotcha backup"),

    /** One bare [ChatSession] file, as Gotcha stores it on the device. Lossless. */
    CHAT_FILE("Gotcha chat file"),

    /** The Markdown "Export chat". Text only; see [ChatMarkdown]. */
    MARKDOWN("Markdown chat export")
}

/** How an incoming chat relates to the ones already on the device. */
enum class ImportStatus {
    /** No chat with its id exists. */
    NEW,

    /** A chat with its id exists and holds the same conversation, so importing would change nothing. */
    IDENTICAL,

    /** A chat with its id exists but differs; the [DuplicateStrategy] decides. */
    CONFLICT
}

/** What to do with an incoming chat whose id is already taken by a different chat. */
enum class DuplicateStrategy(val label: String) {
    KEEP_BOTH("Keep both"),
    REPLACE("Replace mine"),
    SKIP("Skip")
}

data class ImportItem(val session: ChatSession, val status: ImportStatus)

/** A chat that was not imported, named as well as the file allows, and why. */
data class ImportProblem(val title: String, val reason: String)

/** A readable import file, before anything is written. */
data class ImportPreview(
    val format: ImportFormat,
    val items: List<ImportItem>,
    /** Chats in the file that failed validation and will not be imported. */
    val rejected: List<ImportProblem>,
    /** Things that were repaired or guessed while reading, for the report. */
    val warnings: List<String>
) {
    val newCount: Int get() = items.count { it.status == ImportStatus.NEW }
    val identicalCount: Int get() = items.count { it.status == ImportStatus.IDENTICAL }
    val conflictCount: Int get() = items.count { it.status == ImportStatus.CONFLICT }
}

sealed class ImportParseResult {
    data class Ready(val preview: ImportPreview) : ImportParseResult()

    /** The file as a whole can't be imported; [message] says why, for the user. */
    data class Failed(val message: String) : ImportParseResult()
}

data class ImportResult(
    val imported: Int,
    val replaced: Int,
    val skipped: Int,
    val failed: List<ImportProblem>,
    /** Ids of every chat written, new or replaced. */
    val writtenIds: List<String>
)

/**
 * Reads chats back into Gotcha (issue #83), from any of the three files Gotcha
 * writes: a [ChatArchive] backup, a single stored [ChatSession] file, or the
 * Markdown export. Two steps, so the user sees what a file holds before it
 * touches their chats: [preview] reads and validates, [commit] writes.
 *
 * Validation is per chat: one damaged chat in a backup is reported and the rest
 * still import. A file is refused as a whole only when it is too large, is not
 * one of the three formats, or comes from a newer backup version.
 *
 * No file is trusted with the system role, whoever made it: a backup is as easy
 * to hand-edit and share as a Markdown export, so every system message imports
 * as a labelled note ([ChatMarkdown.systemNote]), and chats are compared the
 * same way when checking for duplicates.
 */
class ChatImporter(
    private val repository: ChatHistoryRepository,
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() }
) {

    suspend fun preview(bytes: ByteArray): ImportParseResult {
        if (bytes.size > MAX_BYTES) {
            return ImportParseResult.Failed(
                "The file is ${bytes.size / (1024 * 1024)} MB; the most Gotcha imports at once is " +
                    "${MAX_BYTES / (1024 * 1024)} MB."
            )
        }
        val text = bytes.toString(Charsets.UTF_8).removePrefix(BYTE_ORDER_MARK)
        if (text.isBlank()) return ImportParseResult.Failed("The file is empty.")

        val read = when {
            text.trimStart().startsWith("{") -> readJson(text)
            ChatMarkdown.looksLikeExport(text) -> readMarkdown(text)
            else -> return ImportParseResult.Failed(NOT_RECOGNISED)
        }
        if (read is ImportParseResult.Failed) return read
        val parsed = (read as ImportParseResult.Ready).preview

        // Check what each chat is, once repaired: a clash with a stored chat, or
        // with another chat earlier in the same file.
        val warnings = parsed.warnings.toMutableList()
        val seenIds = mutableSetOf<String>()
        val items = parsed.items.map { item ->
            var session = item.session
            if (!seenIds.add(session.id)) {
                warnings += "\"${session.title}\" shares its id with another chat in the file; imported as a separate chat."
                session = session.copy(id = newId())
                seenIds += session.id
            }
            ImportItem(session, statusOf(session, parsed.format))
        }
        if (items.isEmpty() && parsed.rejected.isEmpty()) {
            return ImportParseResult.Failed("The file holds no chats.")
        }
        return ImportParseResult.Ready(parsed.copy(items = items, warnings = warnings))
    }

    /**
     * Writes [preview]'s chats. Status is checked again rather than trusted from
     * the preview, since a chat may have been saved in between. Chats in
     * [protectedIds] (the one a task is running in) are never replaced: the
     * running task would write its own copy straight back over the import.
     */
    suspend fun commit(
        preview: ImportPreview,
        strategy: DuplicateStrategy,
        protectedIds: Set<String> = emptySet()
    ): ImportResult {
        var imported = 0
        var replaced = 0
        var skipped = 0
        val failed = mutableListOf<ImportProblem>()
        val written = mutableListOf<String>()

        suspend fun write(session: ChatSession): Boolean {
            val ok = repository.saveSession(session, touch = false)
            if (ok) written += session.id else failed += ImportProblem(session.title, "It couldn't be saved.")
            return ok
        }

        for (item in preview.items) {
            val session = item.session
            when (statusOf(session, preview.format)) {
                ImportStatus.NEW -> if (write(session)) imported++
                ImportStatus.IDENTICAL -> skipped++
                ImportStatus.CONFLICT -> when (strategy) {
                    DuplicateStrategy.SKIP -> skipped++
                    DuplicateStrategy.KEEP_BOTH ->
                        if (write(session.copy(id = newId(), title = "${session.title} (imported)"))) imported++
                    DuplicateStrategy.REPLACE ->
                        if (session.id in protectedIds) {
                            failed += ImportProblem(session.title, "A task is running in that chat; try again when it's done.")
                        } else if (write(session)) {
                            replaced++
                        }
                }
            }
        }
        return ImportResult(imported, replaced, skipped, failed + preview.rejected, written)
    }

    private suspend fun statusOf(session: ChatSession, format: ImportFormat): ImportStatus {
        val existing = repository.loadSession(session.id) ?: return ImportStatus.NEW
        val same = if (format == ImportFormat.MARKDOWN) {
            // A Markdown export carries less than the stored chat, so compare
            // what the export would show of each rather than the chats themselves,
            // with the stored chat's system messages turned into the notes an
            // import makes of them.
            ChatMarkdown.export(ChatMarkdown.withSystemAsNotes(existing.messages), session.id, null, now = 0) ==
                ChatMarkdown.export(session.messages, session.id, null, now = 0)
        } else {
            ChatMarkdown.withSystemAsNotes(existing.messages) == session.messages &&
                existing.displayMessages == session.displayMessages
        }
        return if (same) ImportStatus.IDENTICAL else ImportStatus.CONFLICT
    }

    private fun readJson(text: String): ImportParseResult {
        val root = try {
            ChatArchive.json.parseToJsonElement(text)
        } catch (_: Exception) {
            return ImportParseResult.Failed("The file isn't valid JSON; it may be damaged or cut short.")
        }
        val obj = root as? JsonObject ?: return ImportParseResult.Failed(NOT_RECOGNISED)
        val format = (obj["format"] as? JsonPrimitive)?.content

        if (format == null && "id" in obj && "messages" in obj) {
            return collect(ImportFormat.CHAT_FILE, listOf(obj), emptyList())
        }
        if (format != ChatArchive.FORMAT) return ImportParseResult.Failed(NOT_RECOGNISED)

        val version = (obj["version"] as? JsonPrimitive)?.intOrNull
            ?: return ImportParseResult.Failed("The backup has no version number; it may be damaged.")
        if (version > ChatArchive.VERSION) {
            return ImportParseResult.Failed(
                "This backup was made by a newer version of Gotcha (format $version). Update Gotcha to import it."
            )
        }
        val sessions = obj["sessions"] as? JsonArray
            ?: return ImportParseResult.Failed("The backup has no chat list; it may be damaged.")
        val warnings = if ((obj["includesImages"] as? JsonPrimitive)?.content == "false") {
            listOf("This backup was made without images, so imported chats show none.")
        } else {
            emptyList()
        }
        return collect(ImportFormat.BACKUP, sessions, warnings)
    }

    /** Decodes and validates each chat of a JSON file on its own, so one bad chat can't sink the rest. */
    private fun collect(
        format: ImportFormat,
        elements: List<JsonElement>,
        warnings: List<String>
    ): ImportParseResult {
        val items = mutableListOf<ImportItem>()
        val rejected = mutableListOf<ImportProblem>()
        val notes = warnings.toMutableList()
        elements.forEachIndexed { index, element ->
            val fallbackTitle = ((element as? JsonObject)?.get("title") as? JsonPrimitive)?.content
                ?.takeIf { it.isNotBlank() }
                ?: "Chat ${index + 1}"
            val session = try {
                ChatArchive.json.decodeFromJsonElement(ChatSession.serializer(), element)
            } catch (_: Exception) {
                rejected += ImportProblem(fallbackTitle, "Its data is damaged or incomplete.")
                return@forEachIndexed
            }
            when (val checked = validate(session, notes)) {
                is Validated.Ok -> items += ImportItem(
                    asNotes(withTranscriptOfHistory(checked.session, notes), notes),
                    ImportStatus.NEW
                )
                is Validated.Rejected -> rejected += ImportProblem(fallbackTitle, checked.reason)
            }
        }
        return ImportParseResult.Ready(ImportPreview(format, items, rejected, notes))
    }

    private fun readMarkdown(text: String): ImportParseResult {
        val parsed = try {
            ChatMarkdown.parse(text)
        } catch (e: IllegalArgumentException) {
            return ImportParseResult.Failed(e.message ?: NOT_RECOGNISED)
        }
        val notes = parsed.warnings.toMutableList()
        val session = ChatSession(
            id = parsed.sessionId ?: newId(),
            title = parsed.title.orEmpty(),
            lastModified = parsed.exportedAt ?: now(),
            messages = parsed.messages,
            // Rough, like the engine's own trimming estimate; the next turn recounts.
            tokenCount = parsed.messages.sumOf { it.textContent.length } / 4
        )
        notes += "A Markdown export holds text only: images, attached documents and full tool " +
            "arguments are not part of it."
        return when (val checked = validate(session, notes)) {
            is Validated.Ok -> ImportParseResult.Ready(
                ImportPreview(
                    ImportFormat.MARKDOWN,
                    listOf(ImportItem(checked.session, ImportStatus.NEW)),
                    emptyList(),
                    notes
                )
            )
            is Validated.Rejected -> ImportParseResult.Failed("The export can't be imported: ${checked.reason}")
        }
    }

    /**
     * [session], shown as the model will read it. A backup carries the chat twice
     * — [ChatSession.messages] for the model, [ChatSession.displayMessages] for the
     * screen — and an edited file can make them disagree, so that a message the
     * model acts on is never seen by the user: as good as an instruction slipped
     * in. When they disagree the saved transcript is dropped, and the chat is shown
     * rebuilt from its history on open, as a Markdown import is.
     */
    private fun withTranscriptOfHistory(session: ChatSession, notes: MutableList<String>): ChatSession {
        if (session.displayMessages.isEmpty() || transcriptShowsHistory(session)) return session
        notes += "\"${session.title}\": what it showed didn't match the conversation, so it is shown " +
            "rebuilt from the conversation instead; images appear as placeholders."
        return session.copy(displayMessages = emptyList())
    }

    /**
     * Whether every user prompt and assistant reply in [session]'s history appears
     * in its transcript, in order. The user's prompts must match one for one; the
     * transcript may hold more replies than the history (notices, mode switches),
     * never fewer. System messages are left out: an import turns them into
     * labelled notes regardless ([asNotes]).
     */
    private fun transcriptShowsHistory(session: ChatSession): Boolean {
        val shownPrompts = session.displayMessages.filter { it.kind == MessageKind.USER }.map { it.text.trim() }
        val prompts = session.messages
            .filter { it.role == "user" && !isScreenCapture(it.textContent) }
            .map { promptOf(it.textContent) }
        if (prompts.size != shownPrompts.size) return false
        val promptsMatch = prompts.zip(shownPrompts).all { (prompt, shown) ->
            prompt == shown || (prompt.isEmpty() && shown in ATTACHMENT_PLACEHOLDERS)
        }
        if (!promptsMatch) return false
        val shownReplies = session.displayMessages.filter { it.kind == MessageKind.ASSISTANT }
            .map { it.text.trim() }
            .iterator()
        return session.messages
            .filter { it.role == "assistant" }
            .map { it.textContent.trim() }
            .filter { it.isNotEmpty() }
            .all { reply -> shownReplies.asSequence().any { it == reply } }
    }

    /** What the transcript shows of a user message: its prompt, without attachment text or stand-in prompts. */
    private fun promptOf(content: String): String {
        val prompt = (documentPromptText(content) ?: content).trim()
        return if (prompt == DOCUMENT_ONLY_PROMPT || prompt == DOCUMENTS_ONLY_PROMPT) "" else prompt
    }

    /** A screen capture the agent added as a user message; the transcript shows it as a capture, not a prompt. */
    private fun isScreenCapture(content: String): Boolean =
        content.startsWith("[Screen State]") || content.startsWith("Screen text:")

    /** [session] with its system messages as notes, saying so in [notes] when there were any. */
    private fun asNotes(session: ChatSession, notes: MutableList<String>): ChatSession {
        val count = session.messages.count { it.role == "system" }
        if (count == 0) return session
        notes += "\"${session.title}\": $count system message(s) imported as notes, not instructions."
        return session.copy(messages = ChatMarkdown.withSystemAsNotes(session.messages))
    }

    private sealed class Validated {
        data class Ok(val session: ChatSession) : Validated()
        data class Rejected(val reason: String) : Validated()
    }

    /**
     * Refuses a chat that would break Gotcha when opened, and repairs what can be
     * repaired. The id becomes a file name, so anything but a plain token (a
     * path, say) is replaced rather than trusted.
     */
    private fun validate(session: ChatSession, notes: MutableList<String>): Validated {
        if (session.messages.isEmpty()) return Validated.Rejected("It has no messages.")
        session.messages.firstOrNull { it.role !in ROLES }?.let {
            return Validated.Rejected("It has a message of unknown kind \"${it.role.take(20)}\".")
        }
        var repaired = session
        if (!SAFE_ID.matches(session.id)) {
            repaired = repaired.copy(id = newId())
        }
        if (repaired.title.isBlank()) {
            repaired = repaired.copy(title = fallbackTitle(repaired.messages))
        }
        val latest = now() + CLOCK_SKEW_MS
        if (repaired.lastModified <= 0 || repaired.lastModified > latest) {
            repaired = repaired.copy(lastModified = now())
            notes += "\"${repaired.title}\" had no usable date; it is dated today."
        }
        return Validated.Ok(repaired)
    }

    /** The engine's own untitled fallback, so a title is still generated for it later. */
    private fun fallbackTitle(messages: List<ChatMessage>): String =
        messages.firstOrNull { it.role == "user" }?.textContent?.take(30)?.takeIf { it.isNotBlank() }
            ?: "Imported chat"

    companion object {
        /** A backup with every image in it can be large, but it is read into memory whole. */
        const val MAX_BYTES = 64 * 1024 * 1024

        private const val CLOCK_SKEW_MS = 24 * 60 * 60 * 1000L
        private val ROLES = setOf("user", "assistant", "tool", "system")

        /** What the transcript shows for a message sent with attachments and no text ([com.gotcha.agent.userDisplayText]). */
        private val ATTACHMENT_PLACEHOLDERS = setOf("(image attached)", "(files attached)", "(document attached)")
        private val SAFE_ID = Regex("[A-Za-z0-9_-]{1,100}")
        private const val NOT_RECOGNISED =
            "This isn't a Gotcha chat file. Choose a backup (.gotcha.json) or a chat exported as Markdown."
    }
}
