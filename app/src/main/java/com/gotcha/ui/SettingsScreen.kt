package com.gotcha.ui

import androidx.activity.compose.BackHandler
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.gotcha.audio.AudioModel
import com.gotcha.data.Settings
import com.gotcha.i18n.Language

/**
 * Settings, shaped like the system Settings app: a home list of categories, each
 * opening its own page ([SettingsPage]), Back returning to the list.
 *
 * Appearance used to be the exception, kept inline as a single three-way control
 * because a page for it would have been emptier than the row that opened it.
 * A theme picker settles that: it is now [AppearanceScreen], first on the list.
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
    /** Applies appearance changes to the running activity, without a restart. */
    onAppearanceChange: (Settings) -> Unit = {},
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
     * Whether the assistive-ball service is currently running. Not read from
     * [load]: the ball can also be dismissed from its own overlay, which stops
     * the service directly, and the switch has to follow that.
     */
    assistiveBallEnabled: Boolean = false,
    /** Asks the host to start or stop the assistive ball. May be refused (no
     *  overlay permission), in which case [assistiveBallEnabled] stays false. */
    onToggleAssistiveBall: (Boolean) -> Unit = {},
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
        SettingsPage.APPEARANCE -> AppearanceScreen(
            load = load,
            onSave = onSave,
            onBack = backToHome,
            onApply = onAppearanceChange
        )
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
        SettingsPage.ASSISTIVE_BALL -> AssistiveBallScreen(
            enabled = assistiveBallEnabled,
            onToggle = onToggleAssistiveBall,
            onBack = backToHome
        )
        SettingsPage.NOTIFICATIONS -> NotificationsScreen(
            load = load,
            onSave = onSave,
            onBack = backToHome
        )
        null -> SettingsHome(
            onBack = onBack,
            onOpenPage = { page = it }
        )
    }
}

/** The settings home list: one row per sub-page. */
@Composable
private fun SettingsHome(
    onBack: () -> Unit,
    onOpenPage: (SettingsPage) -> Unit
) {
    val overlay = rememberSettingsOverlayState()

    SettingsScaffold(title = "Settings", onBack = onBack, overlay = overlay) {
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
