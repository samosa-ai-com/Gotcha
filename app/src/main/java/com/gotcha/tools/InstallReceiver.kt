package com.gotcha.tools

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The system installer's answer, delivered as a broadcast.
 *
 * `PackageInstaller` is used instead of a plain `ACTION_VIEW` intent precisely so the app learns
 * the outcome: it needs to re-check the companion's identity and let the Linux-shell screen move
 * on, rather than guessing from a resume.
 */
class InstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // The system confirm dialog. It carries the intent to launch; the terminal
                // status arrives in a later broadcast.
                val confirm = confirmIntent(intent) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(confirm) }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                Log.i(TAG, "Companion installed")
                CompanionInstalls.publish(CompanionInstalls.Outcome.SUCCESS)
            }
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
                Log.w(TAG, "Companion install failed (status=$status): $message")
                CompanionInstalls.publish(
                    CompanionInstalls.Outcome.FAILED,
                    reason = message.take(200)
                )
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun confirmIntent(intent: Intent): Intent? =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_INTENT)
        }

    companion object {
        private const val TAG = "CompanionInstall"
        internal const val ACTION_STATUS = "com.gotcha.shell.INSTALL_STATUS"

        /** The PendingIntent the installer reports to. Mutable: the system fills in extras. */
        internal fun statusIntent(context: Context, sessionId: Int): PendingIntent {
            val intent = Intent(context, InstallReceiver::class.java)
                .setAction(ACTION_STATUS)
                .putExtra(PackageInstaller.EXTRA_SESSION_ID, sessionId)
            val flags = CompanionApkInstaller.pendingFlags()
            return PendingIntent.getBroadcast(context, sessionId, intent, flags)
        }
    }
}

/**
 * The most recent install outcome, so the UI can react to a broadcast that arrived while no
 * screen was listening. Kept in memory: it is a notification, not durable state.
 */
internal object CompanionInstalls {

    enum class Outcome { SUCCESS, FAILED }

    data class Notice(val outcome: Outcome, val reason: String?, val at: Long)

    private const val NOTICE_LIFETIME_MS = 5L * 60 * 1000
    private val pending = MutableStateFlow<Notice?>(null)
    val notices = pending.asStateFlow()

    @Synchronized
    fun publish(outcome: Outcome, reason: String? = null) {
        val notice = Notice(outcome, reason, System.currentTimeMillis())
        pending.value = notice
        Handler(Looper.getMainLooper()).postDelayed({ consume(notice) }, NOTICE_LIFETIME_MS)
    }

    /** Consume only the notice that caused the refresh; a newer broadcast stays visible. */
    @Synchronized
    fun consume(notice: Notice): Notice? {
        if (pending.value !== notice) return null
        pending.value = null
        return notice.takeIf { System.currentTimeMillis() - it.at < NOTICE_LIFETIME_MS }
    }

    @Synchronized
    fun clear() {
        pending.value = null
    }
}
