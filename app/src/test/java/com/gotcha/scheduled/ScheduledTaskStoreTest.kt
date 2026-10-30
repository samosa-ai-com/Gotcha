package com.gotcha.scheduled

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.gotcha.agent.Attachment
import com.gotcha.agent.ComposerAttachment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** [ScheduledTaskStore]: the tasks and the files kept with them (issue #132). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScheduledTaskStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dir = File(context.filesDir, "scheduled-store-test")
    private lateinit var store: ScheduledTaskStore

    @Before
    fun setUp() {
        dir.deleteRecursively()
        store = ScheduledTaskStore(context.getSharedPreferences("scheduled-store-test", Context.MODE_PRIVATE), dir)
    }

    private fun task(id: String, chat: String? = null) =
        ScheduledTask(id = id, name = "Task $id", prompt = "p", startLocal = "2026-10-19T08:00", chatSessionId = chat)

    private val document = ComposerAttachment.Document(
        "a",
        Attachment(name = "notes.txt", mimeType = "text/plain", size = 5, text = "hello")
    )

    @Test
    fun `tasks are added, replaced and deleted`() {
        store.put(task("1"))
        store.put(task("2"))
        store.put(task("1").copy(name = "Renamed"))

        assertEquals(listOf("Renamed", "Task 2"), store.all().map { it.name })

        store.delete("1")
        assertEquals(listOf("2"), store.all().map { it.id })
    }

    @Test
    fun `update changes one task and reports a missing one`() {
        store.put(task("1"))
        val updated = store.update("1") { it.copy(enabled = false) }
        assertEquals(false, updated!!.enabled)
        assertEquals(false, store.get("1")!!.enabled)
        assertNull(store.update("nope") { it })
    }

    @Test
    fun `files are kept with the task and go with it`() {
        val withFile = task("1").copy(attachmentNames = listOf("notes.txt"))
        store.put(withFile)
        store.saveAttachments("1", listOf(document))

        assertEquals(listOf(document), store.loadAttachments(withFile))

        store.delete("1")
        assertTrue(dir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `a task whose files are gone reports them unreadable`() {
        val withFile = task("1").copy(attachmentNames = listOf("notes.txt"))
        assertNull(store.loadAttachments(withFile))
        assertEquals(emptyList<ComposerAttachment>(), store.loadAttachments(task("2")))
    }

    @Test
    fun `a deleted chat is forgotten by the tasks pinned to it`() {
        store.put(task("1", chat = "chat-a"))
        store.put(task("2", chat = "chat-b"))

        store.forgetChat("chat-a")

        assertNull(store.get("1")!!.chatSessionId)
        assertEquals("chat-b", store.get("2")!!.chatSessionId)
    }

    @Test
    fun `the briefing preset seeds once, paused`() {
        val zone = java.time.ZoneId.of("UTC")
        val now = java.time.LocalDateTime.parse("2026-10-29T22:15").atZone(zone).toInstant().toEpochMilli()

        val seeded = store.seedBriefingPreset("/mem", now, zone)

        assertTrue(seeded != null)
        assertEquals(MorningBriefing.TEMPLATE_NAME, seeded!!.name)
        assertEquals(false, seeded.enabled)
        assertEquals(com.gotcha.tools.AgentMode.MONITOR, seeded.mode)
        assertEquals("2026-10-30T07:00", seeded.startLocal)
        assertNull(store.seedBriefingPreset("/mem", now, zone))
        assertEquals(1, store.all().count { it.name == MorningBriefing.TEMPLATE_NAME })
    }

    @Test
    fun `the briefing preset skips a user-added briefing`() {
        val zone = java.time.ZoneId.of("UTC")
        val now = java.time.LocalDateTime.parse("2026-10-29T22:15").atZone(zone).toInstant().toEpochMilli()
        store.put(task("u").copy(name = MorningBriefing.TEMPLATE_NAME))

        assertNull(store.seedBriefingPreset("/mem", now, zone))
        assertEquals(1, store.all().size)
        assertTrue(store.hasBriefingPreset())
    }

    @Test
    fun `a user-added briefing is adopted and tracked across renames`() {
        val zone = java.time.ZoneId.of("UTC")
        val now = java.time.LocalDateTime.parse("2026-10-29T22:15").atZone(zone).toInstant().toEpochMilli()
        store.put(task("u").copy(name = MorningBriefing.TEMPLATE_NAME))

        assertNull(store.seedBriefingPreset("/mem", now, zone))
        store.update("u") { it.copy(name = "Mine") }
        assertTrue(store.hasBriefingPreset())
        assertNull(store.seedBriefingPreset("/mem", now, zone))
        assertEquals(1, store.all().size)
    }

    @Test
    fun `renaming the seeded preset neither duplicates it nor shows the re-add path`() {
        val zone = java.time.ZoneId.of("UTC")
        val now = java.time.LocalDateTime.parse("2026-10-29T22:15").atZone(zone).toInstant().toEpochMilli()
        val seeded = store.seedBriefingPreset("/mem", now, zone)!!
        store.update(seeded.id) { it.copy(name = "My mornings") }

        assertNull(store.seedBriefingPreset("/mem", now, zone))
        assertEquals(1, store.all().size)
        assertTrue(store.hasBriefingPreset())

        store.delete(seeded.id)
        assertTrue(!store.hasBriefingPreset())
    }

    @Test
    fun `a zone change rebases wall-clock tasks and reports whether anything moved`() {
        val berlin = java.time.ZoneId.of("Europe/Berlin")
        val tokyo = java.time.ZoneId.of("Asia/Tokyo")
        val armedAt = java.time.LocalDateTime.parse("2026-10-25T09:00").atZone(berlin).toInstant().toEpochMilli()
        store.put(
            task("daily").copy(
                startLocal = "2026-10-25T08:00",
                repeat = Repeat(RepeatKind.DAILY),
                nextRunAt = ScheduledTaskPlanner.armed(
                    task("daily").copy(
                        startLocal = "2026-10-25T08:00",
                        repeat = Repeat(RepeatKind.DAILY)
                    ),
                    armedAt,
                    berlin
                ).nextRunAt
            )
        )
        store.put(
            task("hourly").copy(
                startLocal = "2026-10-25T00:00",
                repeat = Repeat(RepeatKind.HOURLY, interval = 2),
                nextRunAt = armedAt + 2 * 60 * 60 * 1000L
            )
        )
        val sameInstant = armedAt
        assertTrue(store.rebaseForZoneChange(sameInstant, tokyo))
        assertEquals(
            java.time.LocalDateTime.parse("2026-10-26T08:00").atZone(tokyo).toInstant().toEpochMilli(),
            store.get("daily")!!.nextRunAt
        )
        assertEquals(armedAt + 2 * 60 * 60 * 1000L, store.get("hourly")!!.nextRunAt)
        // A second rebase with nothing moved reports false.
        assertTrue(!store.rebaseForZoneChange(sameInstant, tokyo))
    }
}
