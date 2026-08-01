package com.gotcha.ui

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.gotcha.service.GotchaDeviceAdminReceiver
import com.gotcha.tools.ToolResult
import android.provider.Settings as AndroidSettings

@Composable
fun PermissionsSection(packageName: String) {
    val context = LocalContext.current
    val groups = remember { allPermissionGroups() }
    var userToggledGroups by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }

    // Trigger recomposition on every activity resume so permission state is re-read from Android
    val lifecycleOwner = LocalLifecycleOwner.current
    var resumeSignal by remember { mutableStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumeSignal++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val runtimeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* permission state is re-read on next ON_RESUME via resumeSignal */ }

    // No "Permissions" heading: PermissionsScreen's top bar already says it.
    Text(
        "Configure permissions that the assistant needs. Runtime permissions show a system dialog " +
            "when toggled on. Special-access permissions open a Settings screen for one-time setup.",
        style = MaterialTheme.typography.bodySmall
    )

    for (group in groups) {
        val hasUngrantedPermission = remember(resumeSignal, group) {
            group.items.any { !it.isGranted(context) }
        }
        val expanded = userToggledGroups[group.name] ?: hasUngrantedPermission
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { userToggledGroups = userToggledGroups + (group.name to !expanded) }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (expanded) "▼ " else "▶ ",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = group.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
        }

        AnimatedVisibility(visible = expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (item in group.items) {
                    // Read live permission state from Android, re-checked on every resume
                    val granted = remember(resumeSignal) { item.isGranted(context) }
                    PermissionRow(
                        item = item,
                        granted = granted,
                        packageName = packageName,
                        onRequestRuntime = { perms ->
                            runtimeLauncher.launch(perms)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun PermissionRow(
    item: PermissionItem,
    granted: Boolean,
    packageName: String,
    onRequestRuntime: (Array<String>) -> Unit
) {
    val context = LocalContext.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp, horizontal = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(item.name, style = MaterialTheme.typography.bodyMedium)
            Text(
                item.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = granted,
            onCheckedChange = { checked ->
                if (checked) {
                    if (item.androidPermission != null) {
                        onRequestRuntime((listOf(item.androidPermission) + item.extraPermissions).toTypedArray())
                    } else if (item.specialMarker != null) {
                        openSpecialAccess(context, item.specialMarker, packageName)
                    }
                } else {
                    Toast.makeText(
                        context,
                        "Revoke this permission in System Settings → Apps → Gotcha → Permissions",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        )
    }
}

/**
 * Opens the system screen that grants [marker]. Shared with the feature tour,
 * whose permission steps offer the same journey from their coach card — two
 * copies of this intent table would be two places for an OEM quirk to be fixed
 * in only one.
 */
internal fun openSpecialAccess(context: android.content.Context, marker: String, packageName: String) {
    val intent: Intent? = when (marker) {
        ToolResult.WRITE_SETTINGS -> Intent(
            AndroidSettings.ACTION_MANAGE_WRITE_SETTINGS,
            Uri.parse("package:$packageName")
        )
        ToolResult.USAGE_ACCESS -> Intent(AndroidSettings.ACTION_USAGE_ACCESS_SETTINGS)
        ToolResult.DND_ACCESS -> Intent(AndroidSettings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
        ToolResult.ACCESSIBILITY_ACCESS -> Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS)
        ToolResult.NOTIFICATION_LISTENER_ACCESS -> Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        ToolResult.ALL_FILES_ACCESS ->
            Intent(AndroidSettings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
        ToolResult.OVERLAY_ACCESS -> Intent(
            AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        ToolResult.DEVICE_ADMIN -> Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).putExtra(
            DevicePolicyManager.EXTRA_DEVICE_ADMIN,
            ComponentName(context, GotchaDeviceAdminReceiver::class.java)
        ).putExtra(
            DevicePolicyManager.EXTRA_ADD_EXPLANATION,
            "Gotcha uses device administration to lock the screen, enforce " +
                "password policy, and disable the camera when you ask it to."
        )
        ToolResult.VPN_CONSENT -> VpnService.prepare(context)?.let {
            Intent(Intent.ACTION_MAIN).apply {
                `package` = it.`package`
            }
        }
        else -> null
    }
    if (intent != null) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
