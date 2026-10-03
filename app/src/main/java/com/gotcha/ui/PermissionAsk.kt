package com.gotcha.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
private val RUNTIME_RATIONALES: Map<String, String> = mapOf(
    android.Manifest.permission.CALL_PHONE to
        "Gotcha needs the Phone permission to dial the number you asked it to call. " +
        "It only places calls you ask for, and the dialer stays on screen while it does.",
    android.Manifest.permission.SEND_SMS to
        "Gotcha needs the SMS permission to send the message you asked for. " +
        "Nothing is sent that you haven't asked it to send.",
    android.Manifest.permission.READ_SMS to
        "Gotcha needs to read your inbox to answer this — for example to find a code " +
        "or a message someone sent you. Messages stay on the device unless you " +
        "ask Gotcha to do something with them.",
    android.Manifest.permission.READ_CALL_LOG to
        "Gotcha needs your call history to answer questions about who called and when.",
    android.Manifest.permission.READ_CONTACTS to
        "Gotcha needs your contacts to turn a name into a number — so \"call Priya\" " +
        "reaches the right Priya.",
    android.Manifest.permission.WRITE_CONTACTS to
        "Gotcha needs permission to save this contact to your address book.",
    android.Manifest.permission.READ_CALENDAR to
        "Gotcha needs your calendar to see what's on your schedule.",
    android.Manifest.permission.WRITE_CALENDAR to
        "Gotcha needs calendar access to create or change the event you asked for.",
    android.Manifest.permission.CAMERA to
        "Gotcha needs the camera to take the photo you asked for.",
    android.Manifest.permission.RECORD_AUDIO to
        "Gotcha needs the microphone to hear you — for voice input, recording, and " +
        "the \"Hey Gotcha\" wake word.",
    android.Manifest.permission.ACCESS_FINE_LOCATION to
        "Gotcha needs your location to answer this — weather, what's nearby, or where " +
        "you are right now.",
    android.Manifest.permission.ACCESS_COARSE_LOCATION to
        "Gotcha needs an approximate location to answer this.",
    android.Manifest.permission.READ_MEDIA_IMAGES to
        "Gotcha needs access to your photos to open the image you pointed it at.",
    android.Manifest.permission.READ_EXTERNAL_STORAGE to
        "Gotcha needs storage access to open the file you pointed it at.",
    android.Manifest.permission.POST_NOTIFICATIONS to
        "Gotcha needs notification permission to alert you when a reply or a message arrives.",
    android.Manifest.permission.READ_PHONE_STATE to
        "On a dual-SIM phone, Gotcha needs to read the phone state to pick which SIM " +
        "places the call or sends the message."
)

/**
 * The ask to put to the user for [permission].
 *
 * Falls back to the Settings catalog ([allPermissionGroups]) and finally to the
 * permission's own short name, so a tool reporting a permission nobody wrote a
 * sentence for still produces a dialog that names something recognisable rather
 * than a bare `android.permission.*` string.
 */
fun runtimePermissionAsk(permission: String): PermissionAsk {
    val item = allPermissionGroups()
        .flatMap { it.items }
        .firstOrNull { it.androidPermission == permission || permission in it.extraPermissions }
    return PermissionAsk(
        permission = permission,
        title = item?.name ?: shortPermissionName(permission),
        rationale = RUNTIME_RATIONALES[permission]
            ?: item?.description?.let { "Gotcha needs this permission to ${it.replaceFirstChar(Char::lowercase)}." }
            ?: "Gotcha needs the ${shortPermissionName(permission)} permission to do what you asked."
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
        title = { Text("${ask.title} permission needed") },
        text = { Text(ask.rationale) },
        confirmButton = {
            TextButton(onClick = onAllow) { Text("Continue") }
        },
        dismissButton = {
            TextButton(onClick = onDeny) { Text("Not now") }
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
        title = { Text("${ask.title} is turned off") },
        text = {
            Text(
                "Android won't ask again once a permission has been turned off. " +
                    "You can switch it back on in Settings › Apps › Gotcha › Permissions, " +
                    "then ask Gotcha again."
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onDismiss()
                    openAppPermissionSettings(context, packageName)
                }
            ) { Text("Open app settings") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Not now") }
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
