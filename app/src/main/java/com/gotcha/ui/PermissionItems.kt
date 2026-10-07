package com.gotcha.ui

import android.app.AppOpsManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import com.gotcha.R
import com.gotcha.service.GotchaDeviceAdminReceiver
import com.gotcha.tools.HealthPermissionState
import com.gotcha.tools.ToolResult

data class PermissionGroup(
    @StringRes val name: Int,
    val items: List<PermissionItem>
)

data class PermissionItem(
    @StringRes val name: Int,
    @StringRes val description: Int,
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
        R.string.permission_group_communications,
        listOf(
            PermissionItem(
                R.string.permission_phone,
                R.string.permission_phone_description,
                android.Manifest.permission.CALL_PHONE,
                null,
                { c -> checkPerm(c, android.Manifest.permission.CALL_PHONE) }
            ),
            PermissionItem(
                R.string.permission_sms,
                R.string.permission_sms_description,
                android.Manifest.permission.SEND_SMS,
                null,
                { c -> checkPerm(c, android.Manifest.permission.SEND_SMS) }
            ),
            PermissionItem(
                R.string.permission_read_sms,
                R.string.permission_read_sms_description,
                android.Manifest.permission.READ_SMS,
                null,
                { c -> checkPerm(c, android.Manifest.permission.READ_SMS) }
            ),
            PermissionItem(
                R.string.permission_call_log,
                R.string.permission_call_log_description,
                android.Manifest.permission.READ_CALL_LOG,
                null,
                { c -> checkPerm(c, android.Manifest.permission.READ_CALL_LOG) }
            )
        )
    ),
    PermissionGroup(
        R.string.permission_group_contacts,
        listOf(
            PermissionItem(
                R.string.permission_contacts,
                R.string.permission_contacts_description,
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
        R.string.permission_group_calendar,
        listOf(
            PermissionItem(
                R.string.permission_calendar,
                R.string.permission_calendar_description,
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
        R.string.permission_group_media_storage,
        listOf(
            PermissionItem(
                R.string.permission_camera,
                R.string.permission_camera_description,
                android.Manifest.permission.CAMERA,
                null,
                { c -> checkPerm(c, android.Manifest.permission.CAMERA) }
            ),
            PermissionItem(
                R.string.permission_microphone,
                R.string.permission_microphone_description,
                android.Manifest.permission.RECORD_AUDIO,
                null,
                { c -> checkPerm(c, android.Manifest.permission.RECORD_AUDIO) }
            ),
            PermissionItem(
                R.string.permission_storage_read,
                R.string.permission_storage_read_description,
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
                R.string.permission_storage_write,
                R.string.permission_storage_write_description,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                null,
                // API 30+ uses MANAGE_EXTERNAL_STORAGE instead; nothing to request here.
                { true }
            ),
            PermissionItem(
                R.string.permission_all_files_access,
                R.string.permission_all_files_access_description,
                null,
                "special:all_files_access",
                { Environment.isExternalStorageManager() }
            )
        )
    ),
    PermissionGroup(
        R.string.permission_group_location,
        listOf(
            PermissionItem(
                R.string.permission_location,
                R.string.permission_location_description,
                android.Manifest.permission.ACCESS_FINE_LOCATION,
                null,
                { c -> checkPerm(c, android.Manifest.permission.ACCESS_FINE_LOCATION) }
            )
        )
    ),
    PermissionGroup(
        R.string.permission_group_device_control,
        listOf(
            PermissionItem(
                R.string.permission_write_settings,
                R.string.permission_write_settings_description,
                null,
                "special:write_settings",
                { c -> Settings.System.canWrite(c) }
            ),
            PermissionItem(
                R.string.permission_do_not_disturb,
                R.string.permission_do_not_disturb_description,
                null,
                "special:dnd_access",
                { c ->
                    val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
                    nm?.isNotificationPolicyAccessGranted ?: false
                }
            ),
            PermissionItem(
                R.string.permission_usage_access,
                R.string.permission_usage_access_description,
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
                R.string.permission_termux_commands_optional,
                R.string.permission_termux_commands_optional_description,
                com.gotcha.tools.TermuxTool.PERMISSION_RUN_COMMAND,
                null,
                { c -> checkPerm(c, com.gotcha.tools.TermuxTool.PERMISSION_RUN_COMMAND) },
                isRelevant = { c -> com.gotcha.tools.DeviceCapabilities.termuxUsable(c) }
            ),
            PermissionItem(
                R.string.permission_device_admin_optional,
                R.string.permission_device_admin_optional_description,
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
        R.string.permission_group_notifications,
        listOf(
            PermissionItem(
                R.string.permission_show_notifications,
                R.string.permission_show_notifications_description,
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
        R.string.permission_group_system_access,
        listOf(
            PermissionItem(
                R.string.permission_accessibility,
                R.string.permission_accessibility_description,
                null,
                "special:accessibility_access",
                ::isAccessibilityGranted
            ),
            PermissionItem(
                R.string.permission_notification_listener,
                R.string.permission_notification_listener_description,
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
                R.string.permission_display_over_apps,
                R.string.permission_display_over_apps_description,
                null,
                "special:overlay_access",
                ::isOverlayGranted
            )
        )
    ),
    PermissionGroup(
        R.string.permission_group_health,
        listOf(
            PermissionItem(
                R.string.permission_health_connect,
                R.string.permission_health_connect_description,
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
 * the same question to decide when its "grant Accessibility" step is finished.
 * It delegates rather than comparing the settings string itself: three copies of
 * that comparison had already drifted into three different answers, which is how
 * a disabled setting came to surface as an unrelated error (issue #76). This is
 * the "has the user granted it" question, so it reads the setting rather than
 * [com.gotcha.tools.DeviceCapabilities.accessibilityState] — a granted service
 * that Android has unbound should still show as granted here.
 */
fun isAccessibilityGranted(context: Context): Boolean =
    com.gotcha.tools.DeviceCapabilities.accessibilityEnabled(context)

/** Whether "Display over other apps" is allowed — the assistive ball and Lens need it. */
fun isOverlayGranted(context: Context): Boolean = Settings.canDrawOverlays(context)

private fun checkPerm(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED
