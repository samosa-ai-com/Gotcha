package com.gotcha.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.gotcha.agent.skills.Skill
import com.gotcha.agent.skills.SkillRegistry
import com.gotcha.audio.AudioModel
import com.gotcha.audio.CompletionFeedback
import com.gotcha.data.Settings
import com.gotcha.data.ThemeMode
import com.gotcha.i18n.Language
import kotlinx.coroutines.launch

/**
 * Settings, shaped like the system Settings app: a home list of categories, each
 * opening its own page ([SettingsPage]). Appearance and Notifications stay on
 * the home list — two controls each, applied the moment they are touched.
 *
 * Every page saves through [onSave]'s mutator, writing only the fields it owns.
 */
@Composable
fun SettingsScreen(
    /** Reads the persisted settings. Each page calls it on entry, so a page always
     *  opens on current storage rather than on a snapshot taken when Settings did. */
    load: () -> Settings,
    /**
     * Persists one page's fields. The page supplies a mutator that copies *only*
     * the fields it owns onto the freshly-loaded [Settings], so saving on one
     * page can't drag another page's half-typed edits into storage with it.
     */
    onSave: ((Settings) -> Settings) -> Unit,
    onTestConnection: suspend (Settings) -> Result<String>,
    onClearLlmCache: () -> Unit,
    onClearDebugScreenshots: () -> Unit,
    onBack: () -> Unit,
    onThemeChange: (ThemeMode) -> Unit = {},
    onRefreshAudioModels: suspend (Settings) -> Pair<List<AudioModel>, List<AudioModel>> = {
        Pair(emptyList(), emptyList())
    },
    onRefreshChatModels: suspend (Settings) -> Result<List<String>> = {
        Result.failure(Exception("Not available"))
    },
    /** Runs the Samosa Google Sign-In flow; returns (email, sessionToken) or an error. */
    onSamosaSignIn: suspend () -> Result<Pair<String, String>> = {
        Result.failure(Exception("Not available"))
    },
    /** Logs out of Samosa (clears JWT + Google state). */
    onSamosaSignOut: suspend () -> Unit = {},
    /**
     * Speaks the call-started phrase through the host's TTS engine and reports
     * whether the requested language was actually used. Returning null means
     * TTS isn't configured and the button should be a no-op. The default
     * (synchronous, always-true) lets callers ignore voice testing.
     */
    onTestVoice: suspend (Language) -> Boolean? = { null },
    packageName: String = ""
) {
    // null = the home list. Saveable so a rotation doesn't bounce the user back out.
    var page by rememberSaveable { mutableStateOf<SettingsPage?>(null) }
    val backToHome = { page = null }

    when (page) {
        SettingsPage.AI_CONFIG -> {
            BackHandler(onBack = backToHome)
            AiConfigScreen(
                load = load,
                onSave = onSave,
                onBack = backToHome,
                onTestConnection = onTestConnection,
                onRefreshChatModels = onRefreshChatModels,
                onSamosaSignIn = onSamosaSignIn,
                onSamosaSignOut = onSamosaSignOut,
                onClearLlmCache = onClearLlmCache,
                onClearDebugScreenshots = onClearDebugScreenshots
            )
        }
        SettingsPage.SPEECH -> {
            BackHandler(onBack = backToHome)
            SpeechScreen(
                load = load,
                onSave = onSave,
                onBack = backToHome,
                onRefreshAudioModels = onRefreshAudioModels,
                onSamosaSignIn = onSamosaSignIn,
                onSamosaSignOut = onSamosaSignOut
            )
        }
        else -> SettingsHome(
            load = load,
            onSave = onSave,
            onBack = onBack,
            onOpenPage = { page = it },
            onThemeChange = onThemeChange,
            onTestVoice = onTestVoice,
            packageName = packageName
        )
    }
}

/** The settings home list: the immediate toggles, then a row per sub-page. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsHome(
    load: () -> Settings,
    onSave: ((Settings) -> Settings) -> Unit,
    onBack: () -> Unit,
    onOpenPage: (SettingsPage) -> Unit,
    onThemeChange: (ThemeMode) -> Unit,
    onTestVoice: suspend (Language) -> Boolean?,
    packageName: String
) {
    val initial = remember { load() }
    var notifyVibration by remember { mutableStateOf(initial.notifyVibrationEnabled) }
    var notifyChime by remember { mutableStateOf(initial.notifyChimeEnabled) }
    var themeMode by remember { mutableStateOf(initial.themeMode) }
    var disabledSkills by remember { mutableStateOf(initial.disabledSkills) }
    var proactiveEnabled by remember { mutableStateOf(initial.proactiveEnabled) }
    var proactiveScanScreen by remember { mutableStateOf(initial.proactiveScanScreen) }
    var proactiveScanClipboard by remember { mutableStateOf(initial.proactiveScanClipboard) }
    var proactiveScanNotifications by remember { mutableStateOf(initial.proactiveScanNotifications) }
    var proactiveOtpEnabled by remember { mutableStateOf(initial.proactiveOtpEnabled) }
    var proactiveAutoCopyOtp by remember { mutableStateOf(initial.proactiveAutoCopyOtp) }
    var preferredLanguage by remember { mutableStateOf(initial.preferredLanguage) }
    var preferredCurrency by remember { mutableStateOf(initial.preferredCurrency) }
    var testingVoice by remember { mutableStateOf(false) }

    /** Last [Language] whose voice data was reported missing, or null when not shown. */
    var voiceDataMissing by remember { mutableStateOf<Language?>(null) }
    var communitySkillHosts by remember { mutableStateOf(initial.communitySkillHosts) }
    var communitySkillUrl by remember { mutableStateOf("") }
    var communitySkillPasteJson by remember { mutableStateOf("") }
    var communityImportBusy by remember { mutableStateOf(false) }
    var communitySkillRefreshTick by remember { mutableStateOf(0) }
    var communitySkillToDelete by remember { mutableStateOf<Skill?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var skillsExpanded by remember { mutableStateOf(false) }
    var proactiveExpanded by remember { mutableStateOf(false) }
    var languageExpanded by remember { mutableStateOf(false) }
    var currencyExpanded by remember { mutableStateOf(false) }

    val overlay = rememberSettingsOverlayState()
    val scope = rememberCoroutineScope()
    val localContext = LocalContext.current
    androidx.compose.runtime.LaunchedEffect(Unit) {
        // Defense-in-depth: ChatViewModel also calls SkillRegistry.init, but
        // Settings can be opened before the chat screen is ever shown.
        SkillRegistry.bootstrap(localContext)
    }

    /** The Skills section's fields, copied onto [base]. */
    fun applySkills(base: Settings) = base.copy(
        disabledSkills = disabledSkills,
        communitySkillHosts = communitySkillHosts
    )

    /** The Proactive Assistance section's fields, copied onto [base]. */
    fun applyProactive(base: Settings) = base.copy(
        proactiveEnabled = proactiveEnabled,
        proactiveScanScreen = proactiveScanScreen,
        proactiveScanClipboard = proactiveScanClipboard,
        proactiveScanNotifications = proactiveScanNotifications,
        proactiveOtpEnabled = proactiveOtpEnabled,
        proactiveAutoCopyOtp = proactiveAutoCopyOtp,
        preferredLanguage = preferredLanguage,
        preferredCurrency = preferredCurrency
    )

    SettingsScaffold(title = "Settings", onBack = onBack, overlay = overlay) {
        // ---- Appearance (always visible, applies immediately) ----
        Text(
            "Appearance",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            ThemeMode.values().forEachIndexed { index, mode ->
                SegmentedButton(
                    selected = themeMode == mode,
                    onClick = {
                        themeMode = mode
                        onThemeChange(mode)
                    },
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = ThemeMode.values().size
                    )
                ) { Text(mode.label) }
            }
        }

        HorizontalDivider(thickness = 1.dp)

        // ---- Notifications (always visible, applies immediately) ----
        Text(
            "Notifications",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            "Played as soon as a reply arrives. Turn both off for no alert.",
            style = MaterialTheme.typography.bodySmall
        )
        // Switching one on plays it once, so the user knows what to expect.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Vibration", style = MaterialTheme.typography.bodyLarge)
            Switch(
                checked = notifyVibration,
                onCheckedChange = {
                    notifyVibration = it
                    onSave { s -> s.copy(notifyVibrationEnabled = it) }
                    if (it) CompletionFeedback.replyArrived(localContext, vibrate = true, chime = false)
                },
                modifier = Modifier.testTag("settings_notify_vibration")
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Chime", style = MaterialTheme.typography.bodyLarge)
            Switch(
                checked = notifyChime,
                onCheckedChange = {
                    notifyChime = it
                    onSave { s -> s.copy(notifyChimeEnabled = it) }
                    if (it) CompletionFeedback.replyArrived(localContext, vibrate = false, chime = true)
                },
                modifier = Modifier.testTag("settings_notify_chime")
            )
        }

        HorizontalDivider(thickness = 1.dp)

        SettingsNavRow(
            page = SettingsPage.AI_CONFIG,
            onClick = { onOpenPage(SettingsPage.AI_CONFIG) },
            modifier = Modifier.testTag("settings_ai_config_row")
        )

        HorizontalDivider(thickness = 1.dp)

        SettingsNavRow(
            page = SettingsPage.SPEECH,
            onClick = { onOpenPage(SettingsPage.SPEECH) }
        )

        HorizontalDivider(thickness = 1.dp)

        // ---- Permissions ----
        PermissionsSection(packageName = packageName)

        HorizontalDivider(thickness = 1.dp)

        // ---- Skills (collapsible, collapsed by default) ----
        SectionHeader(
            title = "Skills / Plugins",
            expanded = skillsExpanded,
            onToggle = { skillsExpanded = !skillsExpanded }
        )
        AnimatedVisibility(visible = skillsExpanded) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val allSkills = SkillRegistry.getAllSkills()
                if (allSkills.isEmpty()) {
                    Text("No skills loaded.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    allSkills.forEach { skill ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                Text(
                                    skill.id,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Bold
                                )
                                if (skill.description.isNotBlank()) {
                                    Text(skill.description, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            Switch(
                                checked = !disabledSkills.contains(skill.id),
                                onCheckedChange = { enabled ->
                                    disabledSkills = if (enabled) {
                                        disabledSkills - skill.id
                                    } else {
                                        disabledSkills + skill.id
                                    }
                                    onSave { applySkills(it) }
                                }
                            )
                        }
                    }
                }

                HorizontalDivider(thickness = 1.dp)

                // ---- Community Skills ----
                Text(
                    "Community Skills",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Import skills from samosa-ai.example or paste JSON. " +
                        "Community skills appear in the agent's system prompt " +
                        "as advisory guidance.",
                    style = MaterialTheme.typography.bodySmall
                )

                OutlinedTextField(
                    value = communitySkillUrl,
                    onValueChange = { communitySkillUrl = it.trim() },
                    label = { Text("Skill URL (https://samosa-ai.example/...)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = {
                        if (communityImportBusy) return@Button
                        val url = communitySkillUrl.trim()
                        if (url.isEmpty()) {
                            overlay.show("Enter a URL first.")
                            return@Button
                        }
                        communityImportBusy = true
                        overlay.show("Fetching skill…", sticky = true)
                        scope.launch {
                            val result = runCatching {
                                val hosts = communitySkillHosts
                                SkillRegistry.importCommunityFromUrl(url, hosts)
                            }
                            communityImportBusy = false
                            result.onSuccess { skill ->
                                communitySkillUrl = ""
                                communitySkillRefreshTick++
                                overlay.show("Imported '${skill.id}'.")
                            }.onFailure { e ->
                                overlay.show(formatImportError(e))
                            }
                        }
                    },
                    enabled = !communityImportBusy,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Import from URL") }

                var pasteOpen by remember { mutableStateOf(false) }
                OutlinedButton(
                    onClick = { pasteOpen = true },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Paste JSON…") }
                if (pasteOpen) {
                    androidx.compose.ui.window.Dialog(onDismissRequest = {
                        pasteOpen = false
                        communitySkillPasteJson = ""
                    }) {
                        Surface(shape = MaterialTheme.shapes.medium) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    "Paste community skill JSON",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                OutlinedTextField(
                                    value = communitySkillPasteJson,
                                    onValueChange = { communitySkillPasteJson = it },
                                    label = { Text("Skill JSON") },
                                    modifier = Modifier.fillMaxWidth().height(220.dp),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
                                )
                                Row(
                                    horizontalArrangement = Arrangement.End,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    TextButton(onClick = {
                                        pasteOpen = false
                                        communitySkillPasteJson = ""
                                    }) { Text("Cancel") }
                                    TextButton(onClick = {
                                        val src = communitySkillPasteJson.trim()
                                        if (src.isEmpty()) {
                                            overlay.show("Paste JSON first.")
                                            return@TextButton
                                        }
                                        communityImportBusy = true
                                        pasteOpen = false
                                        overlay.show("Importing skill…", sticky = true)
                                        scope.launch {
                                            val result = runCatching {
                                                SkillRegistry.importCommunity(src)
                                            }
                                            communityImportBusy = false
                                            communitySkillPasteJson = ""
                                            result.onSuccess { skill ->
                                                communitySkillRefreshTick++
                                                overlay.show(
                                                    "Imported '${skill.id}'."
                                                )
                                            }.onFailure { e ->
                                                overlay.show(formatImportError(e))
                                            }
                                        }
                                    }) { Text("Import") }
                                }
                            }
                        }
                    }
                }

                // ---- Imported list ----
                val communitySkills = remember(communitySkillRefreshTick) {
                    SkillRegistry.getCommunitySkills()
                }
                if (communitySkills.isEmpty()) {
                    Text(
                        "No community skills imported yet.",
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    communitySkills.forEach { skill ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                    Text(
                                        if (skill.title.isNotBlank()) skill.title else skill.id,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        "id: ${skill.id}",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    if (skill.description.isNotBlank()) {
                                        Text(
                                            skill.description,
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                }
                                Switch(
                                    checked = !disabledSkills.contains(skill.id),
                                    onCheckedChange = { enabled ->
                                        disabledSkills = if (enabled) {
                                            disabledSkills - skill.id
                                        } else {
                                            disabledSkills + skill.id
                                        }
                                        onSave { applySkills(it) }
                                    }
                                )
                                IconButton(
                                    onClick = { communitySkillToDelete = skill }
                                ) {
                                    Icon(
                                        Icons.Outlined.Delete,
                                        contentDescription = "Delete ${skill.id}",
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }
                }

                // ---- Delete confirmation dialog ----
                communitySkillToDelete?.let { pending ->
                    AlertDialog(
                        onDismissRequest = { communitySkillToDelete = null },
                        title = { Text("Delete community skill?") },
                        text = {
                            Text(
                                "Are you sure you want to permanently delete " +
                                    "\"${pending.id}\"? The skill will be removed from " +
                                    "this device and the agent will no longer have " +
                                    "access to it. This action cannot be undone."
                            )
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    val id = pending.id
                                    communitySkillToDelete = null
                                    scope.launch {
                                        runCatching { SkillRegistry.removeCommunity(id) }
                                        disabledSkills = disabledSkills - id
                                        onSave { applySkills(it) }
                                        communitySkillRefreshTick++
                                        overlay.show("Deleted '$id'.")
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error
                                )
                            ) { Text("Delete") }
                        },
                        dismissButton = {
                            TextButton(onClick = { communitySkillToDelete = null }) {
                                Text("Cancel")
                            }
                        }
                    )
                }

                HorizontalDivider(thickness = 1.dp)

                // ---- Host allowlist ----
                Text(
                    "Allowed community skill hosts",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Only HTTPS hosts in this list can be fetched. " +
                        "Default: samosa-ai.example.",
                    style = MaterialTheme.typography.bodySmall
                )
                communitySkillHosts.forEach { host ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(host, modifier = Modifier.weight(1f))
                        TextButton(onClick = {
                            communitySkillHosts = communitySkillHosts - host
                            onSave { applySkills(it) }
                        }) { Text("Remove") }
                    }
                }
                var newHost by remember { mutableStateOf("") }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = newHost,
                        onValueChange = { newHost = it.trim() },
                        label = { Text("Add host") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = {
                            val h = newHost.trim()
                            if (h.isEmpty()) return@Button
                            communitySkillHosts = communitySkillHosts + h
                            onSave { applySkills(it) }
                            newHost = ""
                        }
                    ) { Text("Add") }
                }
            }
        }

        HorizontalDivider(thickness = 1.dp)

        // ---- Proactive Assistance ----
        SectionHeader(
            title = "Proactive Assistance",
            expanded = proactiveExpanded,
            onToggle = { proactiveExpanded = !proactiveExpanded }
        )
        AnimatedVisibility(visible = proactiveExpanded) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SettingsToggleRow(
                    label = "Master Proactive Offers",
                    checked = proactiveEnabled,
                    onCheckedChange = { proactiveEnabled = it },
                    isLarge = true
                )
                if (proactiveEnabled) {
                    SettingsToggleRow(
                        label = "Scan Screen Content",
                        checked = proactiveScanScreen,
                        onCheckedChange = { proactiveScanScreen = it }
                    )
                    SettingsToggleRow(
                        label = "Scan Clipboard",
                        checked = proactiveScanClipboard,
                        onCheckedChange = { proactiveScanClipboard = it }
                    )
                    SettingsToggleRow(
                        label = "Scan Notifications",
                        checked = proactiveScanNotifications,
                        onCheckedChange = { proactiveScanNotifications = it }
                    )
                    SettingsToggleRow(
                        label = "Detect OTP / Codes",
                        checked = proactiveOtpEnabled,
                        onCheckedChange = { proactiveOtpEnabled = it }
                    )
                    SettingsToggleRow(
                        label = "Auto-Copy OTP to Clipboard",
                        checked = proactiveAutoCopyOtp,
                        onCheckedChange = { proactiveAutoCopyOtp = it }
                    )

                    val languages = Language.labels
                    ExposedDropdownMenuBox(
                        expanded = languageExpanded,
                        onExpandedChange = { languageExpanded = it }
                    ) {
                        OutlinedTextField(
                            value = preferredLanguage,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Preferred Language") },
                            trailingIcon = {
                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = languageExpanded)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor()
                        )
                        ExposedDropdownMenu(
                            expanded = languageExpanded,
                            onDismissRequest = { languageExpanded = false }
                        ) {
                            languages.forEach { lang ->
                                DropdownMenuItem(
                                    text = { Text(lang) },
                                    onClick = {
                                        preferredLanguage = lang
                                        languageExpanded = false
                                    }
                                )
                            }
                        }
                    }
                    OutlinedButton(
                        onClick = {
                            testingVoice = true
                            scope.launch {
                                val lang = Language.fromLabel(preferredLanguage)
                                // Track which language triggered the missing-data state so
                                // rapid language-switch clicks don't surface a stale dialog.
                                val ok = onTestVoice(lang)
                                voiceDataMissing = if (ok == false) lang else null
                                testingVoice = false
                            }
                        },
                        enabled = !testingVoice,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (testingVoice) "Playing…" else "Test voice")
                    }
                    voiceDataMissing?.let { missingLang ->
                        AlertDialog(
                            onDismissRequest = { voiceDataMissing = null },
                            title = { Text("Voice data not installed") },
                            text = {
                                Text(
                                    "Your device doesn't have Android's built-in voice for " +
                                        "${missingLang.label}. It was spoken in English instead. " +
                                        "Install the voice data to fix pronunciation."
                                )
                            },
                            confirmButton = {
                                Button(
                                    onClick = {
                                        voiceDataMissing = null
                                        try {
                                            localContext.startActivity(
                                                android.content.Intent(
                                                    android.speech.tts.TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA
                                                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                            )
                                        } catch (_: Exception) { }
                                    }
                                ) { Text("Install") }
                            },
                            dismissButton = {
                                TextButton(onClick = { voiceDataMissing = null }) { Text("Cancel") }
                            }
                        )
                    }

                    val currencies = listOf("USD", "EUR", "GBP", "INR", "CAD", "AUD", "JPY", "CNY")
                    ExposedDropdownMenuBox(
                        expanded = currencyExpanded,
                        onExpandedChange = { currencyExpanded = it }
                    ) {
                        OutlinedTextField(
                            value = preferredCurrency,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Preferred Currency") },
                            trailingIcon = {
                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = currencyExpanded)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor()
                        )
                        ExposedDropdownMenu(
                            expanded = currencyExpanded,
                            onDismissRequest = { currencyExpanded = false }
                        ) {
                            currencies.forEach { curr ->
                                DropdownMenuItem(
                                    text = { Text(curr) },
                                    onClick = {
                                        preferredCurrency = curr
                                        currencyExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }
                Button(
                    onClick = {
                        onSave { applyProactive(it) }
                        overlay.show("Saved Proactive Settings.")
                        status = null
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Save Proactive Settings") }
            }
        }

        // Status text
        status?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun SectionHeader(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = if (expanded) "▼ " else "▶ ",
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium
        )
    }
}
