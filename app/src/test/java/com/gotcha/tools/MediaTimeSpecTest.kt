package com.gotcha.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Timecode arithmetic for `media_edit`. Pure JVM: [MediaTimeSpec] deliberately
 * touches no Android or media3 API, because a wrong conversion here silently
 * trims the wrong window rather than failing loudly — and the encoder half of
 * the tool needs a real device codec to exercise at all.
 */
class MediaTimeSpecTest {

    private fun parsed(spec: String): Long = MediaTimeSpec.parse(spec).getOrThrow()

    private fun error(spec: String): String {
        val result = MediaTimeSpec.parse(spec)
        assertTrue("expected '$spec' to be rejected, got ${result.getOrNull()}", result.isFailure)
        return result.exceptionOrNull()!!.message.orEmpty()
    }

    @Test
    fun `plain number is seconds`() {
        assertEquals(0L, parsed("0"))
        assertEquals(12_000L, parsed("12"))
        assertEquals(12_500L, parsed("12.5"))
    }

    @Test
    fun `colon form reads as minutes and seconds`() {
        assertEquals(83_000L, parsed("1:23"))
        assertEquals(83_500L, parsed("1:23.5"))
        assertEquals(0L, parsed("0:00"))
    }

    @Test
    fun `three colon fields read as hours minutes seconds`() {
        assertEquals(3_723_000L, parsed("1:02:03"))
        assertEquals(36_000_000L, parsed("10:00:00"))
    }

    @Test
    fun `unit form accepts single and compound units`() {
        assertEquals(90_000L, parsed("90s"))
        assertEquals(90_000L, parsed("1m30s"))
        assertEquals(500L, parsed("500ms"))
        assertEquals(3_723_000L, parsed("1h2m3s"))
        assertEquals(120_000L, parsed("2m"))
    }

    @Test
    fun `unit form is case insensitive and tolerates surrounding space`() {
        assertEquals(90_000L, parsed(" 1M30S "))
    }

    @Test
    fun `ms suffix is not read as minutes plus seconds`() {
        // The alternation must prefer "ms" over "m", or 2ms becomes 2 minutes.
        assertEquals(2L, parsed("2ms"))
    }

    @Test
    fun `sixty or more seconds inside a colon field is ambiguous and refused`() {
        assertTrue(error("1:90").contains("at most 59"))
        assertTrue(error("1:02:75").contains("at most 59"))
    }

    @Test
    fun `sixty or more minutes inside an hour form is refused`() {
        assertTrue(error("1:75:00").contains("at most 59"))
    }

    @Test
    fun `negative timecodes are refused`() {
        assertTrue(error("-5").contains("negative"))
        assertTrue(error("-1:30").contains("negative"))
    }

    @Test
    fun `malformed timecodes name the accepted forms`() {
        assertTrue(error("banana").contains("1:23"))
        assertTrue(error("").contains("1:23"))
        assertTrue(error("1:2:3:4").contains("1:23"))
        assertTrue(error("1::3").contains("1:23"))
    }

    // ---- ranges ----

    private fun range(start: String?, end: String?, durationMs: Long = 60_000L): LongRange =
        MediaTimeSpec.parseRange(start, end, durationMs).getOrThrow()

    private fun rangeError(start: String?, end: String?, durationMs: Long = 60_000L): String {
        val result = MediaTimeSpec.parseRange(start, end, durationMs)
        assertTrue("expected $start..$end to be rejected", result.isFailure)
        return result.exceptionOrNull()!!.message.orEmpty()
    }

    @Test
    fun `null start is the beginning and null end is the duration`() {
        assertEquals(0L..60_000L, range(null, null))
    }

    @Test
    fun `the literal end keyword resolves to the duration`() {
        assertEquals(10_000L..60_000L, range("10", "end"))
        assertEquals(10_000L..60_000L, range("10", "END"))
    }

    @Test
    fun `an end past the duration is clamped rather than refused`() {
        // The model misreading a duration should still produce the sensible clip.
        assertEquals(10_000L..60_000L, range("10", "5:00"))
    }

    @Test
    fun `a start at or past the duration is refused`() {
        assertTrue(rangeError("60", null).contains("past the end"))
        assertTrue(rangeError("90", null).contains("past the end"))
    }

    @Test
    fun `a backwards window is refused rather than silently emptied`() {
        assertTrue(rangeError("30", "10").contains("not after start"))
        assertTrue(rangeError("30", "30").contains("not after start"))
    }

    @Test
    fun `an unknown duration leaves the end unclamped`() {
        // MediaMetadataRetriever reports 0 for some streams; the clip should still run.
        assertEquals(10_000L..20_000L, range("10", "20", durationMs = 0L))
    }

    @Test
    fun `a malformed bound is reported rather than defaulted`() {
        assertTrue(rangeError("banana", null).contains("1:23"))
        assertTrue(rangeError(null, "banana").contains("1:23"))
    }

    // ---- formatting ----

    @Test
    fun `format renders minutes and seconds with zero padding`() {
        assertEquals("0:00", MediaTimeSpec.format(0L))
        assertEquals("1:23", MediaTimeSpec.format(83_000L))
        assertEquals("1:23.5", MediaTimeSpec.format(83_500L))
    }

    @Test
    fun `format adds an hours field only when needed`() {
        assertEquals("1:02:03", MediaTimeSpec.format(3_723_000L))
        assertEquals("59:59", MediaTimeSpec.format(3_599_000L))
    }

    @Test
    fun `format round-trips through parse`() {
        listOf("0:30", "1:23", "1:02:03").forEach { spec ->
            assertEquals(spec, MediaTimeSpec.format(MediaTimeSpec.parse(spec).getOrThrow()))
        }
    }
}
