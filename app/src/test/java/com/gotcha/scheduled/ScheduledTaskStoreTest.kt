package com.gotcha.scheduled

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.gotcha.agent.Attachment
import com.gotcha.agent.ComposerAttachment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    private val prefs = context.getSharedPreferences("scheduled-store-test", Context.MODE_PRIVATE)
    private lateinit var store: ScheduledTaskStore

    @Before
    fun setUp() {
        dir.deleteRecursively()
        prefs.edit().clear().commit()
        store = ScheduledTaskStore(prefs, dir)
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
    fun `every example seeds once, paused, in its own shape`() {
        val zone = java.time.ZoneId.of("UTC")
        val now = java.time.LocalDateTime.parse("2026-10-29T22:15").atZone(zone).toInstant().toEpochMilli()

        val created = store.seedPresets("/mem", now, zone)

        assertEquals(ScheduledPresets.all.size, created.size)
        assertEquals(created.size, store.all().size)
        assertTrue(store.all().all { !it.enabled && it.presetId != null })
        assertTrue(store.all().all { it.mode == com.gotcha.tools.AgentMode.MONITOR })
        assertTrue(store.all().none { it.createdByAgent })

        val evening = store.all().first { it.presetId == ScheduledPresets.EVENING_WRAP_UP }
        assertEquals("2026-10-30T21:00", evening.startLocal)
        val weekly = store.all().first { it.presetId == ScheduledPresets.WEEKLY_REVIEW }
        assertEquals(java.time.DayOfWeek.SUNDAY, weekly.start.dayOfWeek)
        val inbox = store.all().first { it.presetId == ScheduledPresets.INBOX_CATCH_UP }
        assertTrue(inbox.start.dayOfWeek in java.time.DayOfWeek.MONDAY..java.time.DayOfWeek.FRIDAY)
        assertEquals(java.time.LocalTime.of(18, 30), inbox.start.toLocalTime())

        assertTrue(store.seedPresets("/mem", now, zone).isEmpty())
        assertEquals(created.size, store.all().size)
    }

    @Test
    fun `the old briefing preset is adopted, not duplicated`() {
        val zone = java.time.ZoneId.of("UTC")
        val now = java.time.LocalDateTime.parse("2026-10-29T22:15").atZone(zone).toInstant().toEpochMilli()
        store.put(task("u").copy(name = MorningBriefing.TEMPLATE_NAME))
        prefs.edit()
            .putBoolean(KEY_BRIEFING_SEEDED, true)
            .putString(KEY_BRIEFING_TASK_ID, "u")
            .commit()

        val created = store.seedPresets("/mem", now, zone)

        assertEquals(ScheduledPresets.all.size - 1, created.size)
        assertEquals(ScheduledPresets.MORNING_BRIEFING, store.get("u")!!.presetId)
        assertEquals(1, store.all().count { it.name == MorningBriefing.TEMPLATE_NAME })
    }

    @Test
    fun `a task the user already named like an example is adopted`() {
        val zone = java.time.ZoneId.of("UTC")
        val now = java.time.LocalDateTime.parse("2026-10-29T22:15").atZone(zone).toInstant().toEpochMilli()
        store.put(task("u").copy(name = "Weekly review"))

        val created = store.seedPresets("/mem", now, zone)

        assertEquals(ScheduledPresets.all.size - 1, created.size)
        assertEquals(ScheduledPresets.WEEKLY_REVIEW, store.get("u")!!.presetId)
        assertEquals(1, store.all().count { it.name == "Weekly review" })
    }

    @Test
    fun `a deleted example stays gone until examples are restored`() {
        val zone = java.time.ZoneId.of("UTC")
        val now = java.time.LocalDateTime.parse("2026-10-29T22:15").atZone(zone).toInstant().toEpochMilli()
        store.seedPresets("/mem", now, zone)
        val evening = store.all().first { it.presetId == ScheduledPresets.EVENING_WRAP_UP }

        store.delete(evening.id)

        assertTrue(store.seedPresets("/mem", now, zone).isEmpty())
        assertTrue(store.all().none { it.presetId == ScheduledPresets.EVENING_WRAP_UP })
        assertEquals(1, store.missingPresets().size)

        assertEquals(1, store.restorePresets("/mem", now, zone))
        assertEquals(1, store.all().count { it.presetId == ScheduledPresets.EVENING_WRAP_UP })
        assertTrue(store.missingPresets().isEmpty())
    }

    @Test
    fun `a promoted example is never seeded or restored again`() {
        val zone = java.time.ZoneId.of("UTC")
        val now = java.time.LocalDateTime.parse("2026-10-29T22:15").atZone(zone).toInstant().toEpochMilli()
        store.seedPresets("/mem", now, zone)
        val evening = store.all().first { it.presetId == ScheduledPresets.EVENING_WRAP_UP }

        store.update(evening.id) { it.copy(presetId = null, enabled = true) }
        store.forgetPreset(ScheduledPresets.EVENING_WRAP_UP)
        // Renaming is the main reason to customize an example; it must not
        // make the example look missing again.
        store.update(evening.id) { it.copy(name = "My evening") }

        assertTrue(store.missingPresets().none { it.id == ScheduledPresets.EVENING_WRAP_UP })
        assertTrue(store.seedPresets("/mem", now, zone).isEmpty())
        assertEquals(0, store.restorePresets("/mem", now, zone))
        assertEquals(1, store.all().count { it.name == "My evening" })
        assertEquals(0, store.all().count { it.name == evening.name })
        assertEquals(null, store.get(evening.id)!!.presetId)

        // Deleting a promoted example does not bring it back either: it stopped
        // being an example when the user made it theirs.
        store.delete(evening.id)
        assertTrue(store.missingPresets().none { it.id == ScheduledPresets.EVENING_WRAP_UP })
        assertEquals(0, store.restorePresets("/mem", now, zone))
    }

    @Test
    fun `a zone change rebases wall-clock tasks and reports whether anything moved`() {
        val berlin = java.time.ZoneId.of("Europe/Berlin")
        val tokyo = java.time.ZoneId.of("Asia/Tokyo")
        val armedAt = java.time.LocalDateTime.parse("2026-10-25T09:00").atZone(berlin).toInstant().toEpochMilli()
        val daily = task("daily").copy(
            startLocal = "2026-10-25T08:00",
            repeat = Repeat(RepeatKind.DAILY)
        )
        store.put(ScheduledTaskPlanner.armed(daily, armedAt, berlin))
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
        assertFalse(store.rebaseForZoneChange(sameInstant, tokyo))
    }
}
