package com.gotcha.notifications

import com.gotcha.data.RunSummary
import com.gotcha.data.Settings
import com.gotcha.tools.AgentMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** The pure rules behind Gotcha's proactive notifications (issue #100). */
class LocalNotificationsTest {

    private val zone = ZoneId.of("Europe/Berlin")
    private val now = at(2026, 9, 24, 12)

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0) =
        ZonedDateTime.of(y, mo, d, h, mi, 0, 0, zone).toInstant().toEpochMilli()

    private fun run(
        endedAt: Long,
        prompt: String = "p",
        reply: String = "Done.",
        succeeded: Boolean = true,
        mode: String = "MONITOR"
    ) = RunSummary(
        startedAt = endedAt - 1_000,
        endedAt = endedAt,
        userPrompt = prompt,
        finalReply = reply,
        model = "m",
        agentMode = mode,
        delegated = false,
        succeeded = succeeded
    )

    private fun chat(id: String, vararg runs: RunSummary, sensitive: Boolean = false) =
        ChatDigest(id = id, title = "Chat $id", sensitive = sensitive, runs = runs.toList())

    private fun candidate(category: NotificationCategory = NotificationCategory.ROUTINE, key: String = "k") =
        LocalCandidate(category, key, "t", "b", NotificationTarget.Home)

    // ---- quiet hours ----

    @Test
    fun `quiet hours wrap past midnight`() {
        assertTrue(inQuietHours(23 * 60, 22 * 60, 8 * 60))
        assertTrue(inQuietHours(7 * 60 + 59, 22 * 60, 8 * 60))
        assertFalse(inQuietHours(8 * 60, 22 * 60, 8 * 60))
        assertFalse(inQuietHours(12 * 60, 22 * 60, 8 * 60))
    }

    @Test
    fun `quiet hours within a day, and equal ends mean none`() {
        assertTrue(inQuietHours(13 * 60, 12 * 60, 14 * 60))
        assertFalse(inQuietHours(14 * 60, 12 * 60, 14 * 60))
        assertFalse(inQuietHours(3 * 60, 9 * 60, 9 * 60))
    }

    // ---- the gate ----

    @Test
    fun `an allowed candidate passes`() {
        assertNull(blockReason(candidate(), Settings(), emptyList(), now, zone))
    }

    @Test
    fun `the master and category switches block`() {
        assertEquals(
            "off",
            blockReason(candidate(), Settings(localNotificationsEnabled = false), emptyList(), now, zone)
        )
        assertEquals(
            "category_off",
            blockReason(candidate(), Settings(routineSuggestionsEnabled = false), emptyList(), now, zone)
        )
    }

    @Test
    fun `quiet hours block, unless turned off`() {
        val night = at(2026, 9, 24, 23)
        assertEquals("quiet_hours", blockReason(candidate(), Settings(), emptyList(), night, zone))
        assertNull(blockReason(candidate(), Settings(quietHoursEnabled = false), emptyList(), night, zone))
    }

    @Test
    fun `the same occasion is never sent twice`() {
        val sent = listOf(SentRecord("k", NotificationCategory.INACTIVITY.name, now - 10 * DAY_MS))
        assertEquals("duplicate", blockReason(candidate(key = "k"), Settings(), sent, now, zone))
    }

    @Test
    fun `one per category within the cooldown`() {
        val sent = listOf(SentRecord("other", NotificationCategory.ROUTINE.name, now - 5 * HOUR_MS))
        assertEquals("cooldown", blockReason(candidate(), Settings(), sent, now, zone))
        val old = listOf(SentRecord("other", NotificationCategory.ROUTINE.name, now - 21 * HOUR_MS))
        assertNull(blockReason(candidate(), Settings(), old, now, zone))
    }

    @Test
    fun `the daily cap counts today's sends across categories`() {
        val sent = listOf(
            SentRecord("a", NotificationCategory.DAILY_TIP.name, at(2026, 9, 24, 10)),
            SentRecord("b", NotificationCategory.INACTIVITY.name, at(2026, 9, 24, 11))
        )
        assertEquals("daily_cap", blockReason(candidate(), Settings(), sent, now, zone))
        assertNull(blockReason(candidate(), Settings(maxLocalNotificationsPerDay = 3), sent, now, zone))
        // Yesterday's don't count.
        val yesterday = sent.map { it.copy(at = it.at - DAY_MS) }
        assertNull(blockReason(candidate(), Settings(), yesterday, now, zone))
    }

    // ---- unfinished chats ----

    @Test
    fun `a failed run from a few hours ago is unfinished`() {
        val c = unfinishedChat(listOf(chat("a", run(now - 3 * HOUR_MS, succeeded = false))), mention = true, now)!!
        assertEquals(NotificationCategory.UNFINISHED_CHAT, c.category)
        assertEquals(NotificationTarget.Chat("a"), c.target)
        assertTrue(c.body.contains("Chat a"))
    }

    @Test
    fun `a reply ending on a question is unfinished`() {
        val c = unfinishedChat(listOf(chat("a", run(now - 3 * HOUR_MS, reply = "Which Priya?"))), true, now)!!
        assertEquals("Gotcha is waiting for you", c.title)
    }

    @Test
    fun `too fresh, too old, finished or superseded is not unfinished`() {
        assertNull(unfinishedChat(listOf(chat("a", run(now - HOUR_MS, succeeded = false))), true, now))
        assertNull(unfinishedChat(listOf(chat("a", run(now - 4 * DAY_MS, succeeded = false))), true, now))
        assertNull(unfinishedChat(listOf(chat("a", run(now - 3 * HOUR_MS))), true, now))
        // Only the chat's last run counts: a later success finished it.
        val later = chat("a", run(now - 5 * HOUR_MS, succeeded = false), run(now - 3 * HOUR_MS))
        assertNull(unfinishedChat(listOf(later), true, now))
    }

    @Test
    fun `without mentions the text names no chat`() {
        val c = unfinishedChat(listOf(chat("a", run(now - 3 * HOUR_MS, succeeded = false))), mention = false, now)!!
        assertFalse(c.body.contains("Chat a"))
        assertEquals(NotificationTarget.Chat("a"), c.target)
    }

    // ---- routines ----

    private fun weekly(prompt: String, weeksAgo: List<Int>, mode: String = "MONITOR") =
        weeksAgo.map { run(now - it * 7 * DAY_MS - HOUR_MS, prompt = prompt, mode = mode) }

    @Test
    fun `a weekly request is due a week after the last`() {
        val runs = weekly("Summarise my unread emails from 9 to 5", listOf(1, 2, 3), mode = "OPERATOR")
        val c = dueRoutine(listOf(chat("a", *runs.toTypedArray())), mention = true, now, zone)!!
        assertEquals(NotificationCategory.ROUTINE, c.category)
        assertEquals(
            NotificationTarget.Draft("Summarise my unread emails from 9 to 5", AgentMode.OPERATOR),
            c.target
        )
        assertTrue(c.body.contains("Summarise my unread emails"))
    }

    @Test
    fun `numbers don't stop requests matching`() {
        val runs = listOf(
            run(now - 7 * DAY_MS, prompt = "Set an alarm for 7am"),
            run(now - 14 * DAY_MS, prompt = "set an alarm for 6am!"),
            run(now - 21 * DAY_MS, prompt = "Set an alarm for 7:30am")
        )
        assertTrue(dueRoutine(listOf(chat("a", *runs.toTypedArray())), true, now, zone) != null)
    }

    @Test
    fun `not due early, not nagged when long abandoned, not from two days`() {
        // Weekly, but the last time was only four days ago.
        val early = listOf(4, 11, 18).map { run(now - it * DAY_MS, prompt = "Summarise my unread emails") }
        assertNull(dueRoutine(listOf(chat("a", *early.toTypedArray())), true, now, zone))
        val stale = weekly("Summarise my unread emails", listOf(4, 5, 6))
        assertNull(dueRoutine(listOf(chat("a", *stale.toTypedArray())), true, now, zone))
        val twice = weekly("Summarise my unread emails", listOf(1, 2))
        assertNull(dueRoutine(listOf(chat("a", *twice.toTypedArray())), true, now, zone))
    }

    @Test
    fun `short requests are not routines`() {
        assertEquals("", routineKey("hi"))
        assertEquals("", routineKey("What's 2+2?"))
    }

    // ---- inactivity ----

    @Test
    fun `a reminder after the chosen number of days, once per spell`() {
        val opened = now - 5 * DAY_MS
        val c = inactivityReminder(listOf(chat("a", run(opened))), opened, 4, mention = true, now)!!
        assertEquals("inactive:$opened", c.dedupKey)
        assertEquals(NotificationTarget.Chat("a"), c.target)
        assertNull(inactivityReminder(emptyList(), now - 3 * DAY_MS, 4, true, now))
        assertNull(inactivityReminder(emptyList(), 0L, 4, true, now))
    }

    @Test
    fun `without mentions the reminder goes home`() {
        val opened = now - 5 * DAY_MS
        val c = inactivityReminder(listOf(chat("a", run(opened))), opened, 4, mention = false, now)!!
        assertEquals(NotificationTarget.Home, c.target)
        assertFalse(c.body.contains("Chat a"))
    }

    // ---- ordering and sensitive chats ----

    @Test
    fun `unfinished beats routine beats inactivity`() {
        val opened = now - 5 * DAY_MS
        val chats = listOf(
            chat("u", run(now - 3 * HOUR_MS, succeeded = false)),
            chat("r", *weekly("Summarise my unread emails", listOf(1, 2, 3)).toTypedArray())
        )
        val all = behaviorCandidates(chats, opened, 4, true, now, zone)
        assertEquals(
            listOf(NotificationCategory.UNFINISHED_CHAT, NotificationCategory.ROUTINE, NotificationCategory.INACTIVITY),
            all.map { it.category }
        )
    }

    @Test
    fun `sensitive chats are never used`() {
        val opened = now - 5 * DAY_MS
        val chats = listOf(
            chat("u", run(now - 3 * HOUR_MS, succeeded = false), sensitive = true),
            chat("r", *weekly("Summarise my unread emails", listOf(1, 2, 3)).toTypedArray(), sensitive = true)
        )
        val all = behaviorCandidates(chats, opened, 4, true, now, zone)
        assertEquals(listOf(NotificationCategory.INACTIVITY), all.map { it.category })
        assertEquals(NotificationTarget.Home, all.single().target)
    }

    @Test
    fun `doctor chats are sensitive unless the user says otherwise`() {
        assertTrue(isChatSensitive("doctor", null))
        assertFalse(isChatSensitive("doctor", false))
        assertFalse(isChatSensitive("chef", null))
        assertTrue(isChatSensitive(null, true))
    }
}
