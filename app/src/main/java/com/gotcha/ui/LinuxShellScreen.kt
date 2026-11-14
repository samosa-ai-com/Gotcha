package com.gotcha.ui

import android.content.Intent
import android.net.Uri
import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.gotcha.R
import com.gotcha.shell.ShellInfo
import com.gotcha.tools.CompanionConnection
import com.gotcha.tools.CompanionIdentity
import com.gotcha.tools.CompanionShellBackend
import com.gotcha.tools.CompanionShellManager
import com.gotcha.tools.FileResolver
import com.gotcha.tools.ShellEntitlement
import com.gotcha.ui.theme.SkinAlertDialog
import kotlinx.coroutines.launch
import java.util.Date

/** What the page is doing right now; one action at a time. */
private enum class ShellAction { INSTALL, REPAIR, RESET }

/** Install progress as the companion reports it. */
private data class InstallProgress(val phase: String, val done: Long, val total: Long)

/**
 * Settings ▸ Linux shell: the state of the Gotcha Shell companion app and of the Linux system
 * inside it, with the actions that manage it — Install, Repair, Reset and Uninstall. The Termux
 * setup page is linked from the bottom as the fallback.
 *
 * Everything here asks the companion over Binder, which blocks, so each action runs in a
 * coroutine and the page shows what is in progress.
 */
@Composable
fun LinuxShellScreen(onBack: () -> Unit, onOpenTermuxSetup: () -> Unit) {
    val context = LocalContext.current
    val manager = remember(context) { CompanionShellManager(context) }
    val overlay = rememberSettingsOverlayState()
    val scope = rememberCoroutineScope()

    var identity by remember { mutableStateOf(manager.identity()) }
    var info by remember { mutableStateOf<ShellInfo?>(null) }
    var access by remember { mutableStateOf(ShellEntitlement.access(context)) }
    var running by remember { mutableStateOf<ShellAction?>(null) }
    var progress by remember { mutableStateOf<InstallProgress?>(null) }
    var confirmReset by remember { mutableStateOf(false) }

    // Re-read on every resume: the companion may have been installed, updated or removed
    // while the user was away from this page.
    val lifecycleOwner = LocalLifecycleOwner.current
    var resumeSignal by remember { mutableStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumeSignal++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    suspend fun refresh() {
        // Asked afresh every time the page opens, so subscribing or signing in shows at once.
        access = ShellEntitlement.refresh(context, force = true)
        identity = manager.identity()
        info = if (identity.installed && identity.trusted) manager.info() else null
    }
    LaunchedEffect(resumeSignal) { refresh() }

    fun perform(action: ShellAction, doneMessage: Int, work: suspend () -> String?) {
        if (running != null) return
        running = action
        scope.launch {
            val error = work()
            progress = null
            running = null
            refresh()
            overlay.show(error ?: context.getString(doneMessage), sticky = error != null)
        }
    }

    SettingsScaffold(title = stringResource(SettingsPage.LINUX_SHELL.title), onBack = onBack, overlay = overlay) {
        Text(stringResource(R.string.linux_shell_intro), style = MaterialTheme.typography.bodySmall)

        val allowed = access == ShellEntitlement.Access.ALLOWED
        SetupCheck(
            title = stringResource(R.string.linux_shell_plan_title),
            done = allowed,
            detail = planDetail(access, ShellEntitlement.allowedUntil(context))
        )

        SetupCheck(
            title = stringResource(R.string.linux_shell_app_title),
            done = identity.installed && identity.trusted,
            detail = appDetail(identity, info)
        )

        val current = info
        if (identity.installed && identity.trusted) {
            SetupCheck(
                title = stringResource(R.string.linux_shell_system_title),
                done = current?.rootfsReady == true,
                detail = systemDetail(current, running == ShellAction.INSTALL),
                action = if (
                    allowed && current != null && !current.rootfsReady && running == null && !current.installing
                ) {
                    SetupAction(stringResource(R.string.linux_shell_action_install)) {
                        perform(ShellAction.INSTALL, R.string.linux_shell_install_done) {
                            manager.install { phase, done, total -> progress = InstallProgress(phase, done, total) }
                        }
                    }
                } else {
                    null
                }
            )
            progress?.let { InstallProgressBar(it) }
        }

        if (current?.rootfsReady == true) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = {
                        perform(ShellAction.REPAIR, R.string.linux_shell_repair_done) { manager.repair() }
                    },
                    enabled = running == null && allowed,
                    modifier = Modifier.weight(1f).testTag("linux_shell_repair")
                ) {
                    Text(
                        stringResource(
                            if (running == ShellAction.REPAIR) {
                                R.string.linux_shell_repairing
                            } else {
                                R.string.linux_shell_action_repair
                            }
                        )
                    )
                }
                OutlinedButton(
                    onClick = { confirmReset = true },
                    enabled = running == null,
                    modifier = Modifier.weight(1f).testTag("linux_shell_reset")
                ) { Text(stringResource(R.string.linux_shell_action_reset)) }
            }
            Text(
                stringResource(R.string.linux_shell_repair_reset_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (identity.installed) {
            OutlinedButton(
                onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_DELETE, Uri.parse("package:${CompanionConnection.PACKAGE}"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                },
                enabled = running == null,
                modifier = Modifier.fillMaxWidth().testTag("linux_shell_uninstall")
            ) { Text(stringResource(R.string.linux_shell_action_uninstall)) }
        }

        if (
            allowed && current?.rootfsReady == true &&
            current.interfaceVersion == CompanionShellBackend.INTERFACE_VERSION
        ) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    stringResource(R.string.linux_shell_ready),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }

        HorizontalDivider(thickness = 1.dp)
        Text(
            stringResource(R.string.linux_shell_termux_fallback),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        SettingsNavRow(
            page = SettingsPage.TERMUX,
            onClick = onOpenTermuxSetup,
            modifier = Modifier.testTag("linux_shell_termux_row")
        )
    }

    if (confirmReset) {
        SkinAlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(stringResource(R.string.linux_shell_reset_confirm_title)) },
            text = { Text(stringResource(R.string.linux_shell_reset_confirm_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmReset = false
                        perform(ShellAction.RESET, R.string.linux_shell_reset_done) { manager.reset() }
                    }
                ) { Text(stringResource(R.string.linux_shell_action_reset)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }
}

@Composable
private fun planDetail(access: ShellEntitlement.Access, allowedUntil: Long?): String = when (access) {
    ShellEntitlement.Access.ALLOWED ->
        if (allowedUntil == null) {
            stringResource(R.string.linux_shell_plan_active)
        } else {
            val context = LocalContext.current
            stringResource(
                R.string.linux_shell_plan_active_until,
                DateFormat.getMediumDateFormat(context).format(Date(allowedUntil))
            )
        }
    ShellEntitlement.Access.SIGNED_OUT -> stringResource(R.string.linux_shell_plan_signed_out)
    ShellEntitlement.Access.NOT_PRO -> stringResource(R.string.linux_shell_plan_not_pro)
    ShellEntitlement.Access.NEEDS_CHECK -> stringResource(R.string.linux_shell_plan_needs_check)
}

@Composable
private fun appDetail(identity: CompanionIdentity, info: ShellInfo?): String = when {
    !identity.installed -> stringResource(R.string.linux_shell_app_not_installed)
    !identity.trusted -> stringResource(R.string.linux_shell_app_untrusted)
    info == null -> stringResource(R.string.linux_shell_app_unreachable, identity.versionName.orEmpty())
    info.interfaceVersion < CompanionShellBackend.INTERFACE_VERSION ->
        stringResource(R.string.linux_shell_app_too_old, identity.versionName.orEmpty())
    info.interfaceVersion > CompanionShellBackend.INTERFACE_VERSION ->
        stringResource(R.string.linux_shell_app_too_new, identity.versionName.orEmpty())
    else -> stringResource(
        R.string.linux_shell_app_installed,
        identity.versionName.orEmpty(),
        FileResolver.formatSizeStatic(info.dataBytes)
    )
}

@Composable
private fun systemDetail(info: ShellInfo?, installing: Boolean): String = when {
    installing || info?.installing == true -> stringResource(R.string.linux_shell_system_installing)
    info == null -> stringResource(R.string.linux_shell_system_unknown)
    !info.rootfsReady -> stringResource(R.string.linux_shell_system_missing)
    info.runningJobs > 0 -> stringResource(
        R.string.linux_shell_system_installed_busy,
        info.rootfsRelease.orEmpty(),
        info.runningJobs
    )
    else -> stringResource(R.string.linux_shell_system_installed, info.rootfsRelease.orEmpty())
}

@Composable
private fun InstallProgressBar(progress: InstallProgress) {
    val label = stringResource(
        when (progress.phase) {
            "download" -> R.string.linux_shell_phase_download
            "verify" -> R.string.linux_shell_phase_verify
            "extract" -> R.string.linux_shell_phase_extract
            else -> R.string.linux_shell_phase_configure
        }
    )
    Text(label, style = MaterialTheme.typography.bodySmall)
    if (progress.total > 0) {
        LinearProgressIndicator(
            progress = { (progress.done.toFloat() / progress.total).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth()
        )
    } else {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
}
