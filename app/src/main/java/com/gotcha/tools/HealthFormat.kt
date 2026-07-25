package com.gotcha.tools

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToLong

/**
 * Pure formatting and window arithmetic for [HealthTool]. Kept separate from the
 * Health Connect client so it can be unit-tested on the JVM — the client itself
 * needs a device with a Health Connect provider.
 */
object HealthFormat {

    private const val METRES_PER_KM = 1000.0
    private const val MINUTES_PER_HOUR = 60L
    private const val MAX_DAYS = 365

    private val dayFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault())
    private val minuteFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

    /** Clamps the requested lookback to a sane range; defaults to a week. */
    fun days(requested: Int?): Int = (requested ?: 7).coerceIn(1, MAX_DAYS)

    /** `[now - days, now]`, the window every summary is computed over. */
    fun window(now: Instant, days: Int): Pair<Instant, Instant> =
        now.minus(Duration.ofDays(days.toLong())) to now

    fun day(instant: Instant): String = dayFormatter.format(instant)

    fun minute(instant: Instant): String = minuteFormatter.format(instant)

    /** "2 h 35 min" / "45 min" — sleep and exercise durations read better than raw minutes. */
    fun duration(duration: Duration): String {
        val totalMinutes = duration.toMinutes()
        if (totalMinutes < MINUTES_PER_HOUR) return "$totalMinutes min"
        val hours = totalMinutes / MINUTES_PER_HOUR
        val minutes = totalMinutes % MINUTES_PER_HOUR
        return if (minutes == 0L) "$hours h" else "$hours h $minutes min"
    }

    fun metresAsKm(metres: Double): String =
        String.format(Locale.US, "%.2f km", metres / METRES_PER_KM)

    fun kilograms(kg: Double): String = String.format(Locale.US, "%.1f kg", kg)

    fun steps(count: Long): String = String.format(Locale.US, "%,d", count)

    /** Rounds a per-day average for display; null input means "no data in the window". */
    fun perDay(total: Long?, days: Int): String =
        total?.let { "${steps((it.toDouble() / days).roundToLong())}/day" } ?: "—"

    /**
     * Assembles the summary text. Entries whose value is null are omitted, so a
     * user who only tracks steps does not get a wall of "no data" lines.
     */
    fun summary(days: Int, entries: List<Pair<String, String?>>): String {
        val present = entries.filter { it.second != null }
        if (present.isEmpty()) {
            return "Health Connect has no data for the last $days day(s). The user may not have " +
                "granted the permissions, or no app is writing health data on this device."
        }
        return "Health summary for the last $days day(s):\n" +
            present.joinToString("\n") { (label, value) -> "  $label: $value" }
    }
}
