package com.gotcha.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gotcha.audio.AudioModel
import com.gotcha.data.FeedbackChannel
import com.gotcha.data.Settings
import com.gotcha.i18n.Language
import com.gotcha.ui.tour.TourAnchor
import com.gotcha.ui.tour.tourAnchor

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
     * Fetches the user's remaining Samosa credit (raw float) or null when not
     * signed in / the gateway is unreachable. Shown scaled by ×1000 in the
     * auth section — never the raw value.
     */
    onFetchSamosaCredits: suspend () -> Double? = { null },
    /**
     * Forces a fetch of the server-messages feed (notifications). Bypasses
     * the 6h gate. Returns the new last-fetched-at timestamp (ms), or null
     * if the sync failed. The screen uses this to refresh its "Last synced"
     * label without an extra round-trip through the Settings repo.
     */
    onSyncServerMessages: suspend () -> Long? = { null },
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
    /** Replays the guided setup from the beginning. */
    onStartTour: () -> Unit = {},
    /** Opens the in-app feedback consent sheet (hidden when no form is configured). */
    onSendFeedback: () -> Unit = {},
    /**
     * Which sub-page is open; null is the home list.
     *
     * Hoisted rather than kept here because two things outside this screen need
     * it: the unconfigured first run opens straight on AI Configuration, and the
     * feature tour has to know which page the user is looking at — and to be
     * able to walk them to the next one.
     */
    page: SettingsPage? = null,
    onPageChange: (SettingsPage?) -> Unit = {}
) {
    // Back leaves the sub-page for whatever it hangs off — its hub if it has one,
    // the home list otherwise. Only the list itself exits Settings.
    val backToHome = { onPageChange(page?.parentPage?.invoke()) }

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
            onFetchSamosaCredits = onFetchSamosaCredits,
            onClearLlmCache = onClearLlmCache,
            onClearDebugScreenshots = onClearDebugScreenshots
        )
        SettingsPage.SPEECH -> SpeechScreen(
            load = load,
            onSave = onSave,
            onBack = backToHome,
            onRefreshAudioModels = onRefreshAudioModels,
            onSamosaSignIn = onSamosaSignIn,
            onSamosaSignOut = onSamosaSignOut,
            onFetchSamosaCredits = onFetchSamosaCredits
        )
        SettingsPage.PERMISSIONS -> PermissionsScreen(
            packageName = packageName,
            onBack = backToHome,
            onOpenTermuxSetup = { onPageChange(SettingsPage.TERMUX) }
        )
        SettingsPage.TERMUX -> TermuxSetupScreen(onBack = backToHome)
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
            load = load,
            onSave = onSave,
            enabled = assistiveBallEnabled,
            onToggle = onToggleAssistiveBall,
            onBack = backToHome
        )
        SettingsPage.NOTIFICATIONS -> NotificationsScreen(
            load = load,
            onSave = onSave,
            onBack = backToHome,
            onSyncServerMessages = onSyncServerMessages
        )
        SettingsPage.ABOUT -> AboutScreen(
            onBack = backToHome,
            onOpenPage = onPageChange
        )
        SettingsPage.ABOUT_SAMOSA -> AboutSamosaScreen(
            context = androidx.compose.ui.platform.LocalContext.current,
            onBack = backToHome
        )
        SettingsPage.LEGAL -> LegalScreen(
            context = androidx.compose.ui.platform.LocalContext.current,
            load = load,
            onSave = onSave,
            onBack = backToHome
        )
        null -> SettingsHome(
            onBack = onBack,
            onOpenPage = onPageChange,
            onStartTour = onStartTour,
            onSendFeedback = onSendFeedback
        )
    }
}

/** The settings home list: one row per sub-page, plus the way back into the tour. */
@Composable
private fun SettingsHome(
    onBack: () -> Unit,
    onOpenPage: (SettingsPage) -> Unit,
    onStartTour: () -> Unit,
    onSendFeedback: () -> Unit
) {
    val overlay = rememberSettingsOverlayState()

    SettingsScaffold(title = "Settings", onBack = onBack, overlay = overlay) {
        SettingsPage.topLevel.forEach { entry ->
            HorizontalDivider(thickness = 1.dp)
            SettingsNavRow(
                page = entry,
                onClick = { onOpenPage(entry) },
                modifier = Modifier
                    .testTag(entry.testTag)
                    .then(entry.tourAnchorModifier())
            )
            // Re-entry into the guided setup sits just above About Us, so the menu
            // ends on the two rows a returning user is least likely to need.
            if (entry == SettingsPage.NOTIFICATIONS) {
                HorizontalDivider(thickness = 1.dp)
                FeatureTourRow(onClick = onStartTour)
                // Feedback is the same shape; only rendered when the form URL is
                // configured at build time (gitignored FEEDBACK_* config).
                if (FeedbackChannel.isConfigured()) {
                    HorizontalDivider(thickness = 1.dp)
                    FeedbackRow(onClick = onSendFeedback)
                }
            }
        }
    }
}

/** The anchor the tour spotlights for this row, when it points at one at all. */
@Composable
private fun SettingsPage.tourAnchorModifier(): Modifier = when (this) {
    SettingsPage.PERSONAL_INFO -> Modifier.tourAnchor(TourAnchor.SETTINGS_PERSONAL_INFO)
    SettingsPage.AI_CONFIG -> Modifier.tourAnchor(TourAnchor.SETTINGS_AI_CONFIG)
    SettingsPage.PERMISSIONS -> Modifier.tourAnchor(TourAnchor.SETTINGS_PERMISSIONS)
    else -> Modifier
}

/** Re-entry into the guided setup, shaped like the rows beneath it. */
@Composable
private fun FeatureTourRow(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp)
            .testTag("settings_feature_tour_row"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Feature Tour",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = "Walk through setup again, one step at a time",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(text = "›", style = MaterialTheme.typography.titleLarge)
    }
}

/** In-app feedback entry point, shaped like the rows around it. */
@Composable
private fun FeedbackRow(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp)
            .testTag("settings_feedback_row"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Send Feedback",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = "Tell us what to improve — nothing is sent until you submit",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(text = "›", style = MaterialTheme.typography.titleLarge)
    }
}
