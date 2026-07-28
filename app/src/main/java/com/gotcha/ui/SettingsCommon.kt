package com.gotcha.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Building blocks shared by the settings pages (see [SettingsScreen]). Each page
 * is its own composable in its own file; anything more than one of them needs
 * lives here.
 */

/**
 * A settings sub-page, reached from the settings home list and left with Back —
 * the same shape as the system Settings app. `null` in [SettingsScreen]'s page
 * state means the home list itself.
 *
 * [title] doubles as the row label on the home list and the sub-page's top-bar
 * title, so the two can't drift apart.
 */
enum class SettingsPage(val title: String, val summary: String, val testTag: String) {
    AI_CONFIG(
        "AI Configuration",
        "Provider, models, agent limits",
        "settings_ai_config_row"
    ),
    SPEECH(
        "Speech (TTS / STT)",
        "Voices, transcription, read replies aloud",
        "settings_speech_row"
    ),
    PERMISSIONS(
        "Permissions",
        "What the assistant is allowed to do",
        "settings_permissions_row"
    ),
    SKILLS(
        "Skills / Plugins",
        "Built-in and community skills",
        "settings_skills_row"
    ),
    PROACTIVE(
        "Proactive Assistance",
        "Offers, OTP detection, language",
        "settings_proactive_row"
    )
}

/**
 * A row on the settings home list that opens a sub-page. Uses the same text
 * chevron as the rest of this screen rather than an icon font.
 */
@Composable
fun SettingsNavRow(
    page: SettingsPage,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = page.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = page.summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(text = "›", style = MaterialTheme.typography.titleLarge)
    }
}

/**
 * A transient message shown as a centred overlay on top of the settings content.
 * [sticky] messages stay until replaced (used while an operation is still running).
 */
data class SettingsOverlay(val text: String, val sticky: Boolean = false)

/** How long a non-sticky overlay message stays on screen. */
private const val OVERLAY_DURATION_MS = 2500L

/**
 * Owns the current overlay message and its auto-dismiss timer, so a page can
 * report progress and results without repeating the timer wiring.
 */
@Stable
class SettingsOverlayState {
    var current by mutableStateOf<SettingsOverlay?>(null)
        private set

    /** Show [text]; a [sticky] message stays until another one replaces it. */
    fun show(text: String, sticky: Boolean = false) {
        // A fresh instance every time, so repeating the same text restarts the timer.
        current = SettingsOverlay(text, sticky)
    }

    internal fun clear() {
        current = null
    }
}

@Composable
fun rememberSettingsOverlayState(): SettingsOverlayState {
    val state = remember { SettingsOverlayState() }
    LaunchedEffect(state.current) {
        state.current?.let {
            if (!it.sticky) {
                delay(OVERLAY_DURATION_MS)
                state.clear()
            }
        }
    }
    return state
}

/**
 * The frame every settings page shares: a titled top bar with a back action, a
 * scrolling content column, and the overlay anchored on top of it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScaffold(
    title: String,
    onBack: () -> Unit,
    overlay: SettingsOverlayState,
    content: @Composable ColumnScope.() -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = { TextButton(onClick = onBack) { Text("← Back") } }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content
            )

            // Centered rather than bottom-aligned: under the keyboard area BottomCenter
            // reads as a stray toast instead of feedback attached to the field that
            // triggered the test (PR #60 review).
            OverlayMessage(message = overlay.current, modifier = Modifier.align(Alignment.Center))
        }
    }
}

/**
 * Non-interactive toast-style overlay used for transient feedback
 * (e.g. connection test progress and result). Anchored by the caller.
 */
@Composable
private fun OverlayMessage(message: SettingsOverlay?, modifier: Modifier = Modifier) {
    // Keep the last text around so it stays readable through the fade-out.
    var lastText by remember { mutableStateOf("") }
    message?.let { lastText = it.text }
    AnimatedVisibility(
        visible = message != null,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier
    ) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.inverseSurface,
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            tonalElevation = 6.dp,
            shadowElevation = 6.dp,
            modifier = Modifier
                .padding(horizontal = 24.dp, vertical = 32.dp)
                .testTag("settings_overlay")
        ) {
            Text(
                text = lastText,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)
            )
        }
    }
}

/** Sign-in / sign-out block for Samosa AI, shown by both AI Configuration and Speech. */
@Composable
fun SamosaAuthSection(
    email: String,
    signedIn: Boolean,
    busy: Boolean,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = if (signedIn) "Signed in to Samosa AI" else "Not signed in",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium
        )
        if (signedIn && email.isNotBlank()) {
            Text(
                text = email,
                style = MaterialTheme.typography.bodyMedium
            )
        }
        if (!signedIn) {
            Text(
                text = "Sign in with Google to use Samosa AI. Your OpenAI-compatible " +
                    "settings are kept separately and are unaffected.",
                style = MaterialTheme.typography.bodySmall
            )
        }
        if (signedIn) {
            OutlinedButton(
                onClick = onSignOut,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (busy) "Please wait…" else "Log out") }
        } else {
            Button(
                onClick = onSignIn,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (busy) "Signing in…" else "Sign in with Google") }
        }
    }
}

/** A label with a trailing switch — the settings screens' standard boolean row. */
@Composable
fun SettingsToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    isLarge: Boolean = false,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = if (isLarge) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** Always produce a human-readable error string for a community-skill import failure. */
fun formatImportError(t: Throwable): String {
    val msg = t.message?.takeIf { it.isNotBlank() }
    val causeMsg = t.cause?.message?.takeIf { it.isNotBlank() }
    return when {
        !msg.isNullOrBlank() -> "Import failed: $msg"
        !causeMsg.isNullOrBlank() -> "Import failed: $causeMsg"
        else -> "Import failed: ${t::class.java.simpleName}"
    }
}
