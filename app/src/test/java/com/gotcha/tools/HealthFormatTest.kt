package com.gotcha.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class HealthFormatTest {

    @Test
    fun `days defaults to a week and is clamped`() {
        assertEquals(7, HealthFormat.days(null))
        assertEquals(1, HealthFormat.days(0))
        assertEquals(1, HealthFormat.days(-5))
        assertEquals(365, HealthFormat.days(10_000))
        assertEquals(30, HealthFormat.days(30))
    }

    @Test
    fun `window ends now and starts the requested number of days earlier`() {
        val now = Instant.parse("2026-07-25T12:00:00Z")
        val (start, end) = HealthFormat.window(now, 7)
        assertEquals(now, end)
        assertEquals(Instant.parse("2026-07-18T12:00:00Z"), start)
    }

    @Test
    fun `durations under an hour render as minutes`() {
        assertEquals("45 min", HealthFormat.duration(Duration.ofMinutes(45)))
        assertEquals("0 min", HealthFormat.duration(Duration.ZERO))
    }

    @Test
    fun `durations on the hour omit the minutes part`() {
        assertEquals("2 h", HealthFormat.duration(Duration.ofHours(2)))
    }

    @Test
    fun `durations render hours and minutes together`() {
        assertEquals("7 h 35 min", HealthFormat.duration(Duration.ofMinutes(455)))
    }

    @Test
    fun `distance converts metres to kilometres`() {
        assertEquals("5.00 km", HealthFormat.metresAsKm(5000.0))
        assertEquals("0.75 km", HealthFormat.metresAsKm(750.0))
    }

    @Test
    fun `step counts are grouped for readability`() {
        assertTrue(HealthFormat.steps(1234567).contains("1"))
        assertEquals("500", HealthFormat.steps(500))
    }

    @Test
    fun `per-day average divides the total over the window`() {
        assertEquals("1,000/day", HealthFormat.perDay(7000, 7))
    }

    @Test
    fun `per-day average of missing data is a dash, not zero`() {
        assertEquals("—", HealthFormat.perDay(null, 7))
    }

    @Test
    fun `summary omits metrics with no data`() {
        val text = HealthFormat.summary(
            7,
            listOf(
                "Steps" to "50,000 total",
                "Weight" to null,
                "Sleep" to "49 h total"
            )
        )
        assertTrue(text.contains("Steps"))
        assertTrue(text.contains("Sleep"))
        assertTrue("absent metrics must not be listed", !text.contains("Weight"))
    }

    @Test
    fun `an entirely empty summary explains why rather than reporting zeroes`() {
        val text = HealthFormat.summary(7, listOf("Steps" to null, "Sleep" to null))
        assertTrue(text.contains("no data"))
        assertTrue(text.contains("permissions"))
        assertTrue("must not fabricate a zero reading", !text.contains(": 0"))
    }

    @Test
    fun `summary names the window it covers`() {
        val text = HealthFormat.summary(30, listOf("Steps" to "1 total"))
        assertTrue(text.contains("30 day"))
    }
}
