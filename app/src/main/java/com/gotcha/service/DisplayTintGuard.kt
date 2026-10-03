package com.gotcha.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import com.gotcha.data.SettingsRepository
import com.gotcha.tools.RootTool
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Keeps Night Light out of the screenshots the user asks for (#78).
 *
 * On some phones the Night Light tint is baked into what the screenshot API
 * returns, so a ball screenshot or a Screen Lens crop comes out yellow and the
 * model reads colours wrong. The only way around it is to switch Night Light off
 * for the capture and back on after, which is what [withTintSuspended] does.
 *
 * Switching it is a `Settings.Secure` write, so it needs `WRITE_SECURE_SETTINGS`
 * (a one-time `adb pm grant`) or root; without either, and whenever the setting
 * is off or Night Light already is, the capture runs untouched. Only captures the
 * user triggers go through here — a background poll would make the screen blink.
 *
 * Night Light fades rather than switching, so the capture waits [settleMillis]
 * for the fade to finish. A capture that overlaps another shares its suspension
 * and the last one out restores the tint. Before switching Night Light off the
 * guard leaves a [PendingRestore] marker; if the process dies before restoring,
 * [recoverIfInterrupted] turns it back on at the next service start instead of
 * leaving the user's screen blue for good.
 */
class DisplayTintGuard(
    private val access: SecureSettingAccess,
    private val pending: PendingRestore,
    private val isEnabled: () -> Boolean,
    private val settleMillis: Long = SETTLE_MILLIS
) {
    /** Reads and writes one `Settings.Secure` integer; false/null when it can't. */
    interface SecureSettingAccess {
        fun read(key: String): Int?
        fun write(key: String, value: Int): Boolean
    }

    /** Durable "Night Light was switched off by us and not yet restored" flag. */
    interface PendingRestore {
        var isPending: Boolean
    }

    private val mutex = Mutex()
    private var holders = 0

    /** Runs [block] with Night Light off when the guard applies, restoring it after. */
    suspend fun <T> withTintSuspended(block: suspend () -> T): T {
        val suspended = acquire()
        try {
            return block()
        } finally {
            if (suspended) withContext(NonCancellable) { release() }
        }
    }

    /** Restores Night Light if a previous process switched it off and died. */
    suspend fun recoverIfInterrupted() {
        mutex.withLock {
            if (holders == 0 && pending.isPending && access.write(NIGHT_DISPLAY_ACTIVATED, 1)) {
                pending.isPending = false
            }
        }
    }

    private suspend fun acquire(): Boolean = mutex.withLock {
        if (holders > 0) {
            holders++
            return@withLock true
        }
        if (!isEnabled() || access.read(NIGHT_DISPLAY_ACTIVATED) != 1) return@withLock false
        pending.isPending = true
        if (!access.write(NIGHT_DISPLAY_ACTIVATED, 0)) {
            pending.isPending = false
            return@withLock false
        }
        holders = 1
        // Held under the lock so an overlapping capture can't start mid-fade.
        delay(settleMillis)
        true
    }

    private suspend fun release() {
        mutex.withLock {
            holders--
            // A failed restore keeps the marker, so the next start retries it.
            if (holders == 0 && access.write(NIGHT_DISPLAY_ACTIVATED, 1)) {
                pending.isPending = false
            }
        }
    }

    companion object {
        const val NIGHT_DISPLAY_ACTIVATED = "night_display_activated"

        /**
         * AOSP fades Night Light over 3 s (ColorDisplayService's transition
         * duration). OEM builds may differ; tune on a device that shows the tint.
         */
        const val SETTLE_MILLIS = 3_000L

        const val GRANT_COMMAND =
            "adb shell pm grant com.gotcha android.permission.WRITE_SECURE_SETTINGS"

        private const val PREFS = "display_tint_guard"
        private const val KEY_PENDING = "night_light_restore_pending"

        @Volatile private var shared: DisplayTintGuard? = null

        /** The process-wide guard; one instance so overlapping captures share it. */
        fun get(context: Context): DisplayTintGuard =
            shared ?: synchronized(this) {
                shared ?: create(context.applicationContext).also { shared = it }
            }

        /** True when Gotcha may write secure settings without root. */
        fun hasSecureSettingsGrant(context: Context): Boolean =
            context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
                PackageManager.PERMISSION_GRANTED

        private fun create(context: Context): DisplayTintGuard {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            // commit, not apply: the marker has to be on disk before the write it
            // guards, or a kill in between leaves nothing to recover from.
            val pending = object : PendingRestore {
                override var isPending: Boolean
                    get() = prefs.getBoolean(KEY_PENDING, false)
                    set(value) { prefs.edit().putBoolean(KEY_PENDING, value).commit() }
            }
            return DisplayTintGuard(
                access = AndroidSecureSettings(context),
                pending = pending,
                isEnabled = { SettingsRepository(context).load().pauseNightLightForScreenshots }
            )
        }
    }
}

/** Direct `Settings.Secure` access with the ADB grant, root as the fallback. */
private class AndroidSecureSettings(private val context: Context) : DisplayTintGuard.SecureSettingAccess {
    private val rootTool by lazy { RootTool() }

    override fun read(key: String): Int? =
        runCatching { Settings.Secure.getInt(context.contentResolver, key) }.getOrNull()
            ?: rootTool.readSecureSetting("secure", key)?.toIntOrNull()

    override fun write(key: String, value: Int): Boolean {
        if (DisplayTintGuard.hasSecureSettingsGrant(context) &&
            runCatching { Settings.Secure.putInt(context.contentResolver, key, value) }.getOrDefault(false)
        ) {
            return true
        }
        return rootTool.writeSecureSetting("secure", key, value.toString()).success
    }
}
