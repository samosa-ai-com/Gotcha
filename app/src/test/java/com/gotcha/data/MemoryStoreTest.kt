package com.gotcha.data

import com.gotcha.llm.ChatMessage
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

@Suppress("LargeClass")
class MemoryStoreTest {

    private lateinit var tmpRoot: File
    private var savedOverride: String? = null

    @Before
    fun setUp() {
        tmpRoot = Files.createTempDirectory("gotcha-memory-test").toFile()
        savedOverride = try {
            GotchaStorage.rootPath
        } catch (_: Exception) {
            null
        }
        GotchaStorage.rootPath = File(tmpRoot, "Gotcha").absolutePath
    }

    @After
    fun tearDown() {
        tmpRoot.deleteRecursively()
        if (savedOverride != null) {
            GotchaStorage.rootPath = savedOverride!!
        } else {
            GotchaStorage.resetRootPathForTests()
        }
    }

    @Test
    fun `ensureDefaults creates memory tree and seeds files`() {
        MemoryStore.ensureDefaults(Settings(userName = "Ada"))
        assertTrue(GotchaStorage.memoryRoot().isDirectory)
        assertTrue(GotchaStorage.soulFile().exists())
        assertTrue(GotchaStorage.memoryIndexFile().exists())
        assertTrue(GotchaStorage.memoryUserFile().readText().contains("Ada"))
        assertTrue(File(GotchaStorage.memoryAreasDir(), "work.md").exists())
    }

    @Test
    fun `startup message contains capped soul and memory index`() {
        MemoryStore.ensureDefaults(Settings())
        GotchaStorage.soulFile().writeText("S".repeat(2000))
        val msg = MemoryStore.buildStartupMessage()
        assertNotNull(msg)
        val text = msg!!.textContent
        assertTrue(text.contains(MemoryStore.STARTUP_MARKER))
        assertTrue(text.contains("truncated"))
        assertTrue(MemoryStore.isStartupMessage(msg))
    }

    @Test
    fun `startup message null when files missing`() {
        assertNull(MemoryStore.buildStartupMessage())
    }

    @Test
    fun `non startup message is not detected`() {
        val other = ChatMessage(role = "system", content = JsonPrimitive("hello"))
        assertFalse(MemoryStore.isStartupMessage(other))
    }

    @Test
    fun `pointer block is stable and cheap`() {
        val first = MemoryStore.buildMemoryPointerBlock()
        val second = MemoryStore.buildMemoryPointerBlock()
        assertTrue(first == second)
        assertTrue(first.contains("<memory>"))
        assertTrue(first.length < 800)
    }

    @Test
    fun `syncUserFile reflects profile changes`() {
        MemoryStore.ensureDefaults(Settings(userName = "Ada"))
        MemoryStore.syncUserFile(Settings(userName = "Grace", userOccupation = "Engineer"))
        val text = GotchaStorage.memoryUserFile().readText()
        assertTrue(text.contains("Grace"))
        assertFalse(text.contains("Ada"))
    }

    @Test
    fun `user file includes reply style`() {
        MemoryStore.syncUserFile(Settings(userResponseStyle = "No bullet lists."))
        assertTrue(GotchaStorage.memoryUserFile().readText().contains("No bullet lists."))
    }

    @Test
    fun `startup message uses absolute paths`() {
        MemoryStore.ensureDefaults(Settings())
        val text = MemoryStore.buildStartupMessage()!!.textContent
        assertTrue(text.contains(GotchaStorage.memoryRoot().absolutePath))
        assertFalse(text.contains("Paths:\n  Gotcha/memory/"))
    }

    @Test
    fun `pointer block is conditional on availability`() {
        val pointer = MemoryStore.buildMemoryPointerBlock()
        assertTrue(pointer.contains("if that message is absent"))
        assertTrue(pointer.contains("ABSOLUTE paths"))
    }

    @Test
    fun `snapshot loads seeded files`() {
        MemoryStore.ensureDefaults(Settings(userName = "Ada"))
        val snap = MemoryStore.loadSnapshot()
        assertTrue(snap.available)
        assertNotNull(snap.soul)
        assertNotNull(snap.memoryIndex)
        assertTrue(snap.userCard!!.content.contains("Ada"))
        assertTrue(snap.areas.map { it.name }.contains("work.md"))
    }

    @Test
    fun `snapshot unavailable when files missing`() {
        val snap = MemoryStore.loadSnapshot()
        assertFalse(snap.available)
        assertNull(snap.soul)
        assertTrue(snap.areas.isEmpty())
    }

    @Test
    fun `wiki links parse with labels and dedupe`() {
        val links = MemoryStore.parseWikiLinks(
            "See [[notes/trip.md]] and [[people/asha.md| Asha ]] and [[notes/trip.md]]."
        )
        assertEquals(listOf("notes/trip.md", "people/asha.md"), links)
    }

    @Test
    fun `graph resolves links and skips dangling ones`() {
        MemoryStore.ensureDefaults(Settings())
        GotchaStorage.memoryNotesDir().mkdirs()
        File(GotchaStorage.memoryNotesDir(), "trip.md").writeText("Trip. See [[work]] and [[nowhere]].")
        val snap = MemoryStore.loadSnapshot()
        val graph = MemoryStore.buildGraph(snap)
        assertTrue(graph.nodes.map { it.id }.contains("notes/trip.md"))
        val edge = graph.edges.singleOrNull { it.fromId == "notes/trip.md" }
        assertNotNull(edge)
        assertEquals("areas/work.md", edge!!.toId)
    }

    @Test
    fun `bare links prefer the source directory`() {
        MemoryStore.ensureDefaults(Settings())
        GotchaStorage.memoryNotesDir().mkdirs()
        GotchaStorage.memoryPeopleDir().mkdirs()
        File(GotchaStorage.memoryNotesDir(), "plan.md").writeText("Note plan.")
        File(GotchaStorage.memoryPeopleDir(), "plan.md").writeText("Person plan.")
        File(GotchaStorage.memoryNotesDir(), "trip.md").writeText("Trip. See [[plan]].")
        val snap = MemoryStore.loadSnapshot()
        val graph = MemoryStore.buildGraph(snap)
        val edge = graph.edges.singleOrNull { it.fromId == "notes/trip.md" }
        assertNotNull(edge)
        assertEquals("notes/plan.md", edge!!.toId)
    }

    @Test
    fun `ambiguous bare names draw no edge`() {
        MemoryStore.ensureDefaults(Settings())
        GotchaStorage.memoryNotesDir().mkdirs()
        GotchaStorage.memoryPeopleDir().mkdirs()
        File(GotchaStorage.memoryNotesDir(), "plan.md").writeText("Note plan.")
        File(GotchaStorage.memoryPeopleDir(), "plan.md").writeText("Person plan.")
        File(GotchaStorage.memoryAreasDir(), "hub.md").writeText("Hub. See [[plan]].")
        val snap = MemoryStore.loadSnapshot()
        val graph = MemoryStore.buildGraph(snap)
        assertTrue(graph.edges.none { it.fromId == "areas/hub.md" })
    }

    @Test
    fun `startup read stays bounded on huge files`() {
        MemoryStore.ensureDefaults(Settings())
        val big = "y".repeat(2_000_000)
        GotchaStorage.soulFile().writeText(big)
        val capped = MemoryStore.readCapped(GotchaStorage.soulFile(), MemoryStore.SOUL_CAP_CHARS)
        assertNotNull(capped)
        assertTrue(capped!!.truncated)
        assertTrue(capped.text.length <= MemoryStore.SOUL_CAP_CHARS)
    }

    @Test
    fun `graph keeps same-named files apart`() {
        MemoryStore.ensureDefaults(Settings())
        GotchaStorage.memoryNotesDir().mkdirs()
        GotchaStorage.memoryPeopleDir().mkdirs()
        File(GotchaStorage.memoryNotesDir(), "plan.md").writeText("Plan. See [[people/plan]].")
        File(GotchaStorage.memoryPeopleDir(), "plan.md").writeText("Person.")
        val snap = MemoryStore.loadSnapshot()
        val graph = MemoryStore.buildGraph(snap)
        assertTrue(graph.nodes.map { it.id }.contains("notes/plan.md"))
        assertTrue(graph.nodes.map { it.id }.contains("people/plan.md"))
        val edge = graph.edges.singleOrNull { it.fromId == "notes/plan.md" }
        assertNotNull(edge)
        assertEquals("people/plan.md", edge!!.toId)
    }

    @Test
    fun `graph sees links past the preview cap`() {
        MemoryStore.ensureDefaults(Settings())
        GotchaStorage.memoryNotesDir().mkdirs()
        val filler = "x".repeat(MemoryStore.VIEWER_FILE_CAP_CHARS + 100)
        File(GotchaStorage.memoryNotesDir(), "long.md").writeText("$filler [[work]]")
        val snap = MemoryStore.loadSnapshot()
        assertTrue(snap.notes.single { it.name == "long.md" }.truncated)
        val graph = MemoryStore.buildGraph(snap)
        assertTrue(graph.edges.any { it.fromId == "notes/long.md" && it.toId == "areas/work.md" })
    }

    @Test
    fun `markdown extension renders as markdown and links`() {
        MemoryStore.ensureDefaults(Settings())
        val rawDir = GotchaStorage.memoryRawDir()
        rawDir.mkdirs()
        File(rawDir, "ref.markdown").writeText("Ref. See [[work]].")
        val snap = MemoryStore.loadSnapshot()
        val ref = snap.raw.single { it.name == "ref.markdown" }
        assertTrue(ref.isMarkdown)
        val graph = MemoryStore.buildGraph(snap)
        assertTrue(graph.edges.any { it.fromId == "raw/ref.markdown" && it.toId == "areas/work.md" })
    }

    @Test
    fun `raw files list any extension without content`() {
        MemoryStore.ensureDefaults(Settings())
        val rawDir = GotchaStorage.memoryRawDir()
        rawDir.mkdirs()
        File(rawDir, "memo.mp3").writeBytes(byteArrayOf(1, 2, 3, 4))
        File(rawDir, "note.txt").writeText("hello raw")
        val snap = MemoryStore.loadSnapshot()
        val mp3 = snap.raw.single { it.name == "memo.mp3" }
        assertEquals("", mp3.content)
        assertEquals(4L, mp3.sizeBytes)
        assertTrue(snap.raw.single { it.name == "note.txt" }.content.contains("hello"))
    }

    @Test
    fun `force layout is deterministic and bounded`() {
        val edges = listOf(0 to 1, 1 to 2, 2 to 0, 2 to 3)
        val first = MemoryStore.forceLayout(4, edges)
        val second = MemoryStore.forceLayout(4, edges)
        assertEquals(4, first.size)
        assertEquals(first, second)
        first.forEach { p ->
            assertTrue(p.x in -500f..500f)
            assertTrue(p.y in -350f..350f)
        }
    }

    @Test
    fun `force layout pulls linked nodes closer than unlinked ones`() {
        val chain = MemoryStore.forceLayout(5, listOf(0 to 1, 1 to 2, 2 to 3, 3 to 4), iterations = 300)
        fun dist(a: Int, b: Int): Float = (chain[a] - chain[b]).getDistance()
        val linked = listOf(dist(0, 1), dist(1, 2), dist(2, 3), dist(3, 4)).average()
        val unlinked = listOf(dist(0, 2), dist(0, 3), dist(0, 4), dist(1, 3), dist(1, 4), dist(2, 4)).average()
        assertTrue("linked=$linked unlinked=$unlinked", linked < unlinked)
    }

    @Test
    fun `force layout handles empty and single node`() {
        assertTrue(MemoryStore.forceLayout(0, emptyList()).isEmpty())
        assertEquals(1, MemoryStore.forceLayout(1, emptyList()).size)
    }

    @Test
    fun `confinement keeps every point inside the brain`() {
        val points = listOf(
            androidx.compose.ui.geometry.Offset(900f, 700f),
            androidx.compose.ui.geometry.Offset(-50f, 20f),
            androidx.compose.ui.geometry.Offset(0f, 0f)
        )
        val depths = listOf(500f, 10f, 0f)
        val (keptPoints, keptDepths) = MemoryStore.confineToBrain(points, depths)
        assertEquals(3, keptPoints.size)
        keptPoints.zip(keptDepths).forEach { (p, z) ->
            val e = (p.x / MemoryStore.BRAIN_RX).let { it * it } +
                (p.y / MemoryStore.BRAIN_RY).let { it * it } +
                (z / MemoryStore.BRAIN_RZ).let { it * it }
            assertTrue("e=$e", e <= 0.81f + 1e-3f)
        }
        // Inside points pass through untouched.
        assertEquals(points[1], keptPoints[1])
        assertEquals(points[2], keptPoints[2])
    }

    // ---- Second Brain Phase 1: record schema, digest, history, timezone ----

    private fun goodRecord(): String = listOf(
        "---",
        "title: Trip Plan",
        "area: Personal",
        "type: note",
        "tags: [travel, japan-2026]",
        "updated: 2026-10-29",
        "source: user chat",
        "source_file: raw/memo.mp3",
        "---",
        "",
        "# Trip Plan",
        "",
        "See [[areas/personal.md]].",
        ""
    ).joinToString("\n")

    @Test
    fun `seeded area hubs carry valid frontmatter`() {
        MemoryStore.ensureDefaults(Settings())
        GotchaStorage.memoryAreasDir().listFiles()!!.forEach { hub ->
            val check = MemoryStore.validateRecord(hub.name, hub.readText())
            assertTrue("${hub.name}: ${check.errors}", check.valid)
        }
    }

    @Test
    fun `validateRecord accepts a complete record`() {
        MemoryStore.ensureDefaults(Settings())
        val check = MemoryStore.validateRecord("trip.md", goodRecord())
        assertTrue(check.errors.toString(), check.valid)
    }

    @Test
    fun `validateRecord rejects missing frontmatter`() {
        MemoryStore.ensureDefaults(Settings())
        val check = MemoryStore.validateRecord("trip.md", "# No frontmatter\n\nBody.\n")
        assertFalse(check.valid)
        assertTrue(check.errors.any { it.contains("frontmatter") })
    }

    @Test
    fun `validateRecord rejects bad fields`() {
        MemoryStore.ensureDefaults(Settings())
        val bad = listOf(
            "---",
            "title: ",
            "area: Atlantis",
            "type: nonsense",
            "tags: [Bad Tag, ok]",
            "updated: 29-10-2026",
            "mystery: 1",
            "---",
            "",
            "Body.",
            ""
        ).joinToString("\n")
        val check = MemoryStore.validateRecord("bad.md", bad)
        assertFalse(check.valid)
        val joined = check.errors.joinToString("\n")
        assertTrue(joined, joined.contains("'title'"))
        assertTrue(joined, joined.contains("Atlantis"))
        assertTrue(joined, joined.contains("nonsense"))
        assertTrue(joined, joined.contains("Bad Tag"))
        assertTrue(joined, joined.contains("updated"))
        assertTrue(joined, joined.contains("mystery"))
    }

    @Test
    fun `validateRecord rejects empty bodies`() {
        MemoryStore.ensureDefaults(Settings())
        val check = MemoryStore.validateRecord(
            "empty.md",
            "---\ntitle: T\narea: Work\ntype: note\nupdated: 2026-10-29\n---\n"
        )
        assertFalse(check.valid)
        assertTrue(check.errors.any { it.contains("empty body") })
    }

    @Test
    fun `parseTags handles inline lists and single slugs`() {
        assertEquals(listOf("travel", "japan-2026"), MemoryStore.parseTags("[travel, japan-2026]"))
        assertEquals(emptyList<String>(), MemoryStore.parseTags("[]"))
        assertEquals(listOf("solo"), MemoryStore.parseTags("solo"))
    }

    @Test
    fun `isKnownArea accepts starter and custom hubs`() {
        MemoryStore.ensureDefaults(Settings())
        assertTrue(MemoryStore.isKnownArea("Work"))
        assertTrue(MemoryStore.isKnownArea("work"))
        File(GotchaStorage.memoryAreasDir(), "sidequest.md").writeText("# Sidequest\n")
        assertTrue(MemoryStore.isKnownArea("sidequest"))
        assertFalse(MemoryStore.isKnownArea("Atlantis"))
    }

    @Test
    fun `isDetailRecord gates only detail markdown`() {
        MemoryStore.ensureDefaults(Settings())
        assertTrue(MemoryStore.isDetailRecord(File(GotchaStorage.memoryNotesDir(), "trip.md")))
        assertTrue(MemoryStore.isDetailRecord(File(GotchaStorage.memoryPeopleDir(), "asha.markdown")))
        assertFalse(MemoryStore.isDetailRecord(GotchaStorage.soulFile()))
        assertFalse(MemoryStore.isDetailRecord(GotchaStorage.memoryIndexFile()))
        assertFalse(MemoryStore.isDetailRecord(GotchaStorage.memoryUserFile()))
        assertFalse(MemoryStore.isDetailRecord(File(GotchaStorage.memoryRawDir(), "memo.mp3")))
        assertFalse(MemoryStore.isDetailRecord(File(GotchaStorage.memoryNotesDir(), "clip.mp3")))
    }

    @Test
    fun `listStaleRaw flags files older than 30 days`() {
        MemoryStore.ensureDefaults(Settings())
        val rawDir = GotchaStorage.memoryRawDir().apply { mkdirs() }
        val old = File(rawDir, "old-memo.mp3").apply {
            writeBytes(byteArrayOf(1, 2, 3))
            setLastModified(System.currentTimeMillis() - 40L * 24 * 60 * 60 * 1000)
        }
        val fresh = File(rawDir, "fresh.txt").apply { writeText("new") }
        val stale = MemoryStore.listStaleRaw()
        assertTrue(stale.map { it.name }.contains(old.name))
        assertFalse(stale.map { it.name }.contains(fresh.name))
    }

    @Test
    fun `isEphemeralPath spots staging paths`() {
        assertTrue(MemoryStore.isEphemeralPath("/tmp/voice.mp3"))
        assertTrue(MemoryStore.isEphemeralPath("/data/cache/memo.pdf"))
        assertTrue(MemoryStore.isEphemeralPath("/sdcard/Temp/clip.mp4"))
        assertTrue(MemoryStore.isEphemeralPath("/sdcard/Download/draft.tmp"))
        assertFalse(MemoryStore.isEphemeralPath("/sdcard/Documents/paper.pdf"))
    }

    @Test
    fun `isDatedNote detects dated names`() {
        assertTrue(MemoryStore.isDatedNote("2026-10-29-sync.md"))
        assertFalse(MemoryStore.isDatedNote("trip.md"))
        assertFalse(MemoryStore.isDatedNote("2026-13-99-bogus.md"))
    }

    @Test
    fun `memory dates use the device timezone`() {
        assertTrue(MemoryStore.deviceTimezoneId().isNotBlank())
        val expected = java.time.LocalDate.ofInstant(
            java.time.Instant.ofEpochMilli(0),
            java.time.ZoneId.systemDefault()
        ).toString()
        assertEquals(expected, MemoryStore.formatDateForMemory(0))
        assertTrue(MemoryStore.formatDateForMemory().matches(Regex("\\d{4}-\\d{2}-\\d{2}")))
    }

    // ---- Codex review follow-ups ----

    private fun record(type: String, area: String, tags: String = "[x]"): String = listOf(
        "---",
        "title: T",
        "area: $area",
        "type: $type",
        "tags: $tags",
        "updated: 2026-10-29",
        "---",
        "",
        "Body.",
        ""
    ).joinToString("\n")

    @Test
    fun `validateRecord treats quoted empty values as blank`() {
        MemoryStore.ensureDefaults(Settings())
        val check = MemoryStore.validateRecord(
            "q.md",
            "---\ntitle: \"\"\narea: Work\ntype: note\nupdated: 2026-10-29\n---\n\nBody.\n"
        )
        assertFalse(check.valid)
        assertTrue(check.errors.any { it.contains("'title'") })
    }

    @Test
    fun `validateRecord rejects commas inside quoted tags`() {
        MemoryStore.ensureDefaults(Settings())
        val check = MemoryStore.validateRecord("t.md", record("note", "Work", "[\"bad,tag\"]"))
        assertFalse(check.valid)
        assertTrue(check.errors.any { it.contains("bad,tag") })
    }

    @Test
    fun `validateRecord accepts block scalars`() {
        MemoryStore.ensureDefaults(Settings())
        val body = listOf(
            "---",
            "title: T",
            "area: Work",
            "type: note",
            "updated: 2026-10-29",
            "summary: |",
            "  first line without a colon",
            "  second: still part of the value",
            "---",
            "",
            "Body.",
            ""
        ).joinToString("\n")
        val check = MemoryStore.validateRecord("b.md", body)
        assertTrue(check.errors.toString(), check.valid)
    }

    @Test
    fun `validateRecord enforces type per directory`() {
        MemoryStore.ensureDefaults(Settings())
        val misplaced = MemoryStore.validateRecord("work.md", record("note", "Work"), "areas")
        assertFalse(misplaced.valid)
        assertTrue(misplaced.errors.any { it.contains("areas/") })
        assertTrue(MemoryStore.validateRecord("work.md", record("area", "Work"), "areas").valid)
        assertTrue(MemoryStore.validateRecord("asha.md", record("person", "People"), "people").valid)
        assertTrue(MemoryStore.validateRecord("sync.md", record("decision", "Work"), "notes").valid)
        // Without directory context the type check stays lenient.
        assertTrue(MemoryStore.validateRecord("work.md", record("note", "Work")).valid)
    }

    @Test
    fun `area bootstrap accepts the new hub name`() {
        MemoryStore.ensureDefaults(Settings())
        assertFalse(MemoryStore.isKnownArea("Research"))
        val check = MemoryStore.validateRecord("Research.md", record("area", "Research"), "areas")
        assertTrue(check.errors.toString(), check.valid)
    }

    @Test
    fun `ensureDefaults migrates legacy hubs`() {
        MemoryStore.ensureDefaults(Settings())
        val hub = File(GotchaStorage.memoryAreasDir(), "work.md")
        hub.writeText("# Work\n\nLegacy plain markdown.\n")
        MemoryStore.ensureDefaults(Settings())
        val text = hub.readText()
        assertTrue(text.lines().first() == "---")
        assertTrue(text.contains("Legacy plain markdown."))
        val check = MemoryStore.validateRecord(hub.name, text, "areas")
        assertTrue(check.errors.toString(), check.valid)
    }

    // ---- Phase 2: task links, duplicates, health ----

    @Test
    fun `validateRecord accepts task link fields`() {
        MemoryStore.ensureDefaults(Settings())
        val rec = listOf(
            "---",
            "title: Trip",
            "area: Personal",
            "type: note",
            "tags: [travel]",
            "updated: 2026-10-29",
            "next_action: Book flights",
            "task_ref: 123e4567-e89b-12d3-a456-426614174000",
            "---",
            "",
            "Body.",
            ""
        ).joinToString("\n")
        val check = MemoryStore.validateRecord("trip.md", rec, "notes")
        assertTrue(check.errors.toString(), check.valid)
    }

    @Test
    fun `validateRecord rejects blank task link fields`() {
        MemoryStore.ensureDefaults(Settings())
        val rec = record("note", "Personal").replace("tags: [x]", "tags: [x]\ntask_ref: ")
        val check = MemoryStore.validateRecord("trip.md", rec, "notes")
        assertFalse(check.valid)
        assertTrue(check.errors.any { it.contains("task_ref") })
    }

    @Test
    fun `checkHealth reports orphans`() {
        MemoryStore.ensureDefaults(Settings())
        val notes = GotchaStorage.memoryNotesDir().apply { mkdirs() }
        fun note(name: String, body: String, extra: String = "") {
            File(notes, name).writeText(
                listOf(
                    "---",
                    "title: $name",
                    "area: Personal",
                    "type: note",
                    "updated: 2026-10-29",
                    extra,
                    "---",
                    "",
                    body,
                    ""
                ).filter { it.isNotEmpty() }.joinToString("\n")
            )
        }
        note("a.md", "See [[notes/b.md]].")
        note("b.md", "See [[notes/a.md]].")
        note("c.md", "Lonely.")
        note("d.md", "See [[nowhere-at-all]].")
        File(notes, "e.md").writeText("No frontmatter.\n")
        note("f.md", "Waiting on a task.", "task_ref: dead-beef")
        val snap = MemoryStore.loadSnapshot()

        val health = MemoryStore.checkHealth(snap)
        assertTrue(health.unlinked.contains("notes/c.md"))
        assertTrue(health.unlinked.contains("notes/d.md"))
        assertTrue(health.unlinked.contains("notes/e.md"))
        assertTrue(health.unlinked.contains("notes/f.md"))
        assertFalse(health.unlinked.contains("notes/a.md"))
        assertFalse(health.unlinked.contains("notes/b.md"))
        assertFalse(health.unlinked.contains("SOUL.md"))
        assertFalse(health.unlinked.contains("MEMORY.md"))
        assertFalse(health.unlinked.contains("user.md"))
        assertEquals(
            listOf(MemoryStore.DanglingLink("notes/d.md", "nowhere-at-all")),
            health.dangling
        )
        assertEquals(listOf("notes/e.md"), health.invalid.map { it.fileId })
        assertEquals(
            listOf(MemoryStore.TaskNoteRef("notes/f.md", "dead-beef")),
            health.unknownTasks
        )
        assertFalse(health.isClean)

        val linked = MemoryStore.checkHealth(
            snap,
            listOf(MemoryStore.TaskLink("dead-beef", "Task", File(notes, "f.md").absolutePath))
        )
        assertTrue(linked.unknownTasks.isEmpty())
        assertTrue(linked.mismatchedTasks.isEmpty())
        assertTrue(linked.staleTaskLinks.isEmpty())
    }

    @Test
    fun `checkHealth flags mismatched and stale task links`() {
        MemoryStore.ensureDefaults(Settings())
        val notes = GotchaStorage.memoryNotesDir().apply { mkdirs() }
        File(notes, "g.md").writeText(
            listOf(
                "---",
                "title: G",
                "area: Personal",
                "type: note",
                "updated: 2026-10-29",
                "task_ref: real-id",
                "---",
                "",
                "Body.",
                ""
            ).joinToString("\n")
        )
        File(notes, "h.md").writeText(
            listOf(
                "---",
                "title: H",
                "area: Personal",
                "type: note",
                "updated: 2026-10-29",
                "task_ref: lonely-id",
                "---",
                "",
                "Body.",
                ""
            ).joinToString("\n")
        )
        val snap = MemoryStore.loadSnapshot()
        val health = MemoryStore.checkHealth(
            snap,
            listOf(
                MemoryStore.TaskLink("real-id", "Task", "/other/note.md"),
                MemoryStore.TaskLink("lonely-id", "Lonely", null)
            )
        )
        assertTrue(health.unknownTasks.isEmpty())
        assertTrue(
            health.mismatchedTasks.contains(
                MemoryStore.TaskMismatch("notes/g.md", "real-id", "/other/note.md")
            )
        )
        assertTrue(
            health.mismatchedTasks.contains(
                MemoryStore.TaskMismatch("notes/h.md", "lonely-id", null)
            )
        )
        assertEquals(
            listOf(MemoryStore.StaleTaskLink("Task", "/other/note.md")),
            health.staleTaskLinks
        )
        assertFalse(health.isClean)
    }

    @Test
    fun `checkHealth flags files past the link scan cap`() {
        MemoryStore.ensureDefaults(Settings())
        val notes = GotchaStorage.memoryNotesDir().apply { mkdirs() }
        val filler = "x".repeat(MemoryStore.LINK_SCAN_CAP_CHARS)
        File(notes, "big.md").writeText(
            record("note", "Personal").replace("Body.", "See [[areas/work.md]].\n\n$filler")
        )
        val snap = MemoryStore.loadSnapshot()
        val health = MemoryStore.checkHealth(snap)
        assertTrue(health.unscanned.contains("notes/big.md"))
        // The link at the start still resolves, so no false unlinked/dangling.
        assertFalse(health.unlinked.contains("notes/big.md"))
        assertTrue(health.dangling.none { it.fromId == "notes/big.md" })
    }

    @Test
    fun `ambiguous bare names are not dangling`() {
        MemoryStore.ensureDefaults(Settings())
        val notes = GotchaStorage.memoryNotesDir().apply { mkdirs() }
        val people = GotchaStorage.memoryPeopleDir().apply { mkdirs() }
        File(notes, "plan.md").writeText(record("note", "Personal"))
        File(people, "plan.md").writeText(record("person", "People"))
        File(GotchaStorage.memoryAreasDir(), "hub.md").writeText(
            record("area", "Work").replace("Body.", "See [[plan]].")
        )
        val snap = MemoryStore.loadSnapshot()
        assertTrue(MemoryStore.findDanglingLinks(snap).none { it.target == "plan" })
    }

    @Test
    fun `wiki links ignore fenced code blocks`() {
        assertEquals(
            listOf("notes/real.md"),
            MemoryStore.parseWikiLinks("```\nSee [[notes/fake.md]].\n```\nSee [[notes/real.md]].")
        )
        assertEquals(
            listOf("notes/real.md"),
            MemoryStore.parseWikiLinks("~~~\nSee [[notes/fake.md]].\n~~~\nSee [[notes/real.md]].")
        )
        assertEquals(
            listOf("notes/real.md"),
            MemoryStore.parseWikiLinks("See [[notes/real.md]].\n```\nUnclosed [[notes/fake.md]].")
        )
    }

    @Test
    fun `detail listing includes markdown files`() {
        MemoryStore.ensureDefaults(Settings())
        File(GotchaStorage.memoryAreasDir(), "extra.markdown").writeText(
            record("area", "Work").replace("title: T", "title: Extra")
        )
        val snap = MemoryStore.loadSnapshot()
        assertTrue(snap.areas.map { it.name }.contains("extra.markdown"))
    }

    @Test
    fun `findPossibleDuplicates keeps dots in titles`() {
        MemoryStore.ensureDefaults(Settings())
        val notes = GotchaStorage.memoryNotesDir().apply { mkdirs() }
        File(notes, "release-2-0.md").writeText(record("note", "Work"))
        File(notes, "release-2.md").writeText(record("note", "Work"))
        val snap = MemoryStore.loadSnapshot()
        assertEquals(
            listOf("notes/release-2-0.md"),
            MemoryStore.findPossibleDuplicates("Release 2.0", snap)
        )
    }

    @Test
    fun `slugify normalizes names`() {
        assertEquals("asha-sharma", MemoryStore.slugify("Asha Sharma"))
        assertEquals("k99", MemoryStore.slugify("K99"))
        assertEquals("3d-kinematics", MemoryStore.slugify("3D Kinematics!"))
        assertEquals("", MemoryStore.slugify("---"))
    }

    @Test
    fun `findPossibleDuplicates catches renames`() {
        MemoryStore.ensureDefaults(Settings())
        val people = GotchaStorage.memoryPeopleDir().apply { mkdirs() }
        File(people, "asha-sharma.md").writeText(
            record("person", "People").replace("title: T", "title: Asha Sharma")
        )
        val snap = MemoryStore.loadSnapshot()
        assertTrue(
            MemoryStore.findPossibleDuplicates("Asha Sharma", snap).contains("people/asha-sharma.md")
        )
        assertTrue(
            MemoryStore.findPossibleDuplicates("asha-sharma", snap).contains("people/asha-sharma.md")
        )
        assertTrue(MemoryStore.findPossibleDuplicates("Nobody Here", snap).isEmpty())
    }
}
