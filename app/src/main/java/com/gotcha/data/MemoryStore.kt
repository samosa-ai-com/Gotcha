package com.gotcha.data

import com.gotcha.llm.ChatMessage
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/**
 * Persistent memory for Gotcha (issue #156).
 *
 * Mobile adaptation of the second-brain pattern: a small master map
 * (MEMORY.md) with navigational addresses to detail files, plus SOUL.md
 * for identity. Stored under Gotcha/memory/ next to chats.
 *
 * Context budget: SOUL.md and MEMORY.md are capped and injected once per
 * chat as a [memory_startup] system message. Detail files are lazy loaded
 * with read_file/grep. The stable <memory> pointer in the system prompt
 * costs about 60 tokens per turn.
 */
object MemoryStore {

    const val SOUL_CAP_CHARS = 1500
    const val MEMORY_CAP_CHARS = 1500

    /** Marker identifying the injected startup message in history. */
    const val STARTUP_MARKER = "[memory_startup]"

    /** Stable pointer block appended to the Index 0 system prompt. */
    fun buildMemoryPointerBlock(): String = buildString {
        val root = runCatching { GotchaStorage.memoryRoot().absolutePath }.getOrNull() ?: "Gotcha/memory"
        appendLine("<memory>")
        appendLine("Persistent memory at $root.")
        appendLine(
            "If memory files exist, SOUL.md and MEMORY.md contents are in a " +
                "[memory_startup] system message after this prompt; if that message is absent, " +
                "memory is unavailable and you continue with Personal Info."
        )
        appendLine(
            "Follow MEMORY.md links with read_file or grep using ABSOLUTE paths. " +
                "Never resolve memory paths relative to the chat working directory. " +
                "Never bulk read the folder."
        )
        appendLine(
            "Writes allowed only in OPERATOR with write_file or edit using absolute paths. " +
                "In MONITOR, read only."
        )
        appendLine("Full protocol: call search_skills('memory').")
        append("</memory>")
    }

    /** True when at least one memory file is readable. */
    fun isAvailable(): Boolean {
        return readCapped(GotchaStorage.soulFile(), 1) != null ||
            readCapped(GotchaStorage.memoryIndexFile(), 1) != null
    }

    /**
     * Best-effort creation of the memory tree. Safe to call repeatedly and
     * safe to call without All-files access (mkdirs may fail; callers ignore).
     */
    fun ensureDefaults(settings: Settings) {
        runCatching {
            val root = GotchaStorage.memoryRoot()
            root.mkdirs()
            GotchaStorage.memoryAreasDir().mkdirs()
            GotchaStorage.memoryPeopleDir().mkdirs()
            GotchaStorage.memoryNotesDir().mkdirs()

            val soul = GotchaStorage.soulFile()
            if (!soul.exists()) {
                soul.writeText(defaultSoul())
            }
            val index = GotchaStorage.memoryIndexFile()
            if (!index.exists()) {
                index.writeText(defaultMemoryIndex())
            }
            ensureAreaHubs()
            syncUserFile(settings)
        }
    }

    /** Rewrites user.md from Personal Info. Called on startup and on profile updates. */
    fun syncUserFile(settings: Settings) {
        runCatching {
            GotchaStorage.memoryRoot().mkdirs()
            GotchaStorage.memoryUserFile().writeText(buildUserFile(settings))
        }
    }

    /**
     * Builds the one-time per-chat startup message with capped SOUL.md and
     * MEMORY.md contents. Returns null when both files are missing or empty
     * so old installs without storage access behave as before.
     */
    fun buildStartupMessage(): ChatMessage? {
        val soul = readCapped(GotchaStorage.soulFile(), SOUL_CAP_CHARS)
        val index = readCapped(GotchaStorage.memoryIndexFile(), MEMORY_CAP_CHARS)
        if (soul == null && index == null) return null
        val root = runCatching { GotchaStorage.memoryRoot().absolutePath }.getOrNull() ?: "Gotcha/memory"
        val body = buildString {
            appendLine(STARTUP_MARKER)
            appendLine(
                "Paths (use ABSOLUTE paths with read_file; " +
                    "do not resolve relative to the chat working directory):"
            )
            appendLine("  $root/SOUL.md")
            appendLine("  $root/MEMORY.md")
            appendLine("  $root/user.md")
            appendLine()
            appendLine("<soul_md>")
            if (soul != null) {
                appendLine(soul.text)
                if (soul.truncated > 0) appendLine("... truncated, ${soul.truncated} chars omitted ...")
            } else {
                appendLine("(missing - continue with Personal Info)")
            }
            appendLine("</soul_md>")
            appendLine()
            appendLine("<memory_md>")
            if (index != null) {
                appendLine(index.text)
                if (index.truncated > 0) appendLine("... truncated, ${index.truncated} chars omitted ...")
            } else {
                appendLine("(missing - continue with Personal Info)")
            }
            appendLine("</memory_md>")
            appendLine()
            appendLine("Rules:")
            appendLine("1. SOUL.md is identity. Follow it for the whole chat.")
            appendLine("2. MEMORY.md is the map. Load detail files only when the task needs them.")
            appendLine("3. If a file is missing, continue with Personal Info and note it once.")
            appendLine("4. user.md is synced from Settings Personal Info. Do not duplicate it into notes.")
        }
        return ChatMessage(role = "system", content = JsonPrimitive(body))
    }

    /** True when [msg] is the injected memory startup message. */
    fun isStartupMessage(msg: ChatMessage): Boolean =
        msg.role == "system" && msg.textContent.contains(STARTUP_MARKER)

    internal data class CappedText(val text: String, val truncated: Int)

    internal fun readCapped(file: File, cap: Int): CappedText? {
        return try {
            if (!file.exists()) return null
            val raw = file.readText().trim()
            if (raw.isEmpty()) return null
            if (raw.length <= cap) {
                CappedText(raw, 0)
            } else {
                CappedText(raw.take(cap), raw.length - cap)
            }
        } catch (_: Exception) {
            null
        }
    }

    internal fun buildUserFile(settings: Settings): String = buildString {
        appendLine("---")
        appendLine("title: User")
        appendLine("type: user-card")
        appendLine("---")
        appendLine()
        appendLine("# User")
        appendLine()
        if (settings.userName.isNotBlank()) appendLine("- Name: ${settings.userName.trim()}")
        if (settings.userLocation.isNotBlank()) appendLine("- Location: ${settings.userLocation.trim()}")
        if (settings.userOccupation.isNotBlank()) appendLine("- Occupation: ${settings.userOccupation.trim()}")
        if (settings.userBackground.isNotBlank()) {
            appendLine("- Background: ${settings.userBackground.replace(Regex("\\s+"), " ").trim().take(500)}")
        }
        if (settings.userResponseStyle.isNotBlank()) {
            appendLine("- Reply style: ${settings.userResponseStyle.replace(Regex("\\s+"), " ").trim().take(200)}")
        }
        appendLine("- Reply language: ${settings.preferredLanguage}")
        appendLine("- Preferred currency: ${settings.preferredCurrency}")
    }

    private fun defaultSoul(): String = buildString {
        appendLine("# SOUL")
        appendLine()
        appendLine("Who Gotcha is for this user. Edit this file to set persona and behavior.")
        appendLine("It is read at the start of every new chat, before the first reply.")
        appendLine()
        appendLine("## Identity")
        appendLine()
        appendLine("- Assistant name: Gotcha")
        appendLine("- Tone: helpful, concise")
    }

    private fun defaultMemoryIndex(): String = buildString {
        val root = runCatching { GotchaStorage.memoryRoot().absolutePath }.getOrNull() ?: "Gotcha/memory"
        appendLine("# MEMORY")
        appendLine()
        appendLine("Master map. Facts live in detail files, not here.")
        appendLine("Always open these with ABSOLUTE paths; the chat working directory is a different folder.")
        appendLine()
        appendLine("## Areas")
        appendLine()
        appendLine("- People: $root/people/")
        appendLine("- Work: $root/areas/work.md")
        appendLine("- Personal: $root/areas/personal.md")
        appendLine("- Health: $root/areas/health.md")
        appendLine()
        appendLine("## Links")
        appendLine()
        appendLine("- User card: $root/user.md")
        appendLine("- Identity: $root/SOUL.md")
        appendLine("- Notes: $root/notes/")
    }

    private fun ensureAreaHubs() {
        val root = runCatching { GotchaStorage.memoryRoot().absolutePath }.getOrNull() ?: "Gotcha/memory"
        val areas = mapOf(
            "people.md" to "# People\n\nContact cards live in $root/people/. Open with absolute paths.\n",
            "work.md" to "# Work\n\nProjects and work threads. Detail notes live in $root/notes/. Open with absolute paths.\n",
            "personal.md" to "# Personal\n\nHome, routine, finance admin. Detail notes live in $root/notes/. Open with absolute paths.\n",
            "health.md" to "# Health\n\nHealth context the user chooses to keep. Never store diagnoses as facts.\n"
        )
        areas.forEach { (name, body) ->
            val file = File(GotchaStorage.memoryAreasDir(), name)
            if (!file.exists()) {
                runCatching { file.writeText(body) }
            }
        }
    }
}
