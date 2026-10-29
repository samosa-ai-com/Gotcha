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
}
