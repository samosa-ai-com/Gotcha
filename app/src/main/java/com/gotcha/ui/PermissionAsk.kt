package com.gotcha.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.gotcha.R
import com.gotcha.ui.theme.SkinAlertDialog
import android.provider.Settings as AndroidSettings

/**
 * A runtime permission as it is put to the user, at the moment a tool needs it.
 *
 * Gotcha used to fire every runtime dialog in a row on first launch, before the
 * user had asked for anything — thirteen system prompts for capabilities they
 * might never use (issue #79). Each one is now asked for when a tool actually
 * reaches for it, and [rationale] is what makes that askable: the system dialog
 * says only "Allow Gotcha to send and view SMS messages?", so the sentence that
 * says *why, right now* has to come from us, immediately before it.
 */
data class PermissionAsk(
    val permission: String,
    /** The capability in the user's words ("SMS"), matching the Settings row. */
    val title: String,
    /** One sentence: what Gotcha is about to do with it. */
    val rationale: String
)

/**
 * Why each runtime permission is being asked for. Keyed by the exact permission
 * a tool returns in [com.gotcha.tools.ToolResult.needsPermission], so a tool
 * that reports one this table has never heard of still gets a usable sentence
 * from the Settings catalog — see [runtimePermissionAsk].
 */
private val RUNTIME_RATIONALES: Map<String, Int> = mapOf(
    android.Manifest.permission.CALL_PHONE to
        R.string.permission_rationale_call_phone,
    android.Manifest.permission.SEND_SMS to
        R.string.permission_rationale_send_sms,
    android.Manifest.permission.READ_SMS to
        R.string.permission_rationale_read_sms,
    android.Manifest.permission.READ_CALL_LOG to
        R.string.permission_rationale_read_call_log,
    android.Manifest.permission.READ_CONTACTS to
        R.string.permission_rationale_read_contacts,
    android.Manifest.permission.WRITE_CONTACTS to
        R.string.permission_rationale_write_contacts,
    android.Manifest.permission.READ_CALENDAR to
        R.string.permission_rationale_read_calendar,
    android.Manifest.permission.WRITE_CALENDAR to
        R.string.permission_rationale_write_calendar,
    android.Manifest.permission.CAMERA to
        R.string.permission_rationale_camera,
    android.Manifest.permission.RECORD_AUDIO to
        R.string.permission_rationale_record_audio,
    android.Manifest.permission.ACCESS_FINE_LOCATION to
        R.string.permission_rationale_access_fine_location,
    android.Manifest.permission.ACCESS_COARSE_LOCATION to
        R.string.permission_rationale_access_coarse_location,
    android.Manifest.permission.READ_MEDIA_IMAGES to
        R.string.permission_rationale_read_media_images,
    android.Manifest.permission.READ_EXTERNAL_STORAGE to
        R.string.permission_rationale_read_external_storage,
    android.Manifest.permission.POST_NOTIFICATIONS to
        R.string.permission_rationale_post_notifications,
    android.Manifest.permission.READ_PHONE_STATE to
        R.string.permission_rationale_read_phone_state
)

/**
 * The ask to put to the user for [permission].
 *
 * Falls back to the Settings catalog ([allPermissionGroups]) and finally to the
 * permission's own short name, so a tool reporting a permission nobody wrote a
 * sentence for still produces a dialog that names something recognisable rather
 * than a bare `android.permission.*` string.
 */
fun runtimePermissionAsk(permission: String, text: StringLookup): PermissionAsk {
    val item = allPermissionGroups()
        .flatMap { it.items }
        .firstOrNull { it.androidPermission == permission || permission in it.extraPermissions }
    return PermissionAsk(
        permission = permission,
        title = item?.name?.let { text(it) } ?: shortPermissionName(permission),
        rationale = RUNTIME_RATIONALES[permission]?.let { text(it) }
            ?: item?.description?.let {
                text(R.string.permission_rationale_from_description, text(it).replaceFirstChar(Char::lowercase))
            }
            ?: text(R.string.permission_rationale_generic, shortPermissionName(permission))
    )
}

/** `android.permission.READ_SMS` → `Read SMS`; anything unrecognisable is returned as is. */
private fun shortPermissionName(permission: String): String =
    permission.substringAfterLast('.')
        .split('_')
        .joinToString(" ") { word -> word.lowercase().replaceFirstChar(Char::uppercase) }

/**
 * Asked immediately before the system dialog, because the system dialog cannot
 * say why. "Not now" is a real answer: the tool call simply fails with the
 * message it already returns, and the permission is asked for again the next
 * time something needs it.
 */
@Composable
fun PermissionRationaleDialog(
    ask: PermissionAsk,
    onAllow: () -> Unit,
    onDeny: () -> Unit
) {
    SkinAlertDialog(
        onDismissRequest = onDeny,
        title = { Text(stringResource(R.string.permission_ask_needed, ask.title)) },
        text = { Text(ask.rationale) },
        confirmButton = {
            TextButton(onClick = onAllow) { Text(stringResource(R.string.permission_ask_continue)) }
        },
        dismissButton = {
            TextButton(onClick = onDeny) { Text(stringResource(R.string.permission_ask_not_now)) }
        }
    )
}

/**
 * Shown when Android will no longer raise the system dialog — the user has
 * denied [ask] twice, or turned it off in Settings. Without this the "Continue"
 * button looks broken: the dialog never appears and the tool fails again.
 */
@Composable
fun PermissionBlockedDialog(
    ask: PermissionAsk,
    packageName: String,
    onDismiss: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    SkinAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.permission_ask_turned_off, ask.title)) },
        text = {
            Text(
                stringResource(R.string.permission_ask_android_won_t_ask_again)
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onDismiss()
                    openAppPermissionSettings(context, packageName)
                }
            ) { Text(stringResource(R.string.permission_ask_open_app_settings)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.permission_ask_not_now)) }
        }
    )
}

/**
 * Opens this app's system settings page — the only place Android lets a
 * permission be revoked, or re-granted after a permanent denial.
 *
 * Returns the intent that was fired so the routing can be asserted without a
 * device; callers ignore the return.
 */
fun openAppPermissionSettings(context: Context, packageName: String): Intent {
    val intent = Intent(
        AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.parse("package:$packageName")
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    // A missing settings activity must degrade to a no-op, never take the app down.
    runCatching { context.startActivity(intent) }
    return intent
}
