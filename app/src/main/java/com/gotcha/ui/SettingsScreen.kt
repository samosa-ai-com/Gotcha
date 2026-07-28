package com.gotcha.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gotcha.audio.AudioModel
import com.gotcha.data.Settings
import com.gotcha.data.ThemeMode
import com.gotcha.i18n.Language

/**
 * Settings, shaped like the system Settings app: a home list of categories, each
 * opening its own page ([SettingsPage]), Back returning to the list. Appearance
 * is the one thing left on the home list itself — a single three-way control,
 * applied the moment it is touched, so a page of its own would be emptier than
 * the row that opened it.
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
    packageName: String = "",
    /**
     * Page to open on, instead of the home list. For the unconfigured first run,
     * where the one thing the user must do is enter an API key and a model, and
     * the home list would only ask them to guess which category that lives under.
     * Back still returns to the list, so the rest of Settings stays reachable.
     */
    initialPage: SettingsPage? = null
) {
    // null = the home list. Saveable so a rotation doesn't bounce the user back out.
    var page by rememberSaveable { mutableStateOf(initialPage) }
    val backToHome = { page = null }

    // Back leaves the sub-page for the list; only the list itself exits Settings.
    if (page != null) BackHandler(onBack = backToHome)

    when (page) {
        SettingsPage.PERSONAL_INFO -> PersonalInfoScreen(
            load = load,
            onSave = onSave,
            onBack = backToHome,
            onTestVoice = onTestVoice
        )
        SettingsPage.AI_CONFIG -> AiConfigScreen(
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
        SettingsPage.SPEECH -> SpeechScreen(
            load = load,
            onSave = onSave,
            onBack = backToHome,
            onRefreshAudioModels = onRefreshAudioModels,
            onSamosaSignIn = onSamosaSignIn,
            onSamosaSignOut = onSamosaSignOut
        )
        SettingsPage.PERMISSIONS -> PermissionsScreen(
            packageName = packageName,
            onBack = backToHome
        )
        SettingsPage.SKILLS -> SkillsScreen(
            load = load,
            onSave = onSave,
            onBack = backToHome
        )
        SettingsPage.PROACTIVE -> ProactiveScreen(
            load = load,
            onSave = onSave,
            onBack = backToHome
        )
        SettingsPage.NOTIFICATIONS -> NotificationsScreen(
            load = load,
            onSave = onSave,
            onBack = backToHome
        )
        null -> SettingsHome(
            load = load,
            onBack = onBack,
            onOpenPage = { page = it },
            onThemeChange = onThemeChange
        )
    }
}

/** The settings home list: the Appearance control, then a row per sub-page. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsHome(
    load: () -> Settings,
    onBack: () -> Unit,
    onOpenPage: (SettingsPage) -> Unit,
    onThemeChange: (ThemeMode) -> Unit
) {
    val initial = remember { load() }
    var themeMode by remember { mutableStateOf(initial.themeMode) }

    val overlay = rememberSettingsOverlayState()

    SettingsScaffold(title = "Settings", onBack = onBack, overlay = overlay) {
        // ---- Appearance (applies immediately) ----
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

        // ---- One row per sub-page ----
        SettingsPage.entries.forEachIndexed { index, entry ->
            if (index > 0) HorizontalDivider(thickness = 1.dp)
            SettingsNavRow(
                page = entry,
                onClick = { onOpenPage(entry) },
                modifier = Modifier.testTag(entry.testTag)
            )
        }
    }
}
