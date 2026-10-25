package com.gotcha.updater

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.gotcha.BuildConfig
import com.gotcha.R
import com.gotcha.data.SettingsRepository
import com.gotcha.notifications.ChatCompletionNotifier
import com.gotcha.notifications.LocalNotificationStore
import com.gotcha.notifications.LocalNotifier
import com.gotcha.notifications.LocalNotifier.Companion.putTargetExtras
import com.gotcha.notifications.NotificationCategory
import com.gotcha.notifications.NotificationTarget
import com.gotcha.notifications.inQuietHours
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.ZoneId

/** How often the background check fetches update.json: about twice a day (issue #126). */
internal const val UPDATE_CHECK_INTERVAL_MS = 12L * 60L * 60L * 1000L

/**
 * Whether [remote] deserves a notification: it is newer than what is installed
 * and than the last version already announced, and its download is one
 * [AppUpdateManager.downloadUpdate] would accept. Announcing a release the
 * About screen would then refuse to download helps nobody.
 */
internal fun shouldNotifyUpdate(
    installedVersionCode: Int,
    remote: AppUpdateInfo,
    lastNotifiedVersionCode: Int,
    trustedDownload: Boolean
): Boolean = trustedDownload &&
    remote.versionCode > installedVersionCode &&
    remote.versionCode > lastNotifiedVersionCode

/** True once [UPDATE_CHECK_INTERVAL_MS] has passed, or when the clock has gone back past the last check. */
internal fun updateCheckDue(now: Long, lastCheckedAt: Long): Boolean =
    now < lastCheckedAt || now - lastCheckedAt >= UPDATE_CHECK_INTERVAL_MS

/** What one background check did, for tests and the log. */
enum class UpdateCheckOutcome { OFF, CANNOT_POST, NOT_DUE, OFFLINE, QUIET_HOURS, FAILED, UP_TO_DATE, NOTIFIED }

/**
 * App start and the reminder alarm can both start a check. Without this, two
 * overlapping checks read the same timestamps, fetch the same manifest, and
 * each announce the same version.
 */
private val updateCheckLock = Mutex()

/** When the check last ran and which version was last announced. Not personal, so not encrypted. */
internal class UpdateCheckStore(private val prefs: SharedPreferences) {

    constructor(context: Context) : this(context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE))

    var lastCheckedAt: Long
        get() = prefs.getLong(KEY_LAST_CHECKED_AT, 0L)
        set(value) {
            // commit, not apply: the receiver's process may go as soon as it finishes.
            prefs.edit().putLong(KEY_LAST_CHECKED_AT, value).commit()
        }

    var lastNotifiedVersionCode: Int
        get() = prefs.getInt(KEY_LAST_NOTIFIED_VERSION, 0)
        set(value) {
            prefs.edit().putInt(KEY_LAST_NOTIFIED_VERSION, value).commit()
        }

    private companion object {
        const val PREFS_FILE = "gotcha_update_check"
        const val KEY_LAST_CHECKED_AT = "last_checked_at"
        const val KEY_LAST_NOTIFIED_VERSION = "last_notified_version_code"
    }
}

/**
 * The background half of the in-app updater (issue #126): fetches update.json
 * at most every [UPDATE_CHECK_INTERVAL_MS] and posts one notification per new
 * version. It rides the reminder-check alarm and runs on app start, so it
 * needs no scheduler of its own.
 *
 * Quiet by design: off, offline, quiet hours or a failed fetch post nothing and
 * leave the next check to try again. Only a check that got an answer counts
 * towards the interval, and only a posted notification marks its version as
 * announced, so a blocked or failed post is retried rather than lost.
 */
internal suspend fun runUpdateCheck(
    context: Context,
    now: Long = System.currentTimeMillis(),
    manager: AppUpdateManager = AppUpdateManager.shared,
    manifestUrl: String = AppUpdateManager.DEFAULT_UPDATE_URL,
    installedVersionCode: Int = BuildConfig.VERSION_CODE,
    zone: ZoneId = ZoneId.systemDefault(),
    isOnline: () -> Boolean = { hasValidatedNetwork(context) }
): UpdateCheckOutcome = updateCheckLock.withLock {
    val settings = SettingsRepository(context).load()
    if (!settings.updateNotificationsEnabled) return UpdateCheckOutcome.OFF
    val notifier = UpdateNotifier(context)
    // Includes the updates channel: a post to a muted one is silently dropped.
    if (!notifier.canPost()) return UpdateCheckOutcome.CANNOT_POST
    val store = UpdateCheckStore(context)
    if (!updateCheckDue(now, store.lastCheckedAt)) return UpdateCheckOutcome.NOT_DUE
    if (!isOnline()) return UpdateCheckOutcome.OFFLINE
    val local = Instant.ofEpochMilli(now).atZone(zone)
    if (settings.quietHoursEnabled &&
        inQuietHours(local.hour * 60 + local.minute, settings.quietHoursStartMinute, settings.quietHoursEndMinute)
    ) {
        return UpdateCheckOutcome.QUIET_HOURS
    }

    val status = manager.checkForUpdate(
        currentVersionCode = installedVersionCode,
        manifestUrl = manifestUrl
    )
    if (status is UpdateStatus.Error) return UpdateCheckOutcome.FAILED
    store.lastCheckedAt = now
    val info = (status as? UpdateStatus.Available)?.info ?: return UpdateCheckOutcome.UP_TO_DATE
    val notify = shouldNotifyUpdate(
        installedVersionCode,
        info,
        store.lastNotifiedVersionCode,
        manager.isTrustedDownloadUrl(info.downloadUrl)
    )
    if (!notify || !notifier.post(info, LocalNotificationStore(context))) return UpdateCheckOutcome.UP_TO_DATE
    store.lastNotifiedVersionCode = info.versionCode
    UpdateCheckOutcome.NOTIFIED
}

/** An alarm has no network constraint, so the check asks for itself. */
private fun hasValidatedNetwork(context: Context): Boolean {
    val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
    val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}

/**
 * Posts the "new version" notification on a channel of its own, so it can be
 * muted from Android's settings without silencing tips or reminders, and files
 * it in Gotcha's inbox. A tap opens Settings ▸ About, which checks again and
 * offers the download.
 */
class UpdateNotifier(private val context: Context) {

    /**
     * The app may post and the user has not muted this channel. Android drops a
     * post to a muted channel without a word, so posting there would mark the
     * version announced and lose it. A channel not created yet is created on
     * the first post with default importance, so it counts as on.
     */
    fun canPost(): Boolean {
        if (!ChatCompletionNotifier(context).canPost()) return false
        val channel = context.getSystemService(NotificationManager::class.java)
            ?.getNotificationChannel(CHANNEL_ID) ?: return true
        return channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    @Suppress("MissingPermission") // canPost() checks it.
    fun post(info: AppUpdateInfo, inbox: LocalNotificationStore): Boolean {
        if (!canPost()) return false
        ensureChannel()
        val title = context.getString(R.string.update_notification_title, info.versionName)
        val body = context.getString(R.string.update_notification_body)
        val target = NotificationTarget.Update
        val entryId = inbox.addEntry(NotificationCategory.APP_UPDATE, title, body, target)
        val notifyId = LocalNotifier.notificationId(NotificationCategory.APP_UPDATE)
        val intent = Intent(context, com.gotcha.MainActivity::class.java).apply {
            action = LocalNotifier.ACTION_OPEN_LOCAL_NOTIFICATION
            putExtra(LocalNotificationStore.EXTRA_INBOX_ENTRY_ID, entryId)
            putTargetExtras(target)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    notifyId,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()
        NotificationManagerCompat.from(context).notify(notifyId, notification)
        return true
    }

    private fun ensureChannel() {
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        mgr.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.channel_app_updates),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = context.getString(R.string.channel_app_updates_description) }
        )
    }

    companion object {
        const val CHANNEL_ID = "gotcha_app_updates"
    }
}
