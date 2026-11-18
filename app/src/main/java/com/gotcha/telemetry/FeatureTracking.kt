package com.gotcha.telemetry

import android.app.AlarmManager
import android.app.AppOpsManager
import android.app.NotificationManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.gotcha.agent.skills.SkillPromptBuilder
import com.gotcha.llm.ChatMessage
import com.gotcha.service.GotchaDeviceAdminReceiver
import com.gotcha.tools.HealthPermissionState
import com.gotcha.ui.isAccessibilityGranted
import com.gotcha.ui.isOverlayGranted

/** `skill_used` from the two places a skill reaches the model. */
object SkillTelemetry {

    /**
     * The skills listed in [message], each counted once per run: the summary is
     * sent again on every round, and [seen] remembers which were counted.
     */
    fun trackOffered(message: ChatMessage, seen: MutableSet<String>) {
        SkillPromptBuilder.offeredSkillIds(message)
            .filter { seen.add(it) }
            .forEach { Telemetry.track(FeatureEvents.skillUsed(it, SkillVia.OFFERED)) }
    }

    /** The model asked for these skills' instructions with `search_skills`. */
    fun trackFetched(skillIds: List<String>) {
        skillIds.forEach { Telemetry.track(FeatureEvents.skillUsed(it, SkillVia.FETCHED)) }
    }
}

/**
 * Notices permissions being granted or taken away, wherever that happened.
 *
 * Most of Gotcha's permissions are granted in Android Settings (Accessibility,
 * overlay, notification access…), where no callback reaches the app, so this
 * compares the set that is granted now with the set seen last time and reports
 * the difference. The first look after usage data is turned on only records
 * the starting point: what was granted before is not an event.
 */
class PermissionWatch(
    private val prefs: SharedPreferences,
    private val granted: () -> Set<PermissionName>
) {
    /** The `permission` events since the last call, and remembers the new state. */
    fun changes(): List<TelemetryEvent> {
        val now = granted()
        val before = prefs.getStringSet(KEY_GRANTED, null)
        prefs.edit().putStringSet(KEY_GRANTED, now.map { it.wire }.toSet()).apply()
        if (before == null) return emptyList()
        return PermissionName.entries.mapNotNull { name ->
            val wasGranted = name.wire in before
            val isGranted = name in now
            if (wasGranted == isGranted) null else FeatureEvents.permission(name, isGranted)
        }
    }

    /** Forgets the starting point, so turning usage data on again starts afresh. */
    fun reset() {
        prefs.edit().remove(KEY_GRANTED).apply()
    }

    companion object {
        internal const val KEY_GRANTED = "permissions_granted"

        /** What is granted on this phone right now. */
        fun grantedOn(context: Context): Set<PermissionName> {
            // One check failing (a missing system service on an odd build) counts as not granted.
            fun safely(name: PermissionName) = runCatching { isGranted(context, name) }.getOrDefault(false)
            return PermissionName.entries.filterTo(mutableSetOf()) { safely(it) }
        }

        @Suppress("CyclomaticComplexMethod")
        private fun isGranted(c: Context, name: PermissionName): Boolean = when (name) {
            PermissionName.MIC -> has(c, android.Manifest.permission.RECORD_AUDIO)
            PermissionName.CAMERA -> has(c, android.Manifest.permission.CAMERA)
            PermissionName.LOCATION -> has(c, android.Manifest.permission.ACCESS_FINE_LOCATION)
            PermissionName.CONTACTS -> has(c, android.Manifest.permission.READ_CONTACTS)
            PermissionName.CALENDAR -> has(c, android.Manifest.permission.READ_CALENDAR)
            PermissionName.PHONE -> has(c, android.Manifest.permission.CALL_PHONE)
            PermissionName.SMS -> has(c, android.Manifest.permission.SEND_SMS)
            PermissionName.READ_SMS -> has(c, android.Manifest.permission.READ_SMS)
            PermissionName.CALL_LOG -> has(c, android.Manifest.permission.READ_CALL_LOG)
            PermissionName.PHOTOS -> has(
                c,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    android.Manifest.permission.READ_MEDIA_IMAGES
                } else {
                    android.Manifest.permission.READ_EXTERNAL_STORAGE
                }
            )
            PermissionName.NOTIFICATIONS ->
                Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    has(c, android.Manifest.permission.POST_NOTIFICATIONS)
            PermissionName.ALL_FILES -> Environment.isExternalStorageManager()
            PermissionName.WRITE_SETTINGS -> Settings.System.canWrite(c)
            PermissionName.DND ->
                c.getSystemService(NotificationManager::class.java)?.isNotificationPolicyAccessGranted == true
            PermissionName.USAGE_ACCESS -> c.getSystemService(AppOpsManager::class.java)?.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                c.packageName
            ) == AppOpsManager.MODE_ALLOWED
            PermissionName.DEVICE_ADMIN -> c.getSystemService(DevicePolicyManager::class.java)
                ?.isAdminActive(ComponentName(c, GotchaDeviceAdminReceiver::class.java)) == true
            PermissionName.ACCESSIBILITY -> isAccessibilityGranted(c)
            PermissionName.NOTIFICATION_LISTENER ->
                Settings.Secure.getString(c.contentResolver, "enabled_notification_listeners")
                    .orEmpty().contains(c.packageName, ignoreCase = true)
            PermissionName.OVERLAY -> isOverlayGranted(c)
            PermissionName.EXACT_ALARM ->
                Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                    c.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true
            PermissionName.BATTERY ->
                c.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(c.packageName) == true
            PermissionName.HEALTH -> HealthPermissionState.isGranted()
        }

        private fun has(c: Context, permission: String) =
            ContextCompat.checkSelfPermission(c, permission) == PackageManager.PERMISSION_GRANTED
    }
}
