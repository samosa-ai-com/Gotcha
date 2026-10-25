package com.gotcha.scheduled

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * [ScheduledTaskForm]: a save from the Settings editor lands on the task as it
 * is stored at that moment, so a run that changed it while the form was open
 * keeps what it wrote (#136 review).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScheduledTaskFormTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dir = File(context.filesDir, "scheduled-form-test")
    private val zone = ZoneId.of("UTC")
    private lateinit var store: ScheduledTaskStore

    private fun at(local: String): Long = LocalDateTime.parse(local).atZone(zone).toInstant().toEpochMilli()

    @Before
    fun setUp() {
        dir.deleteRecursively()
        store = ScheduledTaskStore(context.getSharedPreferences("scheduled-form-test", Context.MODE_PRIVATE), dir)
    }

    private val opened = ScheduledTaskPlanner.armed(
        ScheduledTask(
            id = "t",
            name = "Morning",
            prompt = "p",
            startLocal = "2026-10-19T08:00",
            repeat = Repeat(RepeatKind.DAILY),
            sameChat = true
        ),
        at("2026-10-19T07:00"),
        zone
    )

    /** The form as the editor would build it from [opened], with [name] typed in. */
    private fun form(
        name: String = opened.name,
        startLocal: String = opened.startLocal,
        chatSessionId: String? = opened.chatSessionId
    ) = ScheduledTaskForm(
        name = name,
        prompt = opened.prompt,
        startLocal = startLocal,
        repeat = opened.repeat,
        agentMode = opened.agentMode,
        personaId = opened.personaId,
        sameChat = opened.sameChat,
        chatSessionId = chatSessionId,
        openedWithChatId = opened.chatSessionId,
        maxRetries = opened.maxRetries,
        attachmentNames = opened.attachmentNames
    )

    /** The 08:00 run finished while the form was open: it moved the task on and started its chat. */
    private fun runFinishedMeanwhile() = store.update("t") {
        it.copy(
            nextRunAt = at("2026-10-20T08:00"),
            chatSessionId = "run-chat",
            history = listOf(TaskRun(at = at("2026-10-19T08:00"), status = TaskRunStatus.DONE, sessionId = "run-chat"))
        )
    }

    @Test
    fun `a save keeps the history, next time and chat of a run that ended while the form was open`() {
        store.put(opened)
        runFinishedMeanwhile()

        val saved = form(name = "Renamed").saveOver(store, "t", at("2026-10-19T08:05"), zone)!!

        assertEquals("Renamed", saved.name)
        assertEquals(at("2026-10-20T08:00"), saved.nextRunAt)
        assertEquals(1, saved.history.size)
        assertEquals("run-chat", saved.chatSessionId)
        assertEquals(saved, store.get("t"))
    }

    @Test
    fun `an edit that leaves the schedule alone keeps a pending retry or busy wait`() {
        store.put(opened.copy(retryAt = at("2026-10-19T08:10"), retriesUsed = 1, busySince = at("2026-10-19T08:00")))

        val saved = form(name = "Renamed").saveOver(store, "t", at("2026-10-19T08:05"), zone)!!

        assertEquals(at("2026-10-19T08:10"), saved.retryAt)
        assertEquals(1, saved.retriesUsed)
        assertEquals(at("2026-10-19T08:00"), saved.busySince)
    }

    @Test
    fun `changing the start time arms the task again`() {
        store.put(opened.copy(retryAt = at("2026-10-19T08:10"), retriesUsed = 1))

        val saved = form(startLocal = "2026-10-19T09:00").saveOver(store, "t", at("2026-10-19T08:05"), zone)!!

        assertEquals(at("2026-10-19T09:00"), saved.nextRunAt)
        assertNull(saved.retryAt)
        assertEquals(0, saved.retriesUsed)
    }

    @Test
    fun `a chat picked in the form wins over the stored one`() {
        store.put(opened)
        runFinishedMeanwhile()

        val saved = form(chatSessionId = "picked").saveOver(store, "t", at("2026-10-19T08:05"), zone)!!

        assertEquals("picked", saved.chatSessionId)
    }

    @Test
    fun `a task deleted while the form was open is not brought back`() {
        store.put(opened)
        store.delete("t")

        assertNull(form(name = "Renamed").saveOver(store, "t", at("2026-10-19T08:05"), zone))
        assertEquals(emptyList<ScheduledTask>(), store.all())
    }
}
