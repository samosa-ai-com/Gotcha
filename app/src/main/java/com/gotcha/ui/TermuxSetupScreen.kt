package com.gotcha.ui

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
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
            if (granted) {
                "Permission granted — next, enable external apps in Termux."
            } else {
                "Permission denied. If no dialog appeared, Termux was installed after Gotcha — " +
                    "update or reinstall Gotcha so Android can grant it."
            }
        )
    }

    fun checkConfiguration() {
        if (probing) return
        probing = true
        scope.launch {
            val probe = tool.probeExternalApps()
            state = state.copy(externalAppsEnabled = probe)
            probeFeedback = when (probe) {
                TermuxTool.TermuxConfigProbe.CONFIGURED ->
                    "Termux answered — allow-external-apps is enabled."
                TermuxTool.TermuxConfigProbe.NOT_CONFIGURED ->
                    "Termux answered, but allow-external-apps is not enabled yet."
                TermuxTool.TermuxConfigProbe.UNKNOWN ->
                    "Could not confirm. Open Termux once, set the property, then check again."
            }
            probing = false
        }
    }

    SettingsScaffold(title = "Termux (Linux shell)", onBack = onBack, overlay = overlay) {
        Text(
            "Termux gives the assistant a full Linux shell — installing packages, running " +
                "scripts and tools no phone ships with. This page walks you through the one-time " +
                "setup so you don't have to look anything up.",
            style = MaterialTheme.typography.bodySmall
        )

        SetupCheck(
            title = "Install Termux",
            done = state.installed,
            detail = if (state.installed) {
                "Termux ${state.versionName.orEmpty().trim()} is installed."
            } else {
                "Termux is not installed."
            },
            action = if (state.installed) {
                null
            } else {
                SetupAction("Install Termux from F-Droid") {
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(TermuxTool.TERMUX_FDROID_URL))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                }
            }
        )

        SetupCheck(
            title = "Check the Termux build",
            done = state.pluginApiAvailable,
            detail = when {
                !state.installed -> "Install Termux first."
                state.pluginApiAvailable -> "This build exposes the Run Commands API Gotcha needs."
                else ->
                    "This build has no Run Commands API — that is the Google Play build, which " +
                        "removed it. Install the F-Droid or GitHub build instead."
            }
        )

        SetupCheck(
            title = "Grant the Run Commands permission",
            done = state.permissionGranted,
            detail = when {
                !state.installed -> "Install Termux first."
                state.permissionGranted -> "Gotcha may run commands in Termux."
                else ->
                    "Termux must let Gotcha run commands. If no permission dialog appears, " +
                        "Termux was installed after Gotcha — update or reinstall Gotcha."
            },
            action = if (state.permissionGranted) {
                null
            } else {
                SetupAction("Grant permission") {
                    permissionLauncher.launch(TermuxTool.PERMISSION_RUN_COMMAND)
                }
            }
        )

        SetupCheck(
            title = "Allow external apps",
            done = state.externalAppsEnabled == TermuxTool.TermuxConfigProbe.CONFIGURED,
            detail = when {
                !state.installed -> "Install Termux first."
                !state.pluginApiAvailable -> "This build cannot run commands — see step 2."
                state.externalAppsEnabled == TermuxTool.TermuxConfigProbe.CONFIGURED ->
                    "allow-external-apps is enabled — Gotcha can reach Termux."
                state.externalAppsEnabled == TermuxTool.TermuxConfigProbe.NOT_CONFIGURED ->
                    "allow-external-apps is not enabled. Open Termux and run the two lines below."
                else -> "Not confirmed yet. Open Termux, run the two lines below, then check again."
            },
            action = if (state.installed) {
                SetupAction("Open Termux") {
                    openSpecialAccess(context, ToolResult.TERMUX_ACCESS, context.packageName)
                }
            } else {
                null
            }
        )

        if (state.installed && state.pluginApiAvailable && !state.ready) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "echo 'allow-external-apps=true' >> ~/.termux/termux.properties\n" +
                        "termux-reload-settings",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = GotchaMono),
                    modifier = Modifier.padding(12.dp)
                )
            }
            OutlinedButton(
                onClick = { checkConfiguration() },
                enabled = !probing,
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (probing) "Checking…" else "Check configuration") }
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
                    "Termux is set up. Try asking the assistant to run a command in Termux.",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }
    }
}

/** A named action for a setup step, kept small so the checklist reads top-down. */
private data class SetupAction(val label: String, val onClick: () -> Unit)

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
