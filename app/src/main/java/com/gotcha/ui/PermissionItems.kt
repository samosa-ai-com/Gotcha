package com.gotcha.ui

import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.app.admin.DevicePolicyManager
import android.app.AppOpsManager
import android.content.ComponentName
import android.content.ContextWrapper
import androidx.core.content.ContextCompat
import com.gotcha.service.GotchaDeviceAdminReceiver

data class PermissionGroup(
    val name: String,
    val items: List<PermissionItem>
)

data class PermissionItem(
    val name: String,
    val description: String,
    val androidPermission: String?,
    val specialMarker: String?,
    val isGranted: (Context) -> Boolean
)

fun allPermissionGroups(context: Context): List<PermissionGroup> = listOf(
    PermissionGroup("Communications", listOf(
        PermissionItem(
            "Phone", "Place calls directly",
            android.Manifest.permission.CALL_PHONE, null,
            { c -> checkPerm(c, android.Manifest.permission.CALL_PHONE) }
        ),
        PermissionItem(
            "SMS", "Send text messages",
            android.Manifest.permission.SEND_SMS, null,
            { c -> checkPerm(c, android.Manifest.permission.SEND_SMS) }
        ),
        PermissionItem(
            "Read SMS", "Read inbox messages",
            android.Manifest.permission.READ_SMS, null,
            { c -> checkPerm(c, android.Manifest.permission.READ_SMS) }
        ),
        PermissionItem(
            "Call Log", "Read recent call history",
            android.Manifest.permission.READ_CALL_LOG, null,
            { c -> checkPerm(c, android.Manifest.permission.READ_CALL_LOG) }
        )
    )),
    PermissionGroup("Contacts", listOf(
        PermissionItem(
            "Contacts", "Read and create contacts",
            android.Manifest.permission.READ_CONTACTS, null,
            { c -> checkPerm(c, android.Manifest.permission.READ_CONTACTS) }
        )
    )),
    PermissionGroup("Calendar", listOf(
        PermissionItem(
            "Calendar", "Read and create calendar events",
            android.Manifest.permission.READ_CALENDAR, null,
            { c -> checkPerm(c, android.Manifest.permission.READ_CALENDAR) }
        )
    )),
    PermissionGroup("Media & Storage", listOf(
        PermissionItem(
            "Camera", "Take photos",
            android.Manifest.permission.CAMERA, null,
            { c -> checkPerm(c, android.Manifest.permission.CAMERA) }
        ),
        PermissionItem(
            "Microphone", "Record audio",
            android.Manifest.permission.RECORD_AUDIO, null,
            { c -> checkPerm(c, android.Manifest.permission.RECORD_AUDIO) }
        ),
        PermissionItem(
            "Storage Read", "Read files from shared storage",
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                android.Manifest.permission.READ_MEDIA_IMAGES
            else android.Manifest.permission.READ_EXTERNAL_STORAGE, null,
            { c ->
                val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                    android.Manifest.permission.READ_MEDIA_IMAGES
                else android.Manifest.permission.READ_EXTERNAL_STORAGE
                checkPerm(c, perm)
            }
        ),
        PermissionItem(
            "Storage Write", "Write files to shared storage",
            android.Manifest.permission.WRITE_EXTERNAL_STORAGE, null,
            { c ->
                if (Build.VERSION.SDK_INT <= 29) checkPerm(c, android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                else true // API 30+ uses MANAGE_EXTERNAL_STORAGE
            }
        ),
        PermissionItem(
            "All Files Access", "Full access to all files on device",
            null, "special:all_files_access",
            { c ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager()
                else checkPerm(c, android.Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        )
    )),
    PermissionGroup("Location", listOf(
        PermissionItem(
            "Location", "Get device location",
            android.Manifest.permission.ACCESS_FINE_LOCATION, null,
            { c -> checkPerm(c, android.Manifest.permission.ACCESS_FINE_LOCATION) }
        )
    )),
    PermissionGroup("Device Control", listOf(
        PermissionItem(
            "Write Settings", "Modify system settings (brightness)",
            null, "special:write_settings",
            { c -> Settings.System.canWrite(c) }
        ),
        PermissionItem(
            "Do Not Disturb", "Silence/vibrate mode and DND",
            null, "special:dnd_access",
            { c ->
                val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
                nm?.isNotificationPolicyAccessGranted ?: false
            }
        ),
        PermissionItem(
            "Usage Access", "Read app usage and data stats",
            null, "special:usage_access",
            { c ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                    val appOps = c.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
                    appOps?.checkOpNoThrow(
                        AppOpsManager.OPSTR_GET_USAGE_STATS,
                        android.os.Process.myUid(), c.packageName
                    ) == AppOpsManager.MODE_ALLOWED
                } else true
            }
        ),
        PermissionItem(
            "Device Admin", "Lock screen, enforce password policy",
            null, "special:device_admin",
            { c ->
                val dpm = c.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
                dpm?.isAdminActive(ComponentName(c, GotchaDeviceAdminReceiver::class.java)) ?: false
            }
        )
    )),
    PermissionGroup("System Access", listOf(
        PermissionItem(
            "Accessibility", "Read screen, tap, swipe, type",
            null, "special:accessibility_access",
            { c ->
                val expected = "${c.packageName}/com.gotcha.service.GotchaAccessibilityService"
                val enabled = Settings.Secure.getString(
                    c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                ) ?: ""
                enabled.contains(expected, ignoreCase = true)
            }
        ),
        PermissionItem(
            "Notification Listener", "Read and dismiss notifications, media control",
            null, "special:notification_listener_access",
            { c ->
                val expected = c.packageName
                val enabled = Settings.Secure.getString(
                    c.contentResolver, "enabled_notification_listeners"
                ) ?: ""
                enabled.contains(expected, ignoreCase = true)
            }
        ),
        PermissionItem(
            "Display Over Apps", "Show floating overlays",
            null, "special:overlay_access",
            { c -> Settings.canDrawOverlays(c) }
        ),
        PermissionItem(
            "VPN", "Block all network traffic (firewall kill-switch)",
            null, "special:vpn_consent",
            { c ->
                try {
                    val cm = c.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
                    cm?.let { mgr ->
                        val network = mgr.activeNetwork
                        if (network != null) {
                            mgr.getNetworkCapabilities(network)
                                ?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) ?: false
                        } else false
                    } ?: false
                } catch (_: SecurityException) { false }
            }
        )
    ))
)

private fun checkPerm(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED
