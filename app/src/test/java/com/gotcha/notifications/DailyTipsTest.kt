package com.gotcha.notifications

import com.gotcha.tools.AgentMode
import com.gotcha.tools.ToolDefinitions
import com.gotcha.tools.ToolRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.random.Random

/** The daily tip catalog and the pure rules that choose and time a tip (issue #101). */
class DailyTipsTest {

    private val zone = ZoneId.of("Europe/Berlin")

    private fun millis(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0) =
        ZonedDateTime.of(y, mo, d, h, mi, 0, 0, zone).toInstant().toEpochMilli()

    private fun tip(id: String, vararg tools: String) =
        DailyTip(id = id, title = id, body = id, prompt = id, tools = tools.toSet())

    // ---- catalog ----

    @Test
    fun `tip ids are unique`() {
        val ids = DAILY_TIPS.map { it.id }
        assertEquals(ids.size, ids.distinct().size)
        // Ids are stored comma-separated.
        ids.forEach { assertFalse("Comma in id $it", ',' in it) }
    }

    @Test
    fun `there are more tips than the recent memory holds`() {
        assertTrue(DAILY_TIPS.size > RECENT_TIP_MEMORY)
    }

    @Test
    fun `every tip names real tools and has text`() {
        val known = ToolDefinitions.all.map { it.function.name }.toSet()
        DAILY_TIPS.forEach { tip ->
            val texts = listOf(tip.title, tip.body, tip.prompt)
            assertTrue("Blank text in ${tip.id}", texts.all { it.isNotBlank() })
            assertTrue("${tip.id} lists no tools", tip.tools.isNotEmpty())
            tip.tools.forEach { assertTrue("${tip.id} names unknown tool $it", it in known) }
        }
    }

    @Test
    fun `each tip starts in the mode its tools need`() {
        DAILY_TIPS.forEach { tip ->
            val readOnly = tip.tools.all { it in ToolRegistry.monitorTools }
            val expected = if (readOnly) AgentMode.MONITOR else AgentMode.OPERATOR
            assertEquals("Mode of ${tip.id}", expected, tip.agent)
        }
    }

    // ---- picking ----

    @Test
    fun `recently shown tips are skipped`() {
        val tips = listOf(tip("a", "x"), tip("b", "y"), tip("c", "z"))
        repeat(20) { seed ->
            assertEquals("c", pickDailyTip(tips, listOf("a", "b"), emptySet(), Random(seed))?.id)
        }
    }

    @Test
    fun `tips for untried tools win`() {
        val tips = listOf(tip("used", "read_screen"), tip("new", "send_sms"))
        repeat(20) { seed ->
            assertEquals("new", pickDailyTip(tips, emptyList(), setOf("read_screen"), Random(seed))?.id)
        }
    }

    @Test
    fun `falls back to tried tools once every fresh tip is tried`() {
        val tips = listOf(tip("a", "x"), tip("b", "y"))
        assertEquals("b", pickDailyTip(tips, listOf("a"), setOf("x", "y"))?.id)
    }

    @Test
    fun `starts over when every tip is recent, avoiding the last one`() {
        val tips = listOf(tip("a", "x"), tip("b", "y"))
        repeat(20) { seed ->
            assertEquals("a", pickDailyTip(tips, listOf("a", "b"), emptySet(), Random(seed))?.id)
        }
        // A single tip is still offered.
        assertEquals("a", pickDailyTip(listOf(tip("a", "x")), listOf("a"), emptySet())?.id)
    }

    @Test
    fun `an empty catalog picks nothing`() {
        assertNull(pickDailyTip(emptyList(), emptyList(), emptySet()))
    }

    @Test
    fun `shown tips are remembered newest last and capped`() {
        var recent = emptyList<String>()
        (1..RECENT_TIP_MEMORY + 3).forEach { recent = rememberShownTip(recent, "t$it") }
        assertEquals(RECENT_TIP_MEMORY, recent.size)
        assertEquals("t${RECENT_TIP_MEMORY + 3}", recent.last())
        assertEquals(listOf("b", "a"), rememberShownTip(listOf("a", "b"), "a"))
    }

    // ---- timing ----

    @Test
    fun `next tip is today when the time is still ahead`() {
        assertEquals(millis(2026, 9, 23, 10), nextDailyTipAt(millis(2026, 9, 23, 8), 600, zone))
    }

    @Test
    fun `next tip is tomorrow once the time has passed`() {
        assertEquals(millis(2026, 9, 24, 10), nextDailyTipAt(millis(2026, 9, 23, 10), 600, zone))
        assertEquals(millis(2026, 9, 24, 10), nextDailyTipAt(millis(2026, 9, 23, 22), 600, zone))
    }

    @Test
    fun `the clock time holds across a daylight saving change`() {
        // Berlin leaves summer time on 25 October 2026: that day is 25 hours long.
        val next = nextDailyTipAt(millis(2026, 10, 24, 12), 600, zone)
        assertEquals(millis(2026, 10, 25, 10), next)
        assertNotEquals(millis(2026, 10, 24, 10) + 24L * 3600_000L, next)
    }

    @Test
    fun `used today means the same local day`() {
        val now = millis(2026, 9, 23, 10)
        assertTrue(usedToday(millis(2026, 9, 23, 0, 30), now, zone))
        assertFalse(usedToday(millis(2026, 9, 22, 23, 30), now, zone))
        assertFalse(usedToday(null, now, zone))
    }
}
