package com.gotcha.connectors.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarWindowTest {

    private val hour = 3_600_000L
    private val day = 24 * hour

    @Test
    fun `days_ahead builds a forward window from now`() {
        val window = CalendarWindow.resolve(daysAhead = 3, fromDate = null, toDate = null, now = 1_000L)
        assertEquals(1_000L, window.startMs)
        assertEquals(1_000L + 3 * day, window.endMs)
        assertTrue(window.description.contains("3 day"))
    }

    @Test
    fun `days_ahead defaults to seven and is clamped`() {
        val default = CalendarWindow.resolve(null, null, null, now = 0)
        assertEquals(7 * day, default.endMs)

        val clamped = CalendarWindow.resolve(daysAhead = 9999, fromDate = null, toDate = null, now = 0)
        assertEquals(365 * day, clamped.endMs)
    }

    @Test
    fun `explicit dates override days_ahead`() {
        val window = CalendarWindow.resolve(daysAhead = 3, fromDate = "2026-01-01", toDate = "2026-01-02", now = 0)
        assertTrue(window.endMs > window.startMs)
        assertEquals("2026-01-01 → 2026-01-02", window.description)
    }

    @Test
    fun `rfc3339 round-trips through the parser`() {
        val epoch = 1_767_225_600_000L
        assertEquals(epoch, CalendarWindow.parseRfc3339(CalendarWindow.toRfc3339(epoch)))
    }

    @Test
    fun `parses graph timestamps that omit the zone`() {
        // Graph returns this shape when Prefer: outlook.timezone="UTC" is set.
        val parsed = CalendarWindow.parseRfc3339("2026-01-01T09:00:00.0000000")
        assertEquals(CalendarWindow.parseRfc3339("2026-01-01T09:00:00Z"), parsed)
    }

    @Test
    fun `parses calendar v3 timestamps with an offset`() {
        val withOffset = CalendarWindow.parseRfc3339("2026-01-01T10:00:00+01:00")
        assertEquals(CalendarWindow.parseRfc3339("2026-01-01T09:00:00Z"), withOffset)
    }

    @Test
    fun `unparseable input yields null rather than throwing`() {
        assertEquals(null, CalendarWindow.parseRfc3339("not a date"))
        assertEquals(null, CalendarWindow.parseRfc3339(null))
    }

    @Test
    fun `merge coalesces overlapping and adjacent blocks`() {
        val merged = CalendarWindow.merge(
            listOf(
                BusyBlock(30, 40),
                BusyBlock(0, 10),
                BusyBlock(10, 20), // adjacent to the previous one
                BusyBlock(5, 8) // fully contained
            )
        )
        assertEquals(listOf(BusyBlock(0, 20), BusyBlock(30, 40)), merged)
    }

    @Test
    fun `merge drops zero-length blocks`() {
        assertEquals(emptyList<BusyBlock>(), CalendarWindow.merge(listOf(BusyBlock(5, 5))))
    }

    @Test
    fun `freeSlots returns gaps at least as long as the minimum`() {
        val busy = listOf(BusyBlock(2 * hour, 3 * hour), BusyBlock(5 * hour, 6 * hour))
        val free = CalendarWindow.freeSlots(busy, 0, 8 * hour, hour)

        assertEquals(
            listOf(
                BusyBlock(0, 2 * hour),
                BusyBlock(3 * hour, 5 * hour),
                BusyBlock(6 * hour, 8 * hour)
            ),
            free
        )
    }

    @Test
    fun `freeSlots skips gaps shorter than the minimum`() {
        val busy = listOf(BusyBlock(0, hour), BusyBlock(hour + 60_000, 3 * hour))
        val free = CalendarWindow.freeSlots(busy, 0, 3 * hour, hour)
        assertTrue("the one-minute gap must not be offered", free.isEmpty())
    }

    @Test
    fun `a fully free window is one slot`() {
        assertEquals(
            listOf(BusyBlock(0, 4 * hour)),
            CalendarWindow.freeSlots(emptyList(), 0, 4 * hour, hour)
        )
    }

    @Test
    fun `a fully busy window has no slots`() {
        val busy = listOf(BusyBlock(0, 4 * hour))
        assertTrue(CalendarWindow.freeSlots(busy, 0, 4 * hour, hour).isEmpty())
    }

    @Test
    fun `busy blocks overhanging the window do not produce negative slots`() {
        val busy = listOf(BusyBlock(-2 * hour, 2 * hour), BusyBlock(3 * hour, 10 * hour))
        val free = CalendarWindow.freeSlots(busy, 0, 4 * hour, 30 * 60_000L)
        assertEquals(listOf(BusyBlock(2 * hour, 3 * hour)), free)
    }
}
