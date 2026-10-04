package com.gotcha.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.gotcha.R
import com.gotcha.tools.TermuxTool
import com.gotcha.tools.ToolResult
import com.gotcha.ui.theme.GotchaMono
import kotlinx.coroutines.launch

/**
 * Guided Termux setup: a live checklist of the four things that must be true for
 * `run_termux_command` to work, each with the action to fix it. The cheap checks
 * (installed / build / permission) are re-read on every resume; the
 * `allow-external-apps` probe runs a real command, so it only happens on demand.
 */
@Composable
fun TermuxSetupScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val tool = remember(context) { TermuxTool(context) }
    val overlay = rememberSettingsOverlayState()
    val scope = rememberCoroutineScope()
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager

    var state by remember { mutableStateOf(TermuxTool.TermuxSetupState.from(tool.status())) }
    var probing by remember { mutableStateOf(false) }
    var probeFeedback by remember { mutableStateOf<String?>(null) }

    // Re-read the cheap checks on every resume — a permission grant or a return from Termux
    // changes them, and the screen cannot know without asking.
    val lifecycleOwner = LocalLifecycleOwner.current
    var resumeSignal by remember { mutableStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumeSignal++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(resumeSignal) {
        state = TermuxTool.TermuxSetupState.from(tool.status())
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        state = TermuxTool.TermuxSetupState.from(tool.status())
        overlay.show(
            context.getString(
                if (granted) {
                    R.string.termux_setup_permission_granted_next_enable_external
                } else {
                    R.string.termux_setup_permission_denied_if_no_dialog
                }
            )
        )
    }

    fun checkConfiguration() {
        if (probing) return
        probing = true
        scope.launch {
            val probe = tool.probeExternalApps()
            state = state.copy(externalAppsEnabled = probe)
            probeFeedback = context.getString(
                when (probe) {
                    TermuxTool.TermuxConfigProbe.CONFIGURED ->
                        R.string.termux_setup_termux_answered_allow_external_apps
                    TermuxTool.TermuxConfigProbe.NOT_CONFIGURED ->
                        R.string.termux_setup_termux_answered_but_allow_external
                    TermuxTool.TermuxConfigProbe.UNKNOWN ->
                        R.string.termux_setup_could_not_confirm_open_termux
                }
            )
            probing = false
        }
    }

    SettingsScaffold(title = stringResource(SettingsPage.TERMUX.title), onBack = onBack, overlay = overlay) {
        Text(
            stringResource(R.string.termux_setup_termux_gives_the_assistant_a),
            style = MaterialTheme.typography.bodySmall
        )

        SetupCheck(
            title = stringResource(R.string.termux_setup_install_termux),
            done = state.installed,
            detail = if (state.installed) {
                stringResource(R.string.termux_setup_installed_version, state.versionName.orEmpty().trim())
            } else {
                stringResource(R.string.termux_setup_not_installed)
            },
            action = if (state.installed) {
                null
            } else {
                SetupAction(stringResource(R.string.termux_setup_action_install_termux_from_fdroid)) {
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(TermuxTool.TERMUX_FDROID_URL))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }.onFailure {
                        overlay.show(context.getString(R.string.termux_setup_could_not_open_the_f))
                    }
                }
            }
        )

        SetupCheck(
            title = stringResource(R.string.termux_setup_check_the_termux_build),
            done = state.pluginApiAvailable,
            detail = stringResource(
                when {
                    !state.installed -> R.string.termux_setup_install_termux_first
                    state.pluginApiAvailable -> R.string.termux_setup_this_build_exposes_the_run
                    else ->
                        R.string.termux_setup_this_build_has_no_run
                }
            )
        )

        SetupCheck(
            title = stringResource(R.string.termux_setup_grant_the_run_commands_permission),
            done = state.permissionGranted,
            detail = stringResource(
                when {
                    !state.installed -> R.string.termux_setup_install_termux_first
                    state.permissionGranted -> R.string.termux_setup_gotcha_may_run_commands_in
                    else ->
                        R.string.termux_setup_termux_must_let_gotcha_run
                }
            ),
            action = when {
                state.permissionGranted -> null
                state.installed && state.pluginApiAvailable ->
                    SetupAction(stringResource(R.string.termux_setup_action_grant_permission)) {
                        permissionLauncher.launch(TermuxTool.PERMISSION_RUN_COMMAND)
                    }
                else -> null
            }
        )

        SetupCheck(
            title = stringResource(R.string.termux_setup_allow_external_apps),
            done = state.externalAppsEnabled == TermuxTool.TermuxConfigProbe.CONFIGURED,
            detail = stringResource(
                when {
                    !state.installed -> R.string.termux_setup_install_termux_first
                    !state.pluginApiAvailable -> R.string.termux_setup_this_build_cannot_run_commands
                    state.externalAppsEnabled == TermuxTool.TermuxConfigProbe.CONFIGURED ->
                        R.string.termux_setup_allow_external_apps_is_enabled
                    state.externalAppsEnabled == TermuxTool.TermuxConfigProbe.NOT_CONFIGURED ->
                        R.string.termux_setup_allow_external_apps_is_not
                    else -> R.string.termux_setup_not_confirmed_yet_open_termux
                }
            ),
            action = if (state.installed) {
                SetupAction(stringResource(R.string.termux_setup_action_open_termux)) {
                    openSpecialAccess(context, ToolResult.TERMUX_ACCESS, context.packageName)
                }
            } else {
                null
            }
        )

        if (state.installed && state.pluginApiAvailable && state.permissionGranted && !state.ready) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    TermuxTool.SETUP_COMMANDS,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = GotchaMono),
                    modifier = Modifier.padding(12.dp)
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        clipboard?.setPrimaryClip(
                            ClipData.newPlainText("Termux setup", TermuxTool.SETUP_COMMANDS)
                        )
                        overlay.show(context.getString(R.string.termux_setup_copied_to_clipboard))
                    },
                    modifier = Modifier.weight(1f)
                ) { Text(stringResource(R.string.termux_setup_copy_commands)) }
                OutlinedButton(
                    onClick = { checkConfiguration() },
                    enabled = !probing,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        stringResource(
                            if (probing) R.string.termux_setup_checking else R.string.termux_setup_check_configuration
                        )
                    )
                }
            }
        }

        probeFeedback?.let { feedback ->
            Text(feedback, style = MaterialTheme.typography.bodySmall)
        }

        if (state.ready) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    stringResource(R.string.termux_setup_termux_is_set_up_try),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }
    }
}

/** A named action for a setup step, kept small so the checklist reads top-down. */
private class SetupAction(val label: String, val onClick: () -> Unit)

/**
 * One row of the checklist: a ✓/○ status marker, title, detail line, and an
 * optional action button that is only offered while the step is incomplete.
 */
@Composable
private fun SetupCheck(
    title: String,
    done: Boolean,
    detail: String,
    action: SetupAction? = null
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = if (done) "✓" else "○",
                color = if (done) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.width(8.dp))
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
        }
        Text(
            detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (action != null) {
            Button(onClick = action.onClick, modifier = Modifier.fillMaxWidth()) {
                Text(action.label)
            }
        }
    }
}
