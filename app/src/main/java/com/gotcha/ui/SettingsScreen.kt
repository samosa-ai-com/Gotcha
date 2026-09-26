package com.gotcha.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import kotlinx.coroutines.delay

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
/** How long a search highlight waits for its field before lapsing unshown. */
private const val HIGHLIGHT_LAPSE_MS = 3_000L

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
    /** Fetches the user's full profile (including tier, tags, referral) or null when unavailable. */
    onFetchSamosaProfile: suspend () -> com.gotcha.auth.SamosaUser? = { null },
    /** Claims an invite code via the auth manager. */
    onClaimReferral: suspend (String) -> Result<Unit> = {
        Result.failure(Exception("Not supported"))
    },
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
    onPageChange: (SettingsPage?) -> Unit = {},
    /**
     * The [SettingsField.testTag] a search result opened [page] on, or null.
     * Hoisted beside [page] because both change in the one tap; cleared through
     * [onHighlightConsumed] once the page has shown it, and by the host on any
     * other page change, so Back never returns to a stale highlight.
     */
    highlightField: String? = null,
    onHighlightConsumed: () -> Unit = {},
    /** Opens a page from a search result, pointed at the field the query named. */
    onOpenSearchResult: (SettingsPage, String?) -> Unit = { target, _ -> onPageChange(target) }
) {
    // Back leaves the sub-page for whatever it hangs off — its hub if it has one,
    // the home list otherwise. Only the list itself exits Settings.
    val backToHome = { onPageChange(page?.parentPage?.invoke()) }

    if (page != null) BackHandler(onBack = backToHome)

    // A field the page never shows — the OTP switch while proactive offers are
    // off, the API key while Samosa is the provider — has nothing to consume the
    // highlight, so it lapses on its own rather than waiting for a later visit.
    LaunchedEffect(page, highlightField) {
        if (highlightField != null) {
            delay(HIGHLIGHT_LAPSE_MS)
            onHighlightConsumed()
        }
    }
    // Leaving Settings altogether ends the visit the highlight belonged to.
    DisposableEffect(Unit) { onDispose { onHighlightConsumed() } }
    val highlight = remember(page, highlightField) {
        SettingsHighlight(
            field = page?.let { p -> highlightField?.let { settingsFieldFor(p, it) } },
            onConsumed = onHighlightConsumed
        )
    }

    CompositionLocalProvider(LocalSettingsHighlight provides highlight) {
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
                onOpenLanguage = { onPageChange(SettingsPage.LANGUAGE) }
            )
            SettingsPage.LANGUAGE -> LanguageScreen(
                load = load,
                onSave = onSave,
                onBack = backToHome,
                onTestVoice = onTestVoice
            )
            SettingsPage.AI -> AiHubScreen(
                onBack = backToHome,
                onOpenPage = onPageChange
            )
            SettingsPage.AI_CONFIG -> AiConfigScreen(
                load = load,
                onSave = onSave,
                onBack = backToHome,
                onTestConnection = onTestConnection,
                onRefreshChatModels = onRefreshChatModels,
                onSamosaSignIn = onSamosaSignIn,
                onSamosaSignOut = onSamosaSignOut,
                onFetchSamosaProfile = onFetchSamosaProfile,
                onClaimReferral = onClaimReferral,
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
                onFetchSamosaProfile = onFetchSamosaProfile,
                onClaimReferral = onClaimReferral
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
                onOpenSearchResult = onOpenSearchResult,
                onStartTour = onStartTour,
                onSendFeedback = onSendFeedback
            )
        }
    }
}

/**
 * The settings home list: one row per sub-page, plus the way back into the tour.
 *
 * With 15 pages between them holding well over a hundred controls, the list on
 * its own answers "where is the setting for X?" with a scroll and a guess, so a
 * search field sits pinned above it. A query replaces the list with matching
 * pages — including the ones nested inside a hub, which the list never shows —
 * and an empty query leaves the list exactly as it was.
 */
@Composable
private fun SettingsHome(
    onBack: () -> Unit,
    onOpenPage: (SettingsPage) -> Unit,
    onOpenSearchResult: (SettingsPage, String?) -> Unit,
    onStartTour: () -> Unit,
    onSendFeedback: () -> Unit
) {
    val overlay = rememberSettingsOverlayState()
    var query by rememberSaveable { mutableStateOf("") }

    // Opening a page ends the search: coming back to a list still filtered by a
    // query typed minutes ago reads as a list that has lost most of its rows.
    val openPage = { page: SettingsPage ->
        query = ""
        onOpenPage(page)
    }
    val openResult = { result: SettingsSearchResult ->
        query = ""
        onOpenSearchResult(result.page, result.field?.testTag)
    }

    SettingsScaffold(
        title = "Settings",
        onBack = onBack,
        overlay = overlay,
        header = { SettingsSearchField(query = query, onQueryChange = { query = it }) }
    ) {
        if (query.isBlank()) {
            SettingsHomeRows(
                onOpenPage = openPage,
                onStartTour = onStartTour,
                onSendFeedback = onSendFeedback
            )
        } else {
            SettingsSearchResults(query = query, onOpenResult = openResult)
        }
    }
}

/** The search field pinned above the home list. */
@Composable
private fun SettingsSearchField(query: String, onQueryChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("settings_search"),
        singleLine = true,
        placeholder = { Text("Search settings") },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(
                    onClick = { onQueryChange("") },
                    modifier = Modifier.testTag("settings_search_clear")
                ) { Icon(Icons.Filled.Close, contentDescription = "Clear search") }
            }
        }
    )
}

/** The unfiltered list: every top-level page, then the tour and feedback rows. */
@Composable
private fun ColumnScope.SettingsHomeRows(
    onOpenPage: (SettingsPage) -> Unit,
    onStartTour: () -> Unit,
    onSendFeedback: () -> Unit
) {
    SettingsPage.topLevel.forEach { entry ->
        HorizontalDivider(thickness = 1.dp)
        SettingsNavRow(
            page = entry,
            onClick = { onOpenPage(entry) },
            modifier = Modifier
                .testTag(entry.testTag)
                .then(entry.tourAnchorModifier())
        )
        // Re-entry into the guided setup sits just above About, so the menu
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

/**
 * Pages matching the query. Rows keep their home-list [SettingsPage.testTag] so
 * a result is the same row by every name the tour and the tests know it by; a
 * nested page shows its hub in the label instead of a tour anchor it would be
 * spotlighting in the wrong place, and a result that named one control ends
 * its label with that control — which is where the page will open.
 */
@Composable
private fun SettingsSearchResults(query: String, onOpenResult: (SettingsSearchResult) -> Unit) {
    val results = filterSettings(query)
    if (results.isEmpty()) {
        Text(
            text = "No settings match “$query”",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 24.dp)
                .testTag("settings_search_empty")
        )
        return
    }
    results.forEach { result ->
        HorizontalDivider(thickness = 1.dp)
        SettingsNavRow(
            page = result.page,
            onClick = { onOpenResult(result) },
            modifier = Modifier.testTag(result.page.testTag),
            title = result.label
        )
    }
}

/** The anchor the tour spotlights for this row, when it points at one at all. */
@Composable
private fun SettingsPage.tourAnchorModifier(): Modifier = when (this) {
    SettingsPage.PERSONAL_INFO -> Modifier.tourAnchor(TourAnchor.SETTINGS_PERSONAL_INFO)
    SettingsPage.AI -> Modifier.tourAnchor(TourAnchor.SETTINGS_AI)
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
