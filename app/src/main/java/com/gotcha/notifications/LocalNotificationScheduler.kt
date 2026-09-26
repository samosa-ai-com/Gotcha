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
 * Keeps the two alarms behind Gotcha's proactive notifications armed (issues
 * #100, #101): one at the daily tip time, and a check every few hours for
 * reminders. Both stay armed whatever the settings say, because the check reads
 * them when it runs, so switching things on or off needs no rescheduling. Only
 * a new tip time does.
 */
object LocalNotificationScheduler {

    /** How late Android may deliver the tip; a window keeps it off the exact-alarm permission. */
    private const val TIP_WINDOW_MS = 30L * 60L * 1000L

    /** How often reminders are checked for. Inexact, so Android batches it with other wake-ups. */
    private const val CHECK_INTERVAL_MS = 4L * HOUR_MS

    /** Arms the tip alarm for the next [tipMinuteOfDay], replacing any earlier one. */
    fun scheduleTip(context: Context, tipMinuteOfDay: Int) {
        val alarm = context.getSystemService(AlarmManager::class.java) ?: return
        val at = nextDailyTipAt(System.currentTimeMillis(), tipMinuteOfDay, ZoneId.systemDefault())
        alarm.setWindow(
            AlarmManager.RTC_WAKEUP,
            at,
            TIP_WINDOW_MS,
            broadcast(context, DailyTipAlarmReceiver::class.java)
        )
    }

    /** Arms both alarms from the stored settings. Reads encrypted prefs, so call it off the main thread. */
    fun scheduleAll(context: Context) {
        scheduleTip(context, SettingsRepository(context).load().dailyTipMinuteOfDay)
        val alarm = context.getSystemService(AlarmManager::class.java) ?: return
        alarm.setInexactRepeating(
            AlarmManager.RTC_WAKEUP,
            System.currentTimeMillis() + CHECK_INTERVAL_MS,
            CHECK_INTERVAL_MS,
            broadcast(context, ReminderCheckReceiver::class.java)
        )
    }

    private fun broadcast(context: Context, receiver: Class<out BroadcastReceiver>): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, receiver),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
}

/** Runs [block] off the main thread for a receiver, keeping it alive until done. */
private fun BroadcastReceiver.runAsync(tag: String, block: suspend () -> Unit) {
    val pending = goAsync()
    CoroutineScope(Dispatchers.IO).launch {
        try {
            block()
        } catch (e: Exception) {
            Log.w(tag, "Local notification work failed", e)
        } finally {
            pending.finish()
        }
    }
}

/**
 * Re-arms the alarms after a reboot, an app update or a clock change — none of
 * which an alarm survives at the right time. Exported only because those
 * broadcasts come from the system; it never posts anything itself.
 */
class NotificationRescheduleReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in RESCHEDULE_ACTIONS) return
        val app = context.applicationContext
        runAsync(TAG) { LocalNotificationScheduler.scheduleAll(app) }
    }

    private companion object {
        const val TAG = "NotificationReschedule"
        val RESCHEDULE_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_TIME_CHANGED
        )
    }
}

/** The daily tip time: the best notification of the day, a reminder or else the tip. Not exported. */
class DailyTipAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        runAsync("DailyTipAlarm") {
            // Re-arm first, so a failure below can't end the daily chain.
            LocalNotificationScheduler.scheduleTip(app, SettingsRepository(app).load().dailyTipMinuteOfDay)
            runLocalNotificationCheck(app, includeTip = true)
        }
    }
}

/** The periodic reminder check. Not exported. */
class ReminderCheckReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        runAsync("ReminderCheck") { runLocalNotificationCheck(app, includeTip = false) }
    }
}

private const val KEY_RECENT_TIPS = "daily_tip_recent_ids"

/**
 * Decides whether to send a proactive notification now, and sends at most one.
 * Reminders come first (an unfinished chat, a routine that is due, a quiet
 * spell), then, at the tip time, the day's tip. Each has to pass the rules in
 * [blockReason]: the switches, quiet hours, deduplication, the cooldown and the
 * daily cap. Everything is decided on the phone from the chats already saved
 * there. Returns what was sent, or null.
 */
internal suspend fun runLocalNotificationCheck(
    context: Context,
    now: Long = System.currentTimeMillis(),
    includeTip: Boolean
): LocalCandidate? {
    val repo = SettingsRepository(context)
    val settings = repo.load()
    if (!settings.localNotificationsEnabled) return null
    val notifier = LocalNotifier(context)
    if (!notifier.canPost()) return null
    val store = LocalNotificationStore(context)
    val zone = ZoneId.systemDefault()

    // Seeded samples are demos, not the user's own use.
    val sessions = ChatHistoryRepository(context).listSessions().filterNot { it.isSample }
    val chats = sessions.map { session ->
        ChatDigest(
            id = session.id,
            title = session.title,
            sensitive = store.isChatSensitive(session.id, session.personaId),
            runs = session.runSummaries
        )
    }
    val lastOpenedAt = store.lastOpenedAt()

    var tip: DailyTip? = null
    val candidates = buildList {
        // Someone using Gotcha right now needs no reminder.
        if (now - lastOpenedAt >= RECENTLY_OPENED_MS) {
            addAll(
                behaviorCandidates(
                    chats,
                    lastOpenedAt,
                    settings.inactivityDays,
                    settings.notificationsMentionChats,
                    now,
                    zone
                )
            )
        }
        // Nor, on a day they have already used it, a tip.
        val lastRun = sessions.flatMap { it.runSummaries }.maxOfOrNull { it.endedAt }
        if (includeTip && !usedToday(lastRun, now, zone)) {
            val usedTools = sessions.flatMap { s -> s.runSummaries.flatMap { run -> run.toolCalls.map { it.name } } }
            tip = pickDailyTip(DAILY_TIPS, recentTips(repo), usedTools.toSet())
            tip?.let { add(tipCandidate(it, now, zone)) }
        }
    }

    val sent = store.sent()
    val chosen = candidates.firstOrNull { blockReason(it, settings, sent, now, zone) == null } ?: return null
    if (!notifier.post(chosen, store)) return null
    store.recordSent(chosen, now)
    val shownTip = tip
    if (chosen.category == NotificationCategory.DAILY_TIP && shownTip != null) {
        repo.prefs.edit()
            .putString(KEY_RECENT_TIPS, rememberShownTip(recentTips(repo), shownTip.id).joinToString(","))
            // commit, not apply: the process may go as soon as the receiver finishes.
            .commit()
    }
    return chosen
}

private fun recentTips(repo: SettingsRepository): List<String> =
    repo.prefs.getString(KEY_RECENT_TIPS, null)?.split(',')?.filter { it.isNotBlank() }.orEmpty()
