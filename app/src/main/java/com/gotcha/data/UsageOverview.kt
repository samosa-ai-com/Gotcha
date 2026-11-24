package com.gotcha.data

import android.content.Context
import com.gotcha.GotchaApp
import com.gotcha.scheduled.ScheduledTask
import com.gotcha.scheduled.ScheduledTaskStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * What Gotcha has recorded about its own use on this phone, for the "Your usage"
 * section of the Privacy page.
 *
 * Read from files the app already keeps (chat and call histories, the crash log,
 * scheduled tasks) at the moment the page opens. Nothing here is
 * stored or sent anywhere: it shows the user what is on the phone.
 */
data class UsageOverview(
    /** Chat and call aggregates, the same figures the feedback form offers to include. */
    val stats: FeedbackStats,
    /** Runs that started in the [RECENT_DAYS] before the page opened. */
    val recentRuns: Int,
    /** Epoch millis of the earliest run still on the phone, or null with no runs. */
    val firstRunAt: Long?,
    /** Scheduled tasks the user made or switched on (the paused presets do not count). */
    val activeScheduledTasks: Int,
    /** Crashes in `crash.log`, which keeps the most recent 50. */
    val crashes: Int
) {
    companion object {
        const val RECENT_DAYS = 7
    }
}

/** Reads every source. Touches the disk and encrypted prefs, so it runs on IO. */
suspend fun loadUsageOverview(
    context: Context,
    now: Long = System.currentTimeMillis()
): UsageOverview = withContext(Dispatchers.IO) {
    val chats = ChatHistoryRepository(context).listSessions()
    val calls = ChatHistoryRepository(context, "calls").listSessions()
    val tasks = runCatching { ScheduledTaskStore(context).all() }.getOrDefault(emptyList())
    buildUsageOverview(
        chatSessions = chats,
        callSessions = calls,
        tasks = tasks,
        crashLog = readOrEmpty(File(context.filesDir, GotchaApp.CRASH_LOG_FILE)),
        now = now
    )
}

internal fun buildUsageOverview(
    chatSessions: List<ChatSession>,
    callSessions: List<ChatSession>,
    tasks: List<ScheduledTask>,
    crashLog: String,
    now: Long
): UsageOverview {
    // The chats seeded on first run show what Gotcha can do; they are not the user's use.
    val chats = chatSessions.filterNot { it.isSample }
    val calls = callSessions.filterNot { it.isSample }
    val runStarts = (chats + calls).flatMap { it.runSummaries }.map { it.startedAt }
    val since = now - UsageOverview.RECENT_DAYS * DAY_MS
    return UsageOverview(
        stats = computeFeedbackStats(chats, calls),
        recentRuns = runStarts.count { it in since..now },
        firstRunAt = runStarts.filter { it > 0L }.minOrNull(),
        activeScheduledTasks = tasks.count { it.enabled || it.presetId == null },
        crashes = countCrashEntries(crashLog)
    )
}

/** Entries in `crash.log`, split the way [GotchaApp] writes them. */
internal fun countCrashEntries(text: String): Int =
    text.split(GotchaApp.CRASH_ENTRY_SEPARATOR).count { it.isNotBlank() }

private fun readOrEmpty(file: File): String =
    runCatching { if (file.isFile) file.readText() else "" }.getOrDefault("")

private const val DAY_MS = 24L * 60L * 60L * 1000L
