package com.gotcha.tools

import java.util.Locale

/**
 * Parsing of the timecodes the model writes when trimming media ("1:23", "90s",
 * "1h02m03s", "12.5").
 *
 * Kept apart from [MediaEditTool] and free of Android APIs so the arithmetic —
 * the part most likely to be wrong — is unit-testable without Robolectric or a
 * device codec. Mirrors the split between [PdfTool] and [PdfPageSpec].
 *
 * Everything is milliseconds: [android.media.MediaMetadataRetriever] reports
 * duration in ms, and media3's `ClippingConfiguration` takes ms, so there is no
 * unit conversion left to get wrong at the call site.
 */
object MediaTimeSpec {

    /** Accepted spelling for "to the end of the file" as an end timecode. */
    const val END = "end"

    private const val MS_PER_SECOND = 1000L
    private const val SECONDS_PER_MINUTE = 60
    private const val MINUTES_PER_HOUR = 60
    private const val COLON_PARTS_MIN_SEC = 2
    private const val COLON_PARTS_HOUR_MIN_SEC = 3

    /** "90s", "1m30s", "500ms", "1h2m" — a run of number+unit pairs with no separators. */
    private val UNIT_FORM = Regex("^(?:\\d+(?:\\.\\d+)?(?:ms|s|m|h))+$", RegexOption.IGNORE_CASE)
    private val UNIT_PAIR = Regex("(\\d+(?:\\.\\d+)?)(ms|s|m|h)", RegexOption.IGNORE_CASE)

    /**
     * Returns the timecode in milliseconds, or a failure whose message is written
     * for the model to act on rather than for a log file.
     */
    @Suppress("ReturnCount")
    fun parse(spec: String): Result<Long> {
        val trimmed = spec.trim().lowercase(Locale.ROOT)
        if (trimmed.isEmpty()) return malformed(spec)
        if (trimmed.startsWith("-")) {
            return Result.failure(
                IllegalArgumentException(
                    "Timecode '$spec' is negative. Timecodes are measured from the start of the file."
                )
            )
        }
        return when {
            trimmed.contains(':') -> parseColonForm(trimmed, spec)
            UNIT_FORM.matches(trimmed) -> parseUnitForm(trimmed)
            else -> trimmed.toDoubleOrNull()
                ?.let { Result.success(secondsToMillis(it)) }
                ?: malformed(spec)
        }
    }

    /**
     * Resolves a trim window against a known [durationMs]. A null [start] means
     * the beginning; a null or "end" [end] means the end of the file.
     *
     * The returned range is half-open in spirit — `first` is inclusive, `last` is
     * the exclusive end position — but expressed as a [LongRange] because callers
     * only ever hand both numbers to media3.
     */
    @Suppress("ReturnCount")
    fun parseRange(start: String?, end: String?, durationMs: Long): Result<LongRange> {
        val startMs = start?.let { parse(it).getOrElse { e -> return Result.failure(e) } } ?: 0L
        val endMs = when {
            end == null || end.trim().equals(END, ignoreCase = true) -> durationMs
            else -> parse(end).getOrElse { e -> return Result.failure(e) }
        }
        if (durationMs > 0 && startMs >= durationMs) {
            return Result.failure(
                IllegalArgumentException(
                    "Start ${format(startMs)} is at or past the end of the file (${format(durationMs)}). " +
                        "Call operation='info' to check the duration before trimming."
                )
            )
        }
        // A clip that overruns the file is the model misreading a duration, not a
        // reason to fail: media3 stops at the last frame, so clamp and carry on.
        val clampedEnd = if (durationMs > 0) minOf(endMs, durationMs) else endMs
        if (clampedEnd <= startMs) {
            return Result.failure(
                IllegalArgumentException(
                    "End ${format(endMs)} is not after start ${format(startMs)} — that would produce an empty file. " +
                        "Write the window low-to-high."
                )
            )
        }
        return Result.success(startMs..clampedEnd)
    }

    /** Renders milliseconds back as "h:mm:ss.s" / "m:ss.s", for result messages. */
    fun format(ms: Long): String {
        val totalSeconds = ms / MS_PER_SECOND
        val tenths = (ms % MS_PER_SECOND) / 100
        val seconds = totalSeconds % SECONDS_PER_MINUTE
        val minutes = (totalSeconds / SECONDS_PER_MINUTE) % MINUTES_PER_HOUR
        val hours = totalSeconds / (SECONDS_PER_MINUTE * MINUTES_PER_HOUR)
        val head = if (hours > 0) {
            String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
        }
        return if (tenths > 0) "$head.$tenths" else head
    }

    /** "1:23", "1:23.5" (m:ss) or "1:02:03" (h:mm:ss). */
    @Suppress("ReturnCount")
    private fun parseColonForm(trimmed: String, original: String): Result<Long> {
        val parts = trimmed.split(":")
        if (parts.size !in COLON_PARTS_MIN_SEC..COLON_PARTS_HOUR_MIN_SEC) return malformed(original)
        if (parts.any { it.isBlank() }) return malformed(original)

        // Only the last field may be fractional; the rest are whole units.
        val leading = parts.dropLast(1).map { it.toIntOrNull() ?: return malformed(original) }
        val seconds = parts.last().toDoubleOrNull() ?: return malformed(original)
        if (leading.any { it < 0 } || seconds < 0) return malformed(original)

        // 90 seconds inside a colon form is ambiguous — the model may have meant
        // 1:30 or 90s. Refuse rather than silently picking one.
        if (seconds >= SECONDS_PER_MINUTE) {
            return Result.failure(
                IllegalArgumentException(
                    "Timecode '$original' has $seconds seconds in a field that holds at most 59. " +
                        "Write '1:30' for ninety seconds, or '90s'."
                )
            )
        }
        if (parts.size == COLON_PARTS_HOUR_MIN_SEC && leading[1] >= MINUTES_PER_HOUR) {
            return Result.failure(
                IllegalArgumentException(
                    "Timecode '$original' has ${leading[1]} minutes in a field that holds at most 59."
                )
            )
        }
        val minutes = leading.last()
        val hours = if (parts.size == COLON_PARTS_HOUR_MIN_SEC) leading.first() else 0
        val totalSeconds = hours.toDouble() * SECONDS_PER_MINUTE * MINUTES_PER_HOUR +
            minutes.toDouble() * SECONDS_PER_MINUTE + seconds
        return Result.success(secondsToMillis(totalSeconds))
    }

    /** "90s", "1m30s", "500ms", "1h2m3s". */
    private fun parseUnitForm(trimmed: String): Result<Long> {
        var millis = 0.0
        for (match in UNIT_PAIR.findAll(trimmed)) {
            val value = match.groupValues[1].toDouble()
            millis += when (match.groupValues[2]) {
                "ms" -> value
                "s" -> value * MS_PER_SECOND
                "m" -> value * MS_PER_SECOND * SECONDS_PER_MINUTE
                else -> value * MS_PER_SECOND * SECONDS_PER_MINUTE * MINUTES_PER_HOUR
            }
        }
        return Result.success(millis.toLong())
    }

    private fun secondsToMillis(seconds: Double): Long = Math.round(seconds * MS_PER_SECOND)

    private fun malformed(spec: String): Result<Long> = Result.failure(
        IllegalArgumentException(
            "Could not read the timecode '$spec'. Use '1:23' (m:ss), '1:02:03' (h:mm:ss), '90s', '1m30s' or " +
                "plain seconds like '12.5'."
        )
    )
}
