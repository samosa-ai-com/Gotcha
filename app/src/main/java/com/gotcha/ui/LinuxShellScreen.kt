package com.gotcha.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
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
import com.gotcha.tools.CompanionInstalls
import com.gotcha.tools.CompanionShellBackend
import com.gotcha.tools.CompanionShellManager
import com.gotcha.tools.FileResolver
import com.gotcha.tools.ShellEntitlement
import com.gotcha.ui.theme.SkinAlertDialog
import kotlinx.coroutines.launch

/** What the page is doing right now; one action at a time. */
private enum class ShellAction { GET_APP, INSTALL, REPAIR, RESET }

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
fun LinuxShellScreen(onBack: () -> Unit, onOpenTermuxSetup: () -> Unit, onOpenTerminal: () -> Unit) {
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
    var showInstallPrep by remember { mutableStateOf(false) }
    val installNotice by CompanionInstalls.notices.collectAsState()

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
        val notice = installNotice
        // Asked afresh every time the page opens, so subscribing or signing in shows at once.
        access = ShellEntitlement.refresh(context, force = true)
        identity = manager.identity()
        info = if (identity.installed && identity.trusted) manager.info() else null
        // The system installer answers by broadcast, which may arrive while this screen is
        // gone. Without this the user who declined the prompt, or was blocked by Play Protect,
        // returned to a page that simply still said "not installed" and no explanation.
        notice?.let { CompanionInstalls.consume(it) }?.let { received ->
            if (received.outcome == CompanionInstalls.Outcome.FAILED) {
                overlay.show(
                    if (received.reason.isNullOrBlank()) {
                        context.getString(R.string.linux_shell_get_app_install_failed)
                    } else {
                        received.reason
                    }
                )
            }
        }
    }
    LaunchedEffect(resumeSignal, installNotice) { refresh() }

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

    // The runtime is not optional: without it the shell cannot start, so offering it as a
    // separate button invites a companion that is installed but unusable. When the companion
    // becomes available and the system is not there yet, install it without being asked.
    //
    // Once per visit, deliberately: if the automatic attempt fails the row shows the error and
    // the user can retry. Looping on failure would fight them and burn their data.
    var autoChainedRuntime by remember { mutableStateOf(false) }
    LaunchedEffect(identity.installed, identity.trusted, info?.rootfsReady, access) {
        if (autoChainedRuntime) return@LaunchedEffect
        if (access != ShellEntitlement.Access.ALLOWED) return@LaunchedEffect
        if (!identity.installed || !identity.trusted) return@LaunchedEffect
        // `info` is null until the first refresh returns. Treating "not known yet" as "not
        // installed" would start an install every time this screen opens, on a device that
        // already has one — and installing replaces the Linux system, so that would destroy
        // the user's files, packages and scripts on every visit.
        val current = info ?: return@LaunchedEffect
        if (current.rootfsReady) return@LaunchedEffect
        // The companion may already be installing (a previous attempt, or a command started
        // one). Do not stack a second install on top of it.
        if (current.installing) return@LaunchedEffect
        autoChainedRuntime = true
        perform(ShellAction.INSTALL, R.string.linux_shell_install_done) {
            manager.install { phase, done, total -> progress = InstallProgress(phase, done, total) }
        }
    }

    SettingsScaffold(title = stringResource(SettingsPage.LINUX_SHELL.title), onBack = onBack, overlay = overlay) {
        Text(stringResource(R.string.linux_shell_intro), style = MaterialTheme.typography.bodySmall)

        val allowed = access == ShellEntitlement.Access.ALLOWED
        SetupCheck(
            title = stringResource(R.string.linux_shell_plan_title),
            done = allowed,
            detail = planDetail(access)
        )

        SetupCheck(
            title = stringResource(R.string.linux_shell_app_title),
            done = identity.installed && identity.trusted,
            detail = appDetail(identity, info),
            // Only a plan that includes the shell may obtain it. When the gate says no, there is
            // no action at all: the APK route would refuse anyway, and offering a button that
            // always fails is worse than not offering one.
            action = if (allowed && (!identity.installed || !identity.trusted) && running == null) {
                SetupAction(stringResource(R.string.linux_shell_action_get_app)) {
                    // Shown before anything downloads: the phone will ask to allow installs
                    // from Gotcha, and Play Protect may warn. Neither can be suppressed, so
                    // the user is told first instead of meeting an unexplained blocker.
                    showInstallPrep = true
                }
            } else {
                null
            }
        )

        val current = info
        if (identity.installed && identity.trusted) {
            // The runtime is part of the companion but is downloaded on first use rather than
            // shipped, so it has its own row: a user can have the Linux system and not the
            // runtime, and the fix for each is different. There is no button: Gotcha pushes the
            // source when a command needs it, and the download runs in the companion.
            SetupCheck(
                title = stringResource(R.string.linux_shell_runtime_title),
                done = current?.runtimeReady == true,
                // A companion older than the runtime fields cannot say, and its row above already
                // says to update it; claiming "not checked yet" here would be misleading.
                detail = if (current != null && current.interfaceVersion < CompanionShellBackend.INTERFACE_VERSION) {
                    stringResource(R.string.linux_shell_runtime_unavailable)
                } else {
                    runtimeDetail(current)
                }
            )
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
        }
        // Rendered outside the installed-only branch: the APK fetch shows progress before the
        // companion is present at all, which is the whole point of this screen's first action.
        progress?.let { InstallProgressBar(it) }

        if (current?.rootfsReady == true) {
            val terminalSupported = current.interfaceVersion >= CompanionShellBackend.INTERFACE_VERSION
            Button(
                onClick = onOpenTerminal,
                // Also refuse while the companion is downloading its runtime on its own: the
                // screen's `running` only covers work it started itself, and opening a session
                // mid-download can only fail.
                enabled = allowed && terminalSupported && running == null &&
                    !current.installing && !current.runtimeInstalling,
                modifier = Modifier.fillMaxWidth().testTag("linux_shell_open_terminal")
            ) { Text(stringResource(R.string.linux_shell_action_open_terminal)) }
            if (!terminalSupported) {
                Text(
                    stringResource(R.string.linux_shell_terminal_needs_update),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
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
            current.interfaceVersion >= CompanionShellBackend.INTERFACE_VERSION
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

    if (showInstallPrep) {
        // The system install dialog is unavoidable and Play Protect may warn on top of it.
        // Saying so beforehand turns a scary blocker into an expected step.
        SkinAlertDialog(
            onDismissRequest = { showInstallPrep = false },
            title = { Text(stringResource(R.string.linux_shell_prep_title)) },
            text = { Text(stringResource(R.string.linux_shell_prep_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showInstallPrep = false
                        CompanionInstalls.clear()
                        perform(ShellAction.GET_APP, R.string.linux_shell_get_app_started) {
                            manager.installFromBackend { phase, done, total ->
                                // The download reports from Dispatchers.IO; a snapshot write
                                // from there can miss a recomposition.
                                Snapshot.withMutableSnapshot {
                                    progress = InstallProgress(phase, done, total)
                                }
                            }
                        }
                    }
                ) { Text(stringResource(R.string.linux_shell_prep_continue)) }
            },
            dismissButton = {
                TextButton(onClick = { showInstallPrep = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
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
private fun planDetail(access: ShellEntitlement.Access): String = when (access) {
    // Deliberately does not mention when the offline grace expires. A user who was never told
    // about the grace reads a date here as a subscription countdown, and it answers a question
    // nobody asked. The grace still works; it is simply not advertised.
    ShellEntitlement.Access.ALLOWED -> stringResource(R.string.linux_shell_plan_active)
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
    else -> stringResource(
        R.string.linux_shell_app_installed,
        identity.versionName.orEmpty(),
        FileResolver.formatSizeStatic(info.dataBytes)
    )
}

@Composable
private fun runtimeDetail(info: ShellInfo?): String = when {
    info == null -> stringResource(R.string.linux_shell_runtime_unknown)
    info.runtimeInstalling -> stringResource(R.string.linux_shell_runtime_downloading)
    info.runtimeReady -> stringResource(R.string.linux_shell_runtime_ready)
    else -> stringResource(R.string.linux_shell_runtime_missing)
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
            "download-apk" -> R.string.linux_shell_phase_download_apk
            "verify-apk" -> R.string.linux_shell_phase_verify_apk
            "install-apk" -> R.string.linux_shell_phase_install_apk
            "download-runtime" -> R.string.linux_shell_phase_download_runtime
            "verify-runtime" -> R.string.linux_shell_phase_verify_runtime
            "install-runtime" -> R.string.linux_shell_phase_install_runtime
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
