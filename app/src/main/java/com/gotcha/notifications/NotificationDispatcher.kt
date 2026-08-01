package com.gotcha.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * Pulls the latest envelope from [NotificationApi], filters/dedupes against
 * [NotificationStore], and posts surviving messages to the system tray.
 *
 * Filter rules (see plan §6):
 *  1. id already delivered >= max_deliveries
 *  2. expires_at in the past
 *  3. ttl elapsed since expires_at (or since fetch time, if no expires_at)
 *  4. BuildConfig.VERSION_NAME < min_app_version
 *  5. title or body blank
 *  6. url present but not https
 *
 * The dispatcher is intentionally small; persistence is owned by the store,
 * and posting is split into a single suspend entry point so callers (the
 * activity, the Samosa sign-in flow, a future background worker) don't have
 * to know the details.
 */
class NotificationDispatcher(
    private val context: Context,
    private val api: NotificationApi,
    private val store: NotificationStore,
    private val versionName: String,
    private val openIntent: (url: String) -> Intent = { url ->
        Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        )
    }
) {

    suspend fun fetchAndDeliver(
        now: Long = System.currentTimeMillis()
    ): DispatchResult {
        val result = api.fetch(ifNoneMatch = store.etag().takeIf { it.isNotBlank() })
        return when (result) {
            is NotificationsApiResult.NotModified -> {
                store.setLastFetchedAt(now)
                DispatchResult.UpToDate
            }
            is NotificationsApiResult.Error -> DispatchResult.Failed
            NotificationsApiResult.NetworkError -> DispatchResult.Failed
            is NotificationsApiResult.Parsed -> {
                deliver(result.envelope, now)
            }
        }
    }

    /** Last time a successful fetch happened. Surfaced for the on-resume
     *  freshness gate so a fetch done via Settings → Sync now is honoured. */
    fun lastFetchedAt(): Long = store.lastFetchedAt()

    private fun deliver(envelope: NotificationsEnvelope, now: Long): DispatchResult {
        if (envelope.etag.isNotBlank()) store.setEtag(envelope.etag)
        store.setLastFetchedAt(now)

        val channelEnsured = ensureChannel()
        if (!channelEnsured) return DispatchResult.Skipped("channel")

        if (!hasPostPermission()) return DispatchResult.Skipped("permission")

        var posted = 0
        var suppressed = 0

        envelope.messages.forEach { msg ->
            val reason = skipReason(msg, now)
            if (reason != null) {
                suppressed++
                return@forEach
            }
            try {
                postOne(msg)
                store.recordDelivery(msg.id)
                posted++
            } catch (e: Exception) {
                try { Log.w(TAG, "Failed to post notification id=${msg.id}", e) } catch (_: Throwable) {}
                suppressed++
            }
        }

        store.prune(now)
        return DispatchResult.Delivered(posted = posted, suppressed = suppressed)
    }

    internal fun shouldDeliver(msg: NotificationMessage, now: Long): Boolean =
        skipReason(msg, now) == null

    /** Returns null when the message should be posted; otherwise the reason
     *  for skipping. Surfaced so the dispatcher can log why a message was
     *  dropped, which is useful when investigating "why didn't this fire?". */
    private fun skipReason(msg: NotificationMessage, now: Long): String? {
        val count = store.deliveryCount(msg.id)
        if (count >= msg.maxDeliveries) return "max_deliveries"
        if (msg.title.isBlank() || msg.body.isBlank()) return "blank"
        if (msg.url != null && !msg.url.startsWith("https://")) return "non_https_url"

        if (msg.expiresAt != null) {
            val expiry = parseIsoToMillis(msg.expiresAt)
            if (expiry != null && expiry < now) return "expired"
            if (expiry != null && msg.ttlSeconds != null && (now - expiry) > msg.ttlSeconds * 1000L) {
                return "ttl_after_expiry"
            }
        } else if (msg.ttlSeconds != null && now - store.lastFetchedAt() > msg.ttlSeconds * 1000L) {
            return "ttl_after_fetch"
        }

        if (msg.minAppVersion != null && compareSemver(versionName, msg.minAppVersion) < 0) {
            return "below_min_version"
        }
        return null
    }

    @Suppress("MissingPermission") // hasPostPermission() is enforced in deliver().
    private fun postOne(msg: NotificationMessage) {
        val notifyId = stableNotifyId(msg.id)
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(com.gotcha.R.drawable.ic_notification)
            .setContentTitle(msg.title)
            .setContentText(msg.body)
            .setPriority(msg.androidPriority)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
        if (!msg.url.isNullOrBlank() && msg.url.startsWith("https://")) {
            val intent = openIntent(msg.url)
            val pending = PendingIntent.getActivity(
                context,
                notifyId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.setContentIntent(pending)
        }
        NotificationManagerCompat.from(context).notify(notifyId, builder.build())
    }

    /** Non-negative 31-bit id derived from the message id. Used both as the
     *  system-tray notification id and the PendingIntent request code so the
     *  same message always maps to the same slot (no silent overwrite by a
     *  different message with a hash collision) and so the notification can be
     *  cancelled/replaced deterministically. */
    private fun stableNotifyId(messageId: String): Int =
        messageId.hashCode() and 0x7FFF_FFFF

    private fun ensureChannel(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return false
        val existing = mgr.getNotificationChannel(CHANNEL_ID)
        if (existing != null) return true
        return try {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Server messages",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Updates, tips, and maintenance notices from Gotcha"
            }
            mgr.createNotificationChannel(channel)
            true
        } catch (e: Exception) {
            try { Log.w(TAG, "Failed to create notification channel", e) } catch (_: Throwable) {}
            false
        }
    }

    private fun hasPostPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Compares two semver-style strings `MAJOR.MINOR.PATCH` component by
     * component. Tolerant of trailing build metadata: anything after `+` is
     * ignored. Returns positive if [a] > [b], negative if [a] < [b], 0 on
     * equality.
     */
    private fun compareSemver(a: String, b: String): Int {
        val pa = a.substringBefore('+').split('.').map { it.toIntOrNull() ?: 0 }
        val pb = b.substringBefore('+').split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val ai = pa.getOrElse(i) { 0 }
            val bi = pb.getOrElse(i) { 0 }
            if (ai != bi) return ai - bi
        }
        return 0
    }

    private fun parseIsoToMillis(iso: String): Long? = try {
        // Accept both `…Z` (Instant) and full `±HH:MM` offsets (OffsetDateTime).
        val normalized = iso.trim()
        runCatching { java.time.Instant.parse(normalized).toEpochMilli() }.getOrNull()
            ?: runCatching { java.time.OffsetDateTime.parse(normalized).toInstant().toEpochMilli() }
                .getOrNull()
    } catch (_: Exception) {
        null
    }

    companion object {
        const val CHANNEL_ID = "gotcha_messages"
        private const val TAG = "NotificationDispatcher"
    }
}

sealed interface DispatchResult {
    data class Delivered(val posted: Int, val suppressed: Int) : DispatchResult
    data object UpToDate : DispatchResult
    data object Failed : DispatchResult
    data class Skipped(val reason: String) : DispatchResult
}
