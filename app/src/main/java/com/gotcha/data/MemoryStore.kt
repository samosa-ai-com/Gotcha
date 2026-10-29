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
@Suppress("TooManyFunctions", "LargeClass")
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
            migrateLegacyHubs()
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
                if (soul.truncated) appendLine("... truncated, showing first $SOUL_CAP_CHARS chars ...")
            } else {
                appendLine("(missing - continue with Personal Info)")
            }
            appendLine("</soul_md>")
            appendLine()
            appendLine("<memory_md>")
            if (index != null) {
                appendLine(index.text)
                if (index.truncated) appendLine("... truncated, showing first $MEMORY_CAP_CHARS chars ...")
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
            appendLine("5. Before writing memory, load the full protocol: search_skills('memory').")
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

        /** True when no file is shown: viewer search with no matches. */
        fun isEmpty(): Boolean {
            if (soul != null || memoryIndex != null || userCard != null) return false
            return areas.isEmpty() && people.isEmpty() && notes.isEmpty() && raw.isEmpty()
        }
    }

    /** Max chars shown per file in the viewer. */
    const val VIEWER_FILE_CAP_CHARS = 8000

    /** Matches Obsidian-style links: [[path]] or [[path|label]]. */
    val wikiLinkRegex = Regex("""\[\[([^\]|]+)(?:\|[^\]]*)?]]""")

    /** Fenced code blocks (backtick or tilde, closed or running to end of file): links inside examples are not real links. */
    private val fenceRegex = Regex("(?s)(```|~~~).*?(\\1|\\Z)")

    /** Extracts link targets from markdown prose, in order, deduplicated. */
    fun parseWikiLinks(markdown: String): List<String> =
        wikiLinkRegex.findAll(fenceRegex.replace(markdown, ""))
            .map { it.groupValues[1].trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()

    /** One node on the Second Brain graph. */
    data class BrainNode(val id: String, val label: String, val kind: String)

    /** One directed link between two node ids. */
    data class BrainEdge(val fromId: String, val toId: String)

    /** Nodes plus resolved edges for the graph view. */
    data class BrainGraph(val nodes: List<BrainNode>, val edges: List<BrainEdge>)

    /**
     * Builds the node-link graph: one node per markdown file plus one per raw
     * file, edges from [[links]] resolved source-relative first
     * (notes/plan from notes/trip.md), then vault-relative (areas/work.md),
     * then a bare name only when it is unique. Dangling and self links and
     * ambiguous bare names produce no edge. Deduplicated.
     *
     * Node ids are memory-relative paths (areas/work.md, raw/memo.mp3), so two
     * sections can hold same-named files without colliding. Link targets are
     * parsed from the full file on disk, not the capped preview, up to
     * [LINK_SCAN_CAP_CHARS].
     */
    fun buildGraph(snapshot: SecondBrainSnapshot): BrainGraph {
        val index = buildNodeIndex(snapshot)
        return BrainGraph(index.nodes, graphEdges(index, null).toList())
    }

    /**
     * Graph edges from [index]. When [texts] is supplied the preloaded bounded
     * file contents are used; otherwise each file is read on demand. Sharing
     * one read per file keeps the health check cheap on large vaults.
     */
    private fun graphEdges(index: NodeIndex, texts: Map<String, String>?): Set<BrainEdge> {
        val edges = mutableSetOf<BrainEdge>()
        index.sources.forEach { source ->
            if (!source.file.isMarkdown) return@forEach
            val text = texts?.get(source.id) ?: fullTextForLinks(source.file)
            parseWikiLinks(text).forEach { target ->
                val toId = resolveGraphLink(target, source.id, index)
                if (toId != null && toId != source.id) edges.add(BrainEdge(source.id, toId))
            }
        }
        return edges
    }

    /** One link source: a graph node id plus its file. */
    private data class GraphSource(val id: String, val file: BrainFile)

    /** Graph nodes, link sources and the normalized path index, shared by the graph and health checks. */
    private data class NodeIndex(
        val nodes: List<BrainNode>,
        val sources: List<GraphSource>,
        val byFull: Map<String, String>
    )

    private fun normGraphPath(path: String): String =
        path.lowercase().removeSuffix(".md").removeSuffix(".markdown")

    /** Memory-relative id (areas/work.md) from the absolute path, or [fallback]. */
    fun relativeMemoryId(file: BrainFile, fallback: String): String {
        val abs = file.absolutePath
        if (abs.isNotBlank()) {
            val root = runCatching { GotchaStorage.memoryRoot().absolutePath }.getOrNull()
            if (root != null && abs.startsWith(root)) {
                return abs.removePrefix(root).trimStart('/')
            }
        }
        return fallback
    }

    private fun buildNodeIndex(snapshot: SecondBrainSnapshot): NodeIndex {
        val nodes = mutableListOf<BrainNode>()
        val byFull = mutableMapOf<String, String>()
        val sources = mutableListOf<GraphSource>()
        fun addNode(id: String, label: String, kind: String) {
            nodes.add(BrainNode(id, label, kind))
            byFull.putIfAbsent(normGraphPath(id), id)
        }
        fun addSource(file: BrainFile?, fallbackId: String, label: String, kind: String) {
            if (file == null) return
            val id = relativeMemoryId(file, fallbackId)
            addNode(id, label, kind)
            sources.add(GraphSource(id, file))
        }
        addSource(snapshot.soul, "SOUL.md", "SOUL", "soul")
        addSource(snapshot.memoryIndex, "MEMORY.md", "MEMORY", "map")
        addSource(snapshot.userCard, "user.md", "User", "user")
        snapshot.areas.forEach { addSource(it, "areas/${it.name}", it.name.removeSuffix(".md"), "area") }
        snapshot.people.forEach { addSource(it, "people/${it.name}", it.name.removeSuffix(".md"), "person") }
        snapshot.notes.forEach { addSource(it, "notes/${it.name}", it.name.removeSuffix(".md"), "note") }
        snapshot.raw.forEach { addSource(it, "raw/${it.name}", it.name, "raw") }
        return NodeIndex(nodes, sources, byFull)
    }

    /**
     * Resolves one [[link]] target to a node id, or null when dangling,
     * self-pointing or ambiguous. Source-relative first, vault-relative
     * second, unique bare names last.
     */
    private fun resolveGraphLink(target: String, sourceId: String, index: NodeIndex): String? {
        val raw = target.trim()
        if (raw.isEmpty()) return null
        graphCandidates(raw, sourceId).forEach { candidate ->
            index.byFull[normGraphPath(candidate)]?.let { return it }
        }
        // (c) Bare-name fallback only when it names exactly one node.
        val base = normGraphPath(raw).substringAfterLast('/')
        return index.nodes
            .filter { normGraphPath(it.id).substringAfterLast('/') == base }
            .map { it.id }
            .singleOrNull()
    }

    /** Ordered lookup paths for a link target: source-relative, then vault-relative. */
    private fun graphCandidates(raw: String, sourceId: String): List<String> {
        val sourceDir = sourceId.substringBeforeLast('/', "")
        return buildList {
            if (sourceDir.isNotEmpty()) {
                add("$sourceDir/$raw")
                add("$sourceDir/$raw.md")
                add("$sourceDir/$raw.markdown")
            }
            add(raw)
            add("$raw.md")
            add("$raw.markdown")
        }
    }

    /**
     * True when [target] matches several nodes by bare name without matching
     * any relative or vault path: ambiguous, not missing. The graph draws no
     * edge for these either, so health does not report them as dangling.
     */
    private fun hasAmbiguousMatches(target: String, sourceId: String, index: NodeIndex): Boolean {
        val raw = target.trim()
        if (raw.isEmpty()) return false
        if (graphCandidates(raw, sourceId).any { index.byFull.containsKey(normGraphPath(it)) }) return false
        val base = normGraphPath(raw).substringAfterLast('/')
        return index.nodes.count { normGraphPath(it.id).substringAfterLast('/') == base } > 1
    }

    /** One [[link]] that points nowhere (or at its own file). */
    data class DanglingLink(val fromId: String, val target: String)

    /** One detail record that fails the schema, with its reasons. */
    data class RecordProblem(val fileId: String, val errors: List<String>)

    /** One note whose `task_ref` names a task id no scheduled task carries. */
    data class TaskNoteRef(val fileId: String, val taskId: String)

    /** One scheduled task for the health check: id, display name and linked note path. */
    data class TaskLink(val id: String, val name: String, val notePath: String?)

    /** One note whose `task_ref` names a task that points at a different note (or nowhere). */
    data class TaskMismatch(val fileId: String, val taskId: String, val linkedPath: String?)

    /** One scheduled task whose linked note no longer exists in the vault. */
    data class StaleTaskLink(val taskName: String, val path: String)

    /** One scheduled task pointing at a real note that does not name it back in `task_ref`. */
    data class MissingBacklink(val fileId: String, val taskName: String)

    /** Orphan dashboard: everything that keeps the Second Brain from hanging together. */
    data class BrainHealth(
        val unlinked: List<String>,
        val dangling: List<DanglingLink>,
        val invalid: List<RecordProblem>,
        val unknownTasks: List<TaskNoteRef>,
        val mismatchedTasks: List<TaskMismatch> = emptyList(),
        val staleTaskLinks: List<StaleTaskLink> = emptyList(),
        val unscanned: List<String> = emptyList(),
        val missingBacklinks: List<MissingBacklink> = emptyList()
    ) {
        val isClean: Boolean
            get() = unlinked.isEmpty() && dangling.isEmpty() && invalid.isEmpty() &&
                unknownTasks.isEmpty() && mismatchedTasks.isEmpty() &&
                staleTaskLinks.isEmpty() && unscanned.isEmpty() && missingBacklinks.isEmpty()
        val issueCount: Int
            get() = unlinked.size + dangling.size + invalid.size + unknownTasks.size +
                mismatchedTasks.size + staleTaskLinks.size + unscanned.size + missingBacklinks.size
    }

    /** Root files are navigation hubs, not content: never reported as unlinked. */
    private val healthRootIds = setOf("SOUL.md", "MEMORY.md", "user.md")

    /**
     * Checks the whole vault for orphans: nodes with no links in or out,
     * [[links]] that resolve nowhere, detail records failing the schema, notes
     * whose `task_ref` names an unknown or mismatched task, tasks pointing at
     * deleted notes, and files too large for the link scan. Each file is read
     * once, bounded by [LINK_SCAN_CAP_CHARS].
     */
    fun checkHealth(
        snapshot: SecondBrainSnapshot,
        taskLinks: List<TaskLink> = emptyList()
    ): BrainHealth {
        val index = buildNodeIndex(snapshot)
        val texts = index.sources
            .filter { it.file.isMarkdown }
            .associate { it.id to fullText(it.file) }
        val linked = graphEdges(index, texts).flatMap { listOf(it.fromId, it.toId) }.toSet()
        val unlinked = index.nodes
            .map { it.id }
            .filter { it !in linked && it !in healthRootIds }
            .sorted()
        val dangling = danglingFor(index, texts)
        val byTaskId = taskLinks.associateBy { it.id }
        val vaultNotePaths = detailFiles(snapshot).mapNotNull { (file, _) ->
            runCatching { File(file.absolutePath).canonicalPath }.getOrNull()
        }.toSet()
        val invalid = mutableListOf<RecordProblem>()
        val unknownTasks = mutableListOf<TaskNoteRef>()
        val mismatched = mutableListOf<TaskMismatch>()
        val unscanned = mutableListOf<String>()
        // Canonical note path to (id, task_ref) for the reverse backlink pass.
        val noteRefs = mutableMapOf<String, Pair<String, String?>>()
        detailFiles(snapshot).forEach { (file, dirKind) ->
            val id = relativeMemoryId(file, "$dirKind/${file.name}")
            val full = texts[id] ?: fullText(file)
            if ((texts[id]?.length ?: 0) > LINK_SCAN_CAP_CHARS) unscanned.add(id)
            val check = validateRecord(file.name, full, dirKind)
            if (!check.valid) invalid.add(RecordProblem(id, check.errors))
            val ref = frontmatterOf(full)?.get("task_ref")?.takeIf { it.isNotBlank() }
            runCatching { File(file.absolutePath).canonicalPath }.getOrNull()?.let { canonical ->
                noteRefs[canonical] = id to ref
            }
            ref?.let {
                val task = byTaskId[it]
                when {
                    task == null -> unknownTasks.add(TaskNoteRef(id, it))
                    task.notePath == null || !samePath(task.notePath, file.absolutePath) ->
                        mismatched.add(TaskMismatch(id, it, task.notePath))
                }
            }
        }
        val stale = mutableListOf<StaleTaskLink>()
        val missingBacklinks = mutableListOf<MissingBacklink>()
        taskLinks.forEach { task ->
            val path = task.notePath ?: return@forEach
            val canonical = runCatching { File(path).canonicalPath }.getOrNull()
            val entry = canonical?.let { noteRefs[it] }
            when {
                entry == null && vaultNotePaths.none { samePath(it, path) } ->
                    stale.add(StaleTaskLink(task.name, path))
                entry != null && entry.second != task.id ->
                    missingBacklinks.add(MissingBacklink(entry.first, task.name))
            }
        }
        return BrainHealth(
            unlinked.sorted(),
            dangling,
            invalid,
            unknownTasks,
            mismatched,
            stale,
            unscanned.sorted(),
            missingBacklinks.sortedBy { it.fileId }
        )
    }

    /** Every detail record with its top-level dir for schema checks. */
    private fun detailFiles(snapshot: SecondBrainSnapshot): List<Pair<BrainFile, String>> =
        snapshot.areas.map { it to "areas" } +
            snapshot.people.map { it to "people" } +
            snapshot.notes.map { it to "notes" }

    /** Links that resolve nowhere, in stable order. Self-links count: they draw nothing. */
    fun findDanglingLinks(snapshot: SecondBrainSnapshot): List<DanglingLink> {
        val index = buildNodeIndex(snapshot)
        val texts = index.sources.associate { it.id to fullText(it.file) }
        return danglingFor(index, texts)
    }

    /**
     * Dangling links from preloaded [texts]. Ambiguous bare names are skipped:
     * they match several files rather than none.
     */
    private fun danglingFor(index: NodeIndex, texts: Map<String, String>): List<DanglingLink> {
        return index.sources
            .filter { it.file.isMarkdown }
            .flatMap { source ->
                val text = texts[source.id] ?: fullText(source.file)
                parseWikiLinks(text).mapNotNull { target ->
                    val toId = resolveGraphLink(target, source.id, index)
                    when {
                        toId == null && !hasAmbiguousMatches(target, source.id, index) ->
                            DanglingLink(source.id, target)
                        toId != null && toId == source.id -> DanglingLink(source.id, target)
                        else -> null
                    }
                }
            }
            .distinct()
            .sortedWith(compareBy({ it.fromId }, { it.target }))
    }

    /** True when two paths name the same file, best-effort. */
    private fun samePath(a: String, b: String): Boolean {
        if (a == b) return true
        return runCatching { File(a).canonicalPath == File(b).canonicalPath }.getOrDefault(false)
    }

    /** Frontmatter values of [content], or null when it carries none. Errors are ignored. */
    private fun frontmatterOf(content: String): Map<String, String>? {
        val lines = content.lines()
        if (lines.isEmpty() || lines[0].trim() != "---") return null
        val end = lines.drop(1).indexOfFirst { it.trim() == "---" }
        if (end < 0) return null
        val values = mutableMapOf<String, String>()
        parseFrontmatterLines("", lines.drop(1).take(end), values, mutableListOf())
        return values
    }

    /** Full text for checks that must see past the viewer preview cap. */
    private fun fullText(file: BrainFile): String {
        val abs = file.absolutePath
        if (abs.isNotBlank()) {
            readUpTo(File(abs), LINK_SCAN_CAP_CHARS)?.let { return it }
        }
        return file.content
    }

    /** Viewer search (Phase 3): snapshot copy holding only files matching [query] in name or content. */
    fun filterSnapshot(snapshot: SecondBrainSnapshot, query: String): SecondBrainSnapshot {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return snapshot
        fun match(file: BrainFile): Boolean {
            if (file.name.lowercase().contains(q)) return true
            if (file.content.lowercase().contains(q)) return true
            // Truncated previews stop at the viewer cap: search the full file
            // before reporting no match. Bounded like every other vault read.
            if (!file.truncated) return false
            val abs = file.absolutePath
            if (abs.isBlank()) return false
            return readUpTo(File(abs), LINK_SCAN_CAP_CHARS)?.lowercase()?.contains(q) ?: false
        }
        fun matchOpt(file: BrainFile?): BrainFile? = if (file != null && match(file)) file else null
        return snapshot.copy(
            soul = matchOpt(snapshot.soul),
            memoryIndex = matchOpt(snapshot.memoryIndex),
            userCard = matchOpt(snapshot.userCard),
            areas = snapshot.areas.filter(::match),
            people = snapshot.people.filter(::match),
            notes = snapshot.notes.filter(::match),
            raw = snapshot.raw.filter(::match)
        )
    }

    /**
     * Duplicate guard: slug of a title or file name, so "Asha Sharma",
     * "asha-sharma.md" and "Asha-Sharma" all compare equal. The agent checks
     * this (and greps) before creating a person, area or note.
     */
    fun slugify(text: String): String =
        text.lowercase()
            .map { if (it.isLetterOrDigit()) it else '-' }
            .joinToString("")
            .replace(Regex("-+"), "-")
            .trim('-')

    /** Strips a real record extension (.md, .markdown); dots inside titles are kept. */
    private fun stripRecordExtension(name: String): String {
        val lower = name.lowercase()
        return when {
            lower.endsWith(".markdown") -> name.dropLast(".markdown".length)
            lower.endsWith(".md") -> name.dropLast(".md".length)
            else -> name
        }
    }

    /**
     * Ids of existing records that likely duplicate [title]: same slug as the
     * file base name or the frontmatter title. Empty when there is no clash.
     */
    fun findPossibleDuplicates(title: String, snapshot: SecondBrainSnapshot): List<String> {
        val slug = slugify(stripRecordExtension(title.substringAfterLast('/')))
        if (slug.isEmpty()) return emptyList()
        return detailFiles(snapshot).mapNotNull { (file, dirKind) ->
            val id = relativeMemoryId(file, "$dirKind/${file.name}")
            val baseSlug = slugify(stripRecordExtension(id.substringAfterLast('/')))
            val titleSlug = frontmatterOf(file.content)?.get("title")?.let { slugify(it) }
            if (slug == baseSlug || slug == titleSlug) id else null
        }.distinct().sorted()
    }

    /** Max chars scanned per file for [[link]] parsing (preview cap stays smaller). */
    const val LINK_SCAN_CAP_CHARS = 200_000

    /** Brain shell radii in graph units. Layout content is confined inside these. */
    const val BRAIN_RX = 280f
    const val BRAIN_RY = 360f
    const val BRAIN_RZ = 520f

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
                ?.filter { it.isFile && it.name.substringAfterLast('.', "").lowercase() in setOf("md", "markdown") }
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

    internal data class CappedText(val text: String, val truncated: Boolean)

    /**
     * Bounded read for session startup: streams at most cap+1 chars so a huge
     * or malformed file can never stall a run or exhaust the heap.
     */
    internal fun readCapped(file: File, cap: Int): CappedText? {
        val text = readUpTo(file, cap) ?: return null
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        return if (text.length <= cap) {
            CappedText(trimmed, false)
        } else {
            CappedText(trimmed.take(cap), true)
        }
    }

    /**
     * Record schema for people/, areas/ and notes/ detail files (Phase 1).
     *
     * Every record carries YAML frontmatter between leading `---` lines:
     * required `title`, `area`, `type`, `updated` (ISO yyyy-MM-dd); optional
     * `tags` (slug list), `source` (where the fact came from), `source_file`
     * (raw file this was digested from), `stage`, `project`, `summary`.
     * SOUL.md, MEMORY.md, user.md and raw/ files are not records.
     */
    val RECORD_REQUIRED_KEYS = setOf("title", "area", "type", "updated")
    val RECORD_ALLOWED_KEYS = setOf(
        "title", "area", "type", "tags", "updated",
        "source", "source_file", "stage", "project", "summary",
        "next_action", "task_ref"
    )

    /** Record types. Hub files in areas/ use `area`. */
    val RECORD_TYPES = setOf("person", "note", "meeting", "decision", "reference", "area", "contact-card")

    /** Starter area vocabulary. Users may add areas; validation also accepts existing hubs. */
    val STARTER_AREAS = setOf("People", "Work", "Personal", "Health")

    private val slugRegex = Regex("^[a-z0-9]+(?:-[a-z0-9]+)*$")
    private val isoDateRegex = Regex("^\\d{4}-\\d{2}-\\d{2}$")
    private val datedNameRegex = Regex("^\\d{4}-\\d{2}-\\d{2}")

    data class RecordValidation(val valid: Boolean, val errors: List<String>)

    /**
     * Validates a detail record's frontmatter and body. Returns every problem
     * found so the agent can speak the fix back. Pure string logic, no I/O
     * except the area-vocabulary check against existing hub files.
     *
     * @param dirKind top-level memory dir (`areas`, `people`, `notes`) for
     * type-vs-directory checks; null skips them.
     */
    fun validateRecord(fileName: String, content: String, dirKind: String? = null): RecordValidation {
        val errors = mutableListOf<String>()
        val lines = content.lines()
        if (lines.isEmpty() || lines[0].trim() != "---") {
            return RecordValidation(
                false,
                listOf(
                    "$fileName: missing frontmatter. Start the file with a --- block " +
                        "carrying ${RECORD_REQUIRED_KEYS.sorted().joinToString(", ")}."
                )
            )
        }
        val end = lines.drop(1).indexOfFirst { it.trim() == "---" }
        if (end < 0) {
            return RecordValidation(false, listOf("$fileName: frontmatter never closed with ---."))
        }
        val values = mutableMapOf<String, String>()
        parseFrontmatterLines(fileName, lines.drop(1).take(end), values, errors)
        val base = fileName.substringAfterLast('/').substringBeforeLast('.')
        checkRecordFields(fileName, base, dirKind, values, errors)
        val body = lines.drop(1 + end + 1).joinToString("\n").trim()
        if (body.isEmpty()) errors.add("$fileName: empty body. Facts live in the body, not the map.")
        return RecordValidation(errors.isEmpty(), errors)
    }

    /** Parses `key: value` lines into [values], collecting syntax problems. */
    private fun parseFrontmatterLines(
        fileName: String,
        fmLines: List<String>,
        values: MutableMap<String, String>,
        errors: MutableList<String>
    ) {
        var inBlock = false
        fmLines.forEach { raw ->
            if (inBlock) {
                // Continuation of a `key: |` block scalar: indented or blank
                // lines belong to the value, not to new keys.
                if (raw.isBlank() || raw.startsWith(" ") || raw.startsWith("\t")) return@forEach
                inBlock = false
            }
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEach
            val key = line.substringBefore(':', "").trim()
            if (!line.contains(':') || key.isEmpty()) {
                errors.add("$fileName: malformed frontmatter line '$line' (expected key: value).")
                return@forEach
            }
            if (values.containsKey(key)) errors.add("$fileName: duplicate frontmatter key '$key'.")
            val value = unquote(line.substringAfter(':').trim())
            values[key] = value
            if (value == "|" || value == ">") inBlock = true
        }
    }

    /** Strips one layer of surrounding single or double quotes, so `title: ""` counts as blank. */
    private fun unquote(value: String): String {
        if (value.length < 2) return value
        val doubleQuoted = value.startsWith('"') && value.endsWith('"')
        val singleQuoted = value.startsWith('\'') && value.endsWith('\'')
        if (doubleQuoted || singleQuoted) return value.substring(1, value.length - 1)
        return value
    }

    /** Checks keys, types, areas, dates and tags against the record schema. */
    private fun checkRecordFields(
        fileName: String,
        fileBase: String,
        dirKind: String?,
        values: Map<String, String>,
        errors: MutableList<String>
    ) {
        values.keys.filter { it !in RECORD_ALLOWED_KEYS }.sorted().forEach { key ->
            errors.add(
                "$fileName: unknown frontmatter key '$key' " +
                    "(allowed: ${RECORD_ALLOWED_KEYS.sorted().joinToString(", ")})."
            )
        }
        RECORD_REQUIRED_KEYS.sorted().forEach { key ->
            if (values[key].isNullOrBlank()) errors.add("$fileName: missing required frontmatter key '$key'.")
        }
        values["type"]?.takeIf { it.isNotBlank() }?.let { type ->
            if (type !in RECORD_TYPES) {
                errors.add(
                    "$fileName: unknown type '$type' " +
                        "(allowed: ${RECORD_TYPES.sorted().joinToString(", ")})."
                )
            } else if (dirKind != null && !typeFitsDirectory(dirKind, type)) {
                errors.add(
                    "$fileName: type '$type' does not belong in $dirKind/ " +
                        "(areas/ holds type area, people/ holds person, notes/ holds notes)."
                )
            }
        }
        values["area"]?.takeIf { it.isNotBlank() }?.let { area ->
            // Bootstrap rule: writing areas/<name>.md with area <name> IS the
            // hub creation, so it passes before the hub exists.
            val bootstrapping = dirKind == "areas" && area.equals(fileBase, ignoreCase = true)
            if (!bootstrapping && !isKnownArea(area)) {
                errors.add(
                    "$fileName: unknown area '$area'. Use an existing area hub or " +
                        "create the area in MEMORY.md plus areas/ first."
                )
            }
        }
        values["updated"]?.takeIf { it.isNotBlank() }?.let { updated ->
            val valid = isoDateRegex.matches(updated) &&
                runCatching {
                    java.time.LocalDate.parse(updated)
                    true
                }.getOrDefault(false)
            if (!valid) {
                errors.add("$fileName: 'updated' must be an ISO date (yyyy-MM-dd), got '$updated'.")
            }
        }
        values["tags"]?.let { rawTags ->
            parseTags(rawTags).forEach { tag ->
                if (!slugRegex.matches(tag)) {
                    errors.add(
                        "$fileName: tag '$tag' breaks the slug rule " +
                            "(lowercase letters, digits, single hyphens only)."
                    )
                }
            }
        }
        listOf("source", "source_file", "next_action", "task_ref").forEach { key ->
            if (values.containsKey(key) && values[key].isNullOrBlank()) {
                errors.add("$fileName: '$key' is present but blank.")
            }
        }
    }

    /** Parses a `tags:` value: inline `[a, b]` list or a single slug. */
    fun parseTags(raw: String): List<String> {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return emptyList()
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            return splitInlineList(trimmed.removePrefix("[").removeSuffix("]"))
        }
        return listOf(unquote(trimmed)).filter { it.isNotEmpty() }
    }

    /** Splits an inline `[...]` list on commas outside quotes, so `["bad,tag"]` stays one value. */
    private fun splitInlineList(inner: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var quote: Char? = null
        fun flush() {
            val tag = unquote(cur.toString().trim())
            if (tag.isNotEmpty()) out.add(tag)
            cur.clear()
        }
        inner.forEach { ch ->
            when {
                quote != null && ch == quote -> {
                    cur.append(ch)
                    quote = null
                }
                quote == null && (ch == '"' || ch == '\'') -> {
                    cur.append(ch)
                    quote = ch
                }
                quote == null && ch == ',' -> flush()
                else -> cur.append(ch)
            }
        }
        flush()
        return out
    }

    /** True when [type] belongs in [dirKind]: hubs in areas/, cards in people/, notes in notes/. */
    private fun typeFitsDirectory(dirKind: String, type: String): Boolean = when (dirKind) {
        "areas" -> type == "area"
        "people" -> type == "person" || type == "contact-card"
        "notes" -> type == "note" || type == "meeting" || type == "decision" || type == "reference"
        else -> true
    }

    /** True when [area] matches the starter vocabulary or an existing hub file. */
    fun isKnownArea(area: String): Boolean {
        if (STARTER_AREAS.any { it.equals(area, ignoreCase = true) }) return true
        return runCatching {
            GotchaStorage.memoryAreasDir().listFiles()
                ?.filter { it.isFile }
                ?.map { it.nameWithoutExtension }
                ?.any { it.equals(area, ignoreCase = true) } ?: false
        }.getOrDefault(false)
    }

    /**
     * True when [file] is a people/areas/notes detail record: the only memory
     * files the schema (and the write gate) applies to.
     */
    fun isDetailRecord(file: File): Boolean = recordDirKind(file) != null

    /**
     * Top-level memory dir (`areas`, `people`, `notes`) for a detail record,
     * or null when [file] is not one. The write gates pass it to
     * [validateRecord] for type-vs-directory checks.
     */
    fun recordDirKind(file: File): String? {
        return runCatching {
            val root = GotchaStorage.memoryRoot().canonicalPath
            val path = file.canonicalPath
            if (!path.startsWith(root + File.separator)) return@runCatching null
            val rel = path.removePrefix(root + File.separator)
            val dir = rel.substringBefore('/', "")
            if (dir != "areas" && dir != "people" && dir != "notes") return@runCatching null
            val ext = file.name.substringAfterLast('.', "").lowercase()
            if (ext != "md" && ext != "markdown") return@runCatching null
            dir
        }.getOrNull()
    }

    /**
     * Digest helper: raw files older than [maxAgeDays] that the agent should
     * propose for cleanup (digested-and-linked raws are deleted, kept raws
     * are noted with their reason). Never deletes; only lists.
     */
    fun listStaleRaw(maxAgeDays: Int = 30, nowMillis: Long = System.currentTimeMillis()): List<File> {
        return try {
            val dir = GotchaStorage.memoryRawDir()
            if (!dir.exists() || !dir.isDirectory) return emptyList()
            val cutoff = nowMillis - maxAgeDays * 24L * 60 * 60 * 1000
            dir.listFiles()?.filter { it.isFile && it.lastModified() < cutoff }
                ?.sortedBy { it.name.lowercase() }.orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Digest helper: copy-vs-link gate. Files over [RAW_LINK_THRESHOLD_BYTES]
     * are linked in place, never duplicated — except under ephemeral paths,
     * which must always be copied because the original can vanish.
     */
    const val RAW_LINK_THRESHOLD_BYTES = 10L * 1024 * 1024

    /** True for staging paths that can vanish: temp dirs, caches, `.tmp` files. */
    fun isEphemeralPath(path: String): Boolean {
        val lower = path.lowercase().replace('\\', '/')
        return lower.startsWith("/tmp/") || lower == "/tmp" ||
            lower.contains("/tmp/") || lower.contains("/cache/") ||
            lower.contains("/temp/") || lower.endsWith(".tmp") || lower.endsWith(".temp")
    }

    /**
     * History rule helper: dated notes (names starting yyyy-MM-dd) describe
     * what was true THEN and stay append-only. New decisions go in new notes
     * with `Superseded by [[path]]` links both ways, never rewrites.
     */
    fun isDatedNote(fileName: String): Boolean {
        val prefix = datedNameRegex.find(fileName)?.value ?: return false
        return runCatching {
            java.time.LocalDate.parse(prefix)
            true
        }.getOrDefault(false)
    }

    /**
     * Timezone rule: the device zone is truth. Formats [nowMillis] as an ISO
     * date in the device zone with DST-safe conversion (never a hardcoded
     * offset). Notes carry absolute dates, never relative ones.
     */
    fun deviceTimezoneId(): String =
        runCatching { java.time.ZoneId.systemDefault().id }.getOrNull() ?: "UTC"

    fun formatDateForMemory(nowMillis: Long = System.currentTimeMillis()): String {
        // LocalDate.ofInstant needs API 34; atZone + toLocalDate works from 26.
        val instant = java.time.Instant.ofEpochMilli(nowMillis)
        return runCatching {
            instant.atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString()
        }.getOrDefault(
            instant.atZone(java.time.ZoneOffset.UTC).toLocalDate().toString()
        )
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
        val today = formatDateForMemory()
        fun hub(title: String, area: String, body: String): String = buildString {
            appendLine("---")
            appendLine("title: $title")
            appendLine("area: $area")
            appendLine("type: area")
            appendLine("tags: []")
            appendLine("updated: $today")
            appendLine("---")
            appendLine()
            append(body)
        }
        val areas = mapOf(
            "people.md" to hub(
                "People", "People",
                "# People\n\nContact cards live in $root/people/. Open with absolute paths.\n"
            ),
            "work.md" to hub(
                "Work", "Work",
                "# Work\n\nProjects and work threads. Detail notes live in $root/notes/. Open with absolute paths.\n"
            ),
            "personal.md" to hub(
                "Personal", "Personal",
                "# Personal\n\nHome, routine, finance admin. Detail notes live in $root/notes/. Open with absolute paths.\n"
            ),
            "health.md" to hub(
                "Health", "Health",
                "# Health\n\nHealth context the user chooses to keep. Never store diagnoses as facts.\n"
            )
        )
        areas.forEach { (name, body) ->
            val file = File(GotchaStorage.memoryAreasDir(), name)
            if (!file.exists()) {
                runCatching { file.writeText(body) }
            }
        }
    }

    /**
     * One-time migration for pre-schema installs: hub files written before the
     * record schema gain frontmatter in place, so they stay editable through
     * the write gate. Bodies are preserved untouched; only already-valid files
     * and non-markdown files are skipped.
     */
    private fun migrateLegacyHubs() {
        val dir = GotchaStorage.memoryAreasDir()
        if (!dir.exists() || !dir.isDirectory) return
        val today = formatDateForMemory()
        dir.listFiles()
            ?.filter { it.isFile && it.name.substringAfterLast('.', "").lowercase() == "md" }
            .orEmpty()
            .forEach { hub ->
                runCatching {
                    val text = hub.readText()
                    if (text.lines().firstOrNull()?.trim() == "---") return@runCatching
                    val base = hub.nameWithoutExtension
                    val area = STARTER_AREAS.firstOrNull { it.equals(base, ignoreCase = true) }
                        ?: base.replaceFirstChar { it.uppercase() }
                    val header = listOf(
                        "---",
                        "title: $base",
                        "area: $area",
                        "type: area",
                        "tags: []",
                        "updated: $today",
                        "---",
                        ""
                    ).joinToString("\n")
                    hub.writeText(header + text)
                }
            }
    }
}
