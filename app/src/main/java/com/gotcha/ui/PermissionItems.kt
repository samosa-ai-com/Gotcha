package com.gotcha.ui

import android.app.AppOpsManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.gotcha.service.GotchaDeviceAdminReceiver
import com.gotcha.tools.HealthPermissionState
import com.gotcha.tools.ToolResult

data class PermissionGroup(
    val name: String,
    val items: List<PermissionItem>
)

data class PermissionItem(
    val name: String,
    val description: String,
    val androidPermission: String?,
    val specialMarker: String?,
    val isGranted: (Context) -> Boolean,
    /** Additional runtime permissions requested together with [androidPermission] (e.g. the
     *  matching WRITE permission for a read/write pair). Requested in the same system dialog. */
    val extraPermissions: List<String> = emptyList(),
    /**
     * Whether to show the row at all. Defaults to always. Needed for permissions another app
     * declares: until that app is installed the permission does not exist, so Android denies the
     * request without a dialog — a dead toggle, and one that would keep its group flagged as
     * "has something ungranted" forever.
     */
    val isRelevant: (Context) -> Boolean = { true }
)

// Declarative catalog of every permission the app can request; length is inherent.
@Suppress("LongMethod")
fun allPermissionGroups(): List<PermissionGroup> = listOf(
    PermissionGroup(
        "Communications",
        listOf(
            PermissionItem(
                "Phone",
                "Place calls directly",
                android.Manifest.permission.CALL_PHONE,
                null,
                { c -> checkPerm(c, android.Manifest.permission.CALL_PHONE) }
            ),
            PermissionItem(
                "SMS",
                "Send text messages",
                android.Manifest.permission.SEND_SMS,
                null,
                { c -> checkPerm(c, android.Manifest.permission.SEND_SMS) }
            ),
            PermissionItem(
                "Read SMS",
                "Read inbox messages",
                android.Manifest.permission.READ_SMS,
                null,
                { c -> checkPerm(c, android.Manifest.permission.READ_SMS) }
            ),
            PermissionItem(
                "Call Log",
                "Read recent call history",
                android.Manifest.permission.READ_CALL_LOG,
                null,
                { c -> checkPerm(c, android.Manifest.permission.READ_CALL_LOG) }
            )
        )
    ),
    PermissionGroup(
        "Contacts",
        listOf(
            PermissionItem(
                "Contacts",
                "Read and create contacts",
                android.Manifest.permission.READ_CONTACTS,
                null,
                { c ->
                    checkPerm(c, android.Manifest.permission.READ_CONTACTS) &&
                        checkPerm(c, android.Manifest.permission.WRITE_CONTACTS)
                },
                extraPermissions = listOf(android.Manifest.permission.WRITE_CONTACTS)
            )
        )
    ),
    PermissionGroup(
        "Calendar",
        listOf(
            PermissionItem(
                "Calendar",
                "Read and create calendar events",
                android.Manifest.permission.READ_CALENDAR,
                null,
                { c ->
                    checkPerm(c, android.Manifest.permission.READ_CALENDAR) &&
                        checkPerm(c, android.Manifest.permission.WRITE_CALENDAR)
                },
                extraPermissions = listOf(android.Manifest.permission.WRITE_CALENDAR)
            )
        )
    ),
    PermissionGroup(
        "Media & Storage",
        listOf(
            PermissionItem(
                "Camera",
                "Take photos",
                android.Manifest.permission.CAMERA,
                null,
                { c -> checkPerm(c, android.Manifest.permission.CAMERA) }
            ),
            PermissionItem(
                "Microphone",
                "Record audio",
                android.Manifest.permission.RECORD_AUDIO,
                null,
                { c -> checkPerm(c, android.Manifest.permission.RECORD_AUDIO) }
            ),
            PermissionItem(
                "Storage Read",
                "Read files from shared storage",
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    android.Manifest.permission.READ_MEDIA_IMAGES
                } else {
                    android.Manifest.permission.READ_EXTERNAL_STORAGE
                },
                null,
                { c ->
                    val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        android.Manifest.permission.READ_MEDIA_IMAGES
                    } else {
                        android.Manifest.permission.READ_EXTERNAL_STORAGE
                    }
                    checkPerm(c, perm)
                }
            ),
            PermissionItem(
                "Storage Write",
                "Write files to shared storage",
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                null,
                // API 30+ uses MANAGE_EXTERNAL_STORAGE instead; nothing to request here.
                { true }
            ),
            PermissionItem(
                "All Files Access",
                "Full access to all files on device",
                null,
                "special:all_files_access",
                { Environment.isExternalStorageManager() }
            )
        )
    ),
    PermissionGroup(
        "Location",
        listOf(
            PermissionItem(
                "Location",
                "Get device location",
                android.Manifest.permission.ACCESS_FINE_LOCATION,
                null,
                { c -> checkPerm(c, android.Manifest.permission.ACCESS_FINE_LOCATION) }
            )
        )
    ),
    PermissionGroup(
        "Device Control",
        listOf(
            PermissionItem(
                "Write Settings",
                "Modify system settings (brightness)",
                null,
                "special:write_settings",
                { c -> Settings.System.canWrite(c) }
            ),
            PermissionItem(
                "Do Not Disturb",
                "Silence/vibrate mode and DND",
                null,
                "special:dnd_access",
                { c ->
                    val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
                    nm?.isNotificationPolicyAccessGranted ?: false
                }
            ),
            PermissionItem(
                "Usage Access",
                "Read app usage and data stats",
                null,
                "special:usage_access",
                { c ->
                    val appOps = c.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
                    appOps?.unsafeCheckOpNoThrow(
                        AppOpsManager.OPSTR_GET_USAGE_STATS,
                        android.os.Process.myUid(), c.packageName
                    ) == AppOpsManager.MODE_ALLOWED
                }
            ),
            // A runtime permission rather than a special marker, so the standard toggle can
            // request it. Termux declares it, so it only exists once Termux is installed — hence
            // isRelevant. The allow-external-apps half has to be done inside Termux either way;
            // the Guided setup link (PermissionsScreen → PermissionsSection) walks through it.
            PermissionItem(
                "Termux Commands (Optional)",
                "Run shell commands and install packages in Termux. Also needs " +
                    "`allow-external-apps=true` in Termux's ~/.termux/termux.properties — use the " +
                    "Guided setup link for step-by-step help.",
                com.gotcha.tools.TermuxTool.PERMISSION_RUN_COMMAND,
                null,
                { c -> checkPerm(c, com.gotcha.tools.TermuxTool.PERMISSION_RUN_COMMAND) },
                isRelevant = { c -> com.gotcha.tools.DeviceCapabilities.termuxUsable(c) }
            ),
            PermissionItem(
                "Device Admin (Optional)",
                "DANGEROUS: Lock screen, enforce password policy. Only enable if you really need these.",
                null,
                "special:device_admin",
                { c ->
                    val dpm = c.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
                    dpm?.isAdminActive(ComponentName(c, GotchaDeviceAdminReceiver::class.java)) ?: false
                }
            )
        )
    ),
    PermissionGroup(
        "Notifications",
        listOf(
            PermissionItem(
                "Show Notifications",
                "Display server messages and reply alerts in the status bar",
                android.Manifest.permission.POST_NOTIFICATIONS,
                null,
                // Below Android 13 (API 33) the permission is granted at install
                // time and there is no runtime dialog to show.
                { c ->
                    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                        checkPerm(c, android.Manifest.permission.POST_NOTIFICATIONS)
                }
            )
        )
    ),
    PermissionGroup(
        "System Access",
        listOf(
            PermissionItem(
                "Accessibility",
                "Read screen, tap, swipe, type",
                null,
                "special:accessibility_access",
                ::isAccessibilityGranted
            ),
            PermissionItem(
                "Notification Listener",
                "Read and dismiss notifications, media control",
                null,
                "special:notification_listener_access",
                { c ->
                    val expected = c.packageName
                    val enabled = Settings.Secure.getString(
                        c.contentResolver, "enabled_notification_listeners"
                    ) ?: ""
                    enabled.contains(expected, ignoreCase = true)
                }
            ),
            PermissionItem(
                "Display Over Apps",
                "Show floating overlays",
                null,
                "special:overlay_access",
                ::isOverlayGranted
            )
        )
    ),
    PermissionGroup(
        "Health",
        listOf(
            PermissionItem(
                "Health Connect",
                "Read steps, sleep, heart rate, weight and workouts",
                null,
                ToolResult.HEALTH_CONNECT,
                // Health Connect only reports grants from a suspend call, so this
                // reflects the last check (see HealthPermissionState).
                { _ -> HealthPermissionState.isGranted() }
            )
        )
    )
)

/**
 * Whether Gotcha's accessibility service is switched on.
 *
 * Named rather than inlined into the catalog above because the feature tour asks
 * the same question to decide when its "grant Accessibility" step is finished,
 * and two copies of this string comparison would be two chances to drift.
 */
fun isAccessibilityGranted(context: Context): Boolean {
    val expected = "${context.packageName}/com.gotcha.service.GotchaAccessibilityService"
    val enabled = Settings.Secure.getString(
        context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: ""
    return enabled.contains(expected, ignoreCase = true)
}

/** Whether "Display over other apps" is allowed — the assistive ball and Lens need it. */
fun isOverlayGranted(context: Context): Boolean = Settings.canDrawOverlays(context)

private fun checkPerm(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED
