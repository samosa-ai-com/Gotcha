package com.gotcha.ui

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.gotcha.BuildConfig
import com.gotcha.tools.CompanyInfoTool
import com.gotcha.updater.AppUpdateManager
import com.gotcha.updater.UpdateStatus
import kotlinx.coroutines.launch

/**
 * About Us: the hub for everything about who made this app and what using it
 * commits you to. Shaped like the settings home list rather than a content page,
 * so the two things underneath it — the company and the agreements — stay
 * separate reads instead of one long scroll.
 */
@Composable
fun AboutScreen(
    onBack: () -> Unit,
    onOpenPage: (SettingsPage) -> Unit
) {
    val overlay = rememberSettingsOverlayState()

    SettingsScaffold(title = SettingsPage.ABOUT.title, onBack = onBack, overlay = overlay) {
        listOf(SettingsPage.ABOUT_SAMOSA, SettingsPage.LEGAL).forEach { page ->
            HorizontalDivider(thickness = 1.dp)
            SettingsNavRow(
                page = page,
                onClick = { onOpenPage(page) },
                modifier = Modifier.testTag(page.testTag)
            )
        }
        HorizontalDivider(thickness = 1.dp)
        AppUpdateSection()
        HorizontalDivider(thickness = 1.dp)
    }
}

/**
 * The company page: who Samosa AI is, what else they make, and how to reach them.
 *
 * The body is the same bundled asset the `about_samosa_ai` tool reads
 * ([CompanyInfoTool.ABOUT_ASSET]), so what the agent says about the company and
 * what this page shows cannot drift apart. It is rendered with the same small
 * Markdown pass the Legal page uses.
 */
@Composable
fun AboutSamosaScreen(
    context: Context,
    onBack: () -> Unit
) {
    val overlay = rememberSettingsOverlayState()
    val uriHandler = LocalUriHandler.current

    var aboutText by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        aboutText = readAsset(context, CompanyInfoTool.ABOUT_ASSET)
    }

    SettingsScaffold(title = SettingsPage.ABOUT_SAMOSA.title, onBack = onBack, overlay = overlay) {
        Text(
            text = renderLegalMarkdown(aboutText ?: "(loading…)"),
            style = MaterialTheme.typography.bodyMedium
        )

        HorizontalDivider(thickness = 1.dp)

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "Open in your browser",
                style = MaterialTheme.typography.titleMedium
            )
            LINKS.forEach { (label, url) ->
                LinkRow(label = label, url = url, onOpen = uriHandler::openUri)
            }
        }
    }
}

@Composable
private fun LinkRow(label: String, url: String, onOpen: (String) -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.primary,
        textDecoration = TextDecoration.Underline,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen(url) }
            .padding(vertical = 8.dp)
    )
}

/**
 * Kept here rather than parsed out of the asset: the asset is prose the agent
 * reads, and pulling tappable rows out of it would mean the page silently loses
 * links whenever the copy is reworded.
 */
private val LINKS = listOf(
    "Samosa AI website" to "https://samosa-ai.com",
    "About us" to "https://samosa-ai.com/about-us",
    "Gotcha" to "https://samosa-ai.com/gotcha",
    "Gotcha documentation" to "https://samosa-ai.com/gotcha/docs",
    "Pricing" to "https://samosa-ai.com/pricing",
    "Blog" to "https://blog.samosa-ai.com",
    "GitHub" to "https://github.com/Rishabh-Bajpai",
    "Email samosa.ai.com@gmail.com" to "mailto:samosa.ai.com@gmail.com"
)

@Composable
fun AppUpdateSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val updateManager = remember { AppUpdateManager.shared }
    var status by remember { mutableStateOf<UpdateStatus>(UpdateStatus.Idle) }
    var busy by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME &&
                status is UpdateStatus.NeedsInstallPermission &&
                updateManager.canInstall(context)
            ) {
                val needsPermission = status
                if (needsPermission is UpdateStatus.NeedsInstallPermission) {
                    status = UpdateStatus.ReadyToInstall(needsPermission.apkFile)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "App Version & Updates",
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            text = "Gotcha v${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})",
            style = MaterialTheme.typography.bodyMedium
        )

        when (val currentStatus = status) {
            is UpdateStatus.Idle -> {
                Button(onClick = {
                    if (busy) return@Button
                    busy = true
                    scope.launch {
                        try {
                            status = UpdateStatus.Checking
                            status = updateManager.checkForUpdate()
                        } finally {
                            busy = false
                        }
                    }
                }) {
                    Text("Check for Updates")
                }
            }
            is UpdateStatus.Checking -> {
                Text(
                    text = "Checking for updates...",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            is UpdateStatus.UpToDate -> {
                Text(
                    text = "Gotcha is up to date (v${currentStatus.currentVersion}).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            is UpdateStatus.Available -> {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "New update available: v${currentStatus.info.versionName}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    if (currentStatus.info.releaseNotes.isNotEmpty()) {
                        Text(
                            text = currentStatus.info.releaseNotes,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Button(onClick = {
                        if (busy) return@Button
                        busy = true
                        scope.launch {
                            try {
                                status = UpdateStatus.Downloading(0)
                                val res = updateManager.downloadUpdate(
                                    context,
                                    currentStatus.info
                                ) { pct ->
                                    status = UpdateStatus.Downloading(pct)
                                }
                                res.onSuccess { apkFile ->
                                    status = UpdateStatus.ReadyToInstall(apkFile)
                                }.onFailure { err ->
                                    status = UpdateStatus.Error(err.message ?: "Download failed")
                                }
                            } finally {
                                busy = false
                            }
                        }
                    }) {
                        Text("Download Update")
                    }
                }
            }
            is UpdateStatus.Downloading -> {
                Text(
                    text = "Downloading update: ${currentStatus.progressPercent}%",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            is UpdateStatus.ReadyToInstall -> {
                Button(onClick = {
                    if (!updateManager.installUpdate(context, currentStatus.apkFile)) {
                        status = if (updateManager.canInstall(context)) {
                            UpdateStatus.Error("Could not launch the installer.")
                        } else {
                            UpdateStatus.NeedsInstallPermission(currentStatus.apkFile)
                        }
                    }
                }) {
                    Text("Install Update")
                }
            }
            is UpdateStatus.NeedsInstallPermission -> {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "Allow Gotcha to install updates from this source, then return here.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Button(onClick = {
                        updateManager.openInstallPermissionSettings(context)
                    }) {
                        Text("Grant Install Permission")
                    }
                }
            }
            is UpdateStatus.Error -> {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "Error: ${currentStatus.message}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    Button(onClick = {
                        if (busy) return@Button
                        busy = true
                        scope.launch {
                            try {
                                status = UpdateStatus.Checking
                                status = updateManager.checkForUpdate()
                            } finally {
                                busy = false
                            }
                        }
                    }) {
                        Text("Retry Check")
                    }
                }
            }
        }
    }
}
