package com.gotcha.data

import androidx.compose.ui.geometry.Offset
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
@Suppress("TooManyFunctions")
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
            GotchaStorage.memoryRawDir().mkdirs()

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

    /** One file listed by the Second Brain viewer. Content is capped for display. */
    data class BrainFile(
        val name: String,
        val content: String,
        val truncated: Boolean,
        val sizeBytes: Long = content.length.toLong(),
        val absolutePath: String = ""
    ) {
        val extension: String get() = name.substringAfterLast('.', "").lowercase()
        val isMarkdown: Boolean get() = extension == "md" || extension == "markdown" || extension.isEmpty()
    }

    /** Everything the Second Brain viewer shows, loaded best-effort. */
    data class SecondBrainSnapshot(
        val available: Boolean,
        val soul: BrainFile?,
        val memoryIndex: BrainFile?,
        val userCard: BrainFile?,
        val areas: List<BrainFile>,
        val people: List<BrainFile>,
        val notes: List<BrainFile>,
        val raw: List<BrainFile> = emptyList()
    ) {
        /** Every markdown node shown on the graph, keyed by file name. */
        fun allMarkdown(): List<BrainFile> = listOfNotNull(soul, memoryIndex, userCard) + areas + people + notes
    }

    /** Max chars shown per file in the viewer. */
    const val VIEWER_FILE_CAP_CHARS = 8000

    /** Matches Obsidian-style links: [[path]] or [[path|label]]. */
    val wikiLinkRegex = Regex("""\[\[([^\]|]+)(?:\|[^\]]*)?]]""")

    /** Extracts link targets from markdown, in order, deduplicated. */
    fun parseWikiLinks(markdown: String): List<String> =
        wikiLinkRegex.findAll(markdown).map { it.groupValues[1].trim() }.filter { it.isNotEmpty() }.distinct().toList()

    /** One node on the Second Brain graph. */
    data class BrainNode(val id: String, val label: String, val kind: String)

    /** One directed link between two node ids. */
    data class BrainEdge(val fromId: String, val toId: String)

    /** Nodes plus resolved edges for the graph view. */
    data class BrainGraph(val nodes: List<BrainNode>, val edges: List<BrainEdge>)

    /**
     * Builds the node-link graph: one node per markdown file plus one per raw
     * file, edges from [[links]] resolved against relative paths first
     * (areas/work.md) then bare names (work.md), deduplicated.
     *
     * Node ids are memory-relative paths (areas/work.md, raw/memo.mp3), so two
     * sections can hold same-named files without colliding. Link targets are
     * parsed from the full file on disk, not the capped preview, up to
     * [LINK_SCAN_CAP_CHARS].
     */
    fun buildGraph(snapshot: SecondBrainSnapshot): BrainGraph {
        val nodes = mutableListOf<BrainNode>()
        val byFull = mutableMapOf<String, String>()
        val byBase = mutableMapOf<String, String>()
        fun norm(path: String): String = path.lowercase().removeSuffix(".md").removeSuffix(".markdown")
        fun addNode(id: String, label: String, kind: String) {
            nodes.add(BrainNode(id, label, kind))
            byFull.putIfAbsent(norm(id), id)
            byBase.putIfAbsent(norm(id.substringAfterLast('/')), id)
        }
        fun relId(file: BrainFile, fallback: String): String {
            val abs = file.absolutePath
            if (abs.isNotBlank()) {
                val root = runCatching { GotchaStorage.memoryRoot().absolutePath }.getOrNull()
                if (root != null && abs.startsWith(root)) {
                    return abs.removePrefix(root).trimStart('/')
                }
            }
            return fallback
        }
        data class Source(val id: String, val file: BrainFile)
        val sources = mutableListOf<Source>()
        fun addSource(file: BrainFile?, fallbackId: String, label: String, kind: String) {
            if (file == null) return
            val id = relId(file, fallbackId)
            addNode(id, label, kind)
            sources.add(Source(id, file))
        }
        addSource(snapshot.soul, "SOUL.md", "SOUL", "soul")
        addSource(snapshot.memoryIndex, "MEMORY.md", "MEMORY", "map")
        addSource(snapshot.userCard, "user.md", "User", "user")
        snapshot.areas.forEach { addSource(it, "areas/${it.name}", it.name.removeSuffix(".md"), "area") }
        snapshot.people.forEach { addSource(it, "people/${it.name}", it.name.removeSuffix(".md"), "person") }
        snapshot.notes.forEach { addSource(it, "notes/${it.name}", it.name.removeSuffix(".md"), "note") }
        snapshot.raw.forEach { addSource(it, "raw/${it.name}", it.name, "raw") }
        fun resolve(target: String): String? {
            val normTarget = norm(target.trim())
            if (normTarget.isEmpty()) return null
            return byFull[normTarget] ?: byBase[normTarget] ?: byBase[normTarget.substringAfterLast('/')]
        }
        val edges = mutableSetOf<BrainEdge>()
        sources.forEach { source ->
            if (!source.file.isMarkdown) return@forEach
            parseWikiLinks(fullTextForLinks(source.file)).forEach { target ->
                val toId = resolve(target)
                if (toId != null && toId != source.id) edges.add(BrainEdge(source.id, toId))
            }
        }
        return BrainGraph(nodes, edges.toList())
    }

    /** Max chars scanned per file for [[link]] parsing (preview cap stays smaller). */
    const val LINK_SCAN_CAP_CHARS = 200_000

    /** Brain shell radii in graph units. Layout content is confined inside these. */
    const val BRAIN_RX = 520f
    const val BRAIN_RY = 360f
    const val BRAIN_RZ = 280f

    /**
     * Pulls every point inside the brain ellipsoid (with margin), so no neuron
     * can sit outside the shell no matter how many files are added later.
     * Returns the adjusted positions and depths.
     */
    fun confineToBrain(points: List<Offset>, depths: List<Float>): Pair<List<Offset>, List<Float>> {
        if (points.size != depths.size) return points to depths
        return points.zip(depths).map { (p, z) ->
            val e = (p.x / BRAIN_RX) * (p.x / BRAIN_RX) +
                (p.y / BRAIN_RY) * (p.y / BRAIN_RY) +
                (z / BRAIN_RZ) * (z / BRAIN_RZ)
            if (e <= 0.81f) {
                p to z
            } else {
                val s = (0.9f / kotlin.math.sqrt(e))
                Offset(p.x * s, p.y * s) to (z * s)
            }
        }.unzip()
    }

    /**
     * Force-directed layout (Fruchterman-Reingold, as used by Obsidian graph
     * plugins): repulsion pushes all nodes apart, springs pull linked nodes
     * together, gravity keeps the cloud centered. Fixed seed, so the same
     * vault always draws the same brain. Coordinates are centered at the
     * origin in a 1000x700 box; the viewer scales them to the canvas.
     */
    fun forceLayout(
        nodeCount: Int,
        edgeIndexPairs: List<Pair<Int, Int>>,
        iterations: Int = 160
    ): List<Offset> {
        if (nodeCount == 0) return emptyList()
        if (nodeCount == 1) return listOf(Offset.Zero)
        val width = 1000f
        val height = 700f
        val random = kotlin.random.Random(42)
        val pos = Array(nodeCount) {
            Offset(random.nextFloat() * width - width / 2f, random.nextFloat() * height - height / 2f)
        }
        val area = width * height
        val k = kotlin.math.sqrt(area / nodeCount)
        var temperature = minOf(width, height) / 4f
        repeat(iterations) {
            val disp = Array(nodeCount) { Offset.Zero }
            for (i in 0 until nodeCount) {
                for (j in i + 1 until nodeCount) {
                    val delta = pos[i] - pos[j]
                    val dist = delta.getDistance().coerceAtLeast(0.01f)
                    val force = k * k / dist
                    val push = delta / dist * force
                    disp[i] += push
                    disp[j] -= push
                }
            }
            edgeIndexPairs.forEach { (a, b) ->
                if (a in 0 until nodeCount && b in 0 until nodeCount && a != b) {
                    val delta = pos[a] - pos[b]
                    val dist = delta.getDistance().coerceAtLeast(0.01f)
                    val force = dist * dist / k
                    val pull = delta / dist * force
                    disp[a] -= pull
                    disp[b] += pull
                }
            }
            for (i in 0 until nodeCount) {
                disp[i] -= pos[i] * 0.02f
                val len = disp[i].getDistance().coerceAtLeast(0.01f)
                val capped = disp[i] / len * minOf(len, temperature)
                pos[i] = Offset(
                    (pos[i].x + capped.x).coerceIn(-width / 2f, width / 2f),
                    (pos[i].y + capped.y).coerceIn(-height / 2f, height / 2f)
                )
            }
            temperature *= 0.95f
        }
        return pos.toList()
    }

    /** Full text for link parsing, read straight from disk to see past the preview cap. */
    private fun fullTextForLinks(file: BrainFile): String {
        val abs = file.absolutePath
        if (abs.isNotBlank()) {
            return readUpTo(File(abs), LINK_SCAN_CAP_CHARS) ?: file.content
        }
        return file.content
    }

    /** Reads at most [maxChars] chars; null on any failure. Never loads the whole file. */
    private fun readUpTo(file: File, maxChars: Int): String? {
        return try {
            if (!file.exists() || !file.isFile) return null
            file.bufferedReader().use { reader ->
                val out = StringBuilder()
                val buf = CharArray(8192)
                var remaining = maxChars + 1
                while (remaining > 0) {
                    val n = reader.read(buf, 0, minOf(buf.size, remaining))
                    if (n < 0) break
                    out.append(buf, 0, n)
                    remaining -= n
                }
                out.toString()
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Loads all viewer content. Never throws; missing files become null/empty lists. */
    fun loadSnapshot(): SecondBrainSnapshot {
        val soul = loadBrainFile(GotchaStorage.soulFile())
        val index = loadBrainFile(GotchaStorage.memoryIndexFile())
        val user = loadBrainFile(GotchaStorage.memoryUserFile())
        return SecondBrainSnapshot(
            available = soul != null || index != null,
            soul = soul,
            memoryIndex = index,
            userCard = user,
            areas = listBrainFiles(GotchaStorage.memoryAreasDir()),
            people = listBrainFiles(GotchaStorage.memoryPeopleDir()),
            notes = listBrainFiles(GotchaStorage.memoryNotesDir()),
            raw = listRawFiles(GotchaStorage.memoryRawDir())
        )
    }

    /** Extensions previewed as text in the viewer; anything else shows file info plus Open. */
    private val textExtensions = setOf("md", "markdown", "txt", "yaml", "yml", "json", "csv", "log")

    private fun loadBrainFile(file: File): BrainFile? {
        return try {
            if (!file.exists() || !file.isFile) return null
            val ext = file.name.substringAfterLast('.', "").lowercase()
            val size = file.length()
            if (ext.isNotEmpty() && ext !in textExtensions) {
                return BrainFile(file.name, "", false, size, file.absolutePath)
            }
            val text = readUpTo(file, VIEWER_FILE_CAP_CHARS) ?: return null
            if (text.isBlank()) return null
            if (text.length <= VIEWER_FILE_CAP_CHARS) {
                BrainFile(file.name, text, false, size, file.absolutePath)
            } else {
                BrainFile(file.name, text.take(VIEWER_FILE_CAP_CHARS), true, size, file.absolutePath)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun listBrainFiles(dir: File): List<BrainFile> {
        return try {
            if (!dir.exists() || !dir.isDirectory) return emptyList()
            dir.listFiles()
                ?.filter { it.isFile && it.name.endsWith(".md") }
                ?.sortedBy { it.name.lowercase() }
                ?.mapNotNull { loadBrainFile(it) }
                .orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Raw drops in any format: audio, images, documents. Content loads only for text types. */
    private fun listRawFiles(dir: File): List<BrainFile> {
        return try {
            if (!dir.exists() || !dir.isDirectory) return emptyList()
            dir.listFiles()
                ?.filter { it.isFile }
                ?.sortedBy { it.name.lowercase() }
                ?.mapNotNull { loadBrainFile(it) }
                .orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
    }

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
