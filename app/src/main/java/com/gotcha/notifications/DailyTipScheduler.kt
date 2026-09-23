package com.gotcha.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.gotcha.data.ChatHistoryRepository
import com.gotcha.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.ZoneId

/**
 * Keeps one inexact alarm armed for the next daily tip (issue #101).
 *
 * The alarm stays armed whether or not tips are on: [DailyTipAlarmReceiver] checks
 * the setting when it fires, so switching tips on or off (from Settings or
 * through the assistant) needs no rescheduling. Only a new time does.
 */
object DailyTipScheduler {

    /** How late Android may deliver the tip; a window keeps it off the exact-alarm permission. */
    private const val WINDOW_MS = 30L * 60L * 1000L

    /** Arms the alarm for the next [minuteOfDay], replacing any earlier one. */
    fun schedule(context: Context, minuteOfDay: Int) {
        val alarm = context.getSystemService(AlarmManager::class.java) ?: return
        val at = nextDailyTipAt(System.currentTimeMillis(), minuteOfDay, ZoneId.systemDefault())
        alarm.setWindow(AlarmManager.RTC_WAKEUP, at, WINDOW_MS, fireIntent(context))
    }

    /** Arms the alarm for the stored time. Reads encrypted prefs, so call it off the main thread. */
    fun schedule(context: Context) {
        schedule(context, SettingsRepository(context).load().dailyTipMinuteOfDay)
    }

    private fun fireIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, DailyTipAlarmReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
}

/**
 * Re-arms the alarm after a reboot, an app update or a clock change — none of
 * which an alarm survives at the right time. Exported only because those
 * broadcasts come from the system; it never posts a tip itself.
 */
class DailyTipReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in RESCHEDULE_ACTIONS) return
        val app = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                DailyTipScheduler.schedule(app)
            } catch (e: Exception) {
                Log.w(TAG, "Daily tip reschedule failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "DailyTipReceiver"
        val RESCHEDULE_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_TIME_CHANGED
        )
    }
}

/** Posts the day's tip when the alarm fires, then arms tomorrow's. Not exported. */
class DailyTipAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Re-arm first, so a failure below can't end the daily chain.
                DailyTipScheduler.schedule(app)
                postDailyTip(app)
            } catch (e: Exception) {
                Log.w(TAG, "Daily tip failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "DailyTipAlarmReceiver"
    }
}

private const val KEY_RECENT_TIPS = "daily_tip_recent_ids"

/**
 * Posts today's tip unless tips are off, notifications are blocked or Gotcha
 * has already been used today. Returns the tip posted, or null.
 */
internal suspend fun postDailyTip(context: Context, now: Long = System.currentTimeMillis()): DailyTip? {
    val repo = SettingsRepository(context)
    if (!repo.load().dailyTipsEnabled) return null
    val notifier = DailyTipNotifier(context)
    if (!notifier.canPost()) return null

    // The chats on disk are the usage record: which tools have run, and when
    // Gotcha was last used. Seeded samples are demos, not the user's own use.
    val runs = ChatHistoryRepository(context).listSessions()
        .filterNot { it.isSample }
        .flatMap { it.runSummaries }
    // Someone who has already used Gotcha today doesn't need a nudge.
    if (usedToday(runs.maxOfOrNull { it.endedAt }, now, ZoneId.systemDefault())) return null

    val recent = repo.prefs.getString(KEY_RECENT_TIPS, null)
        ?.split(',')?.filter { it.isNotBlank() }.orEmpty()
    val usedTools = runs.flatMap { run -> run.toolCalls.map { it.name } }.toSet()
    val tip = pickDailyTip(DAILY_TIPS, recent, usedTools) ?: return null
    if (!notifier.notify(tip)) return null
    repo.prefs.edit()
        .putString(KEY_RECENT_TIPS, rememberShownTip(recent, tip.id).joinToString(","))
        // commit, not apply: the process may go as soon as the receiver finishes.
        .commit()
    return tip
}
