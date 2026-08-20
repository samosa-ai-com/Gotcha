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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gotcha.auth.ReferralClipboardHelper
import com.gotcha.auth.SamosaTier
import com.gotcha.auth.SamosaUser
import kotlinx.coroutines.delay
import java.text.NumberFormat
import java.time.Duration
import java.time.Instant
import kotlin.math.round

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
/**
 * Declaration order is the order of the home list; nothing reads the ordinal, so
 * this is the only place the menu is arranged. What the assistant *is* comes
 * first — who it thinks you are, which model it runs, what it may touch — and
 * the page about how the app presents itself sits at the end.
 *
 * Most pages hang directly off the home list. The exceptions declare a [parent]:
 * they are full pages, routed and titled like any other, but reached from inside
 * that parent instead of from the home list. [ABOUT] is the only such hub today,
 * collecting "who made this app and what did I agree to" into one row.
 */
enum class SettingsPage(
    val title: String,
    val summary: String,
    val testTag: String,
    /**
     * The page this one is reached from, or null for the home list. Doubles as
     * the Back target, so a nested page returns to its hub rather than skipping
     * out to the home list. Declared lazily because an enum entry cannot name a
     * later one in its own constructor call.
     */
    val parentPage: () -> SettingsPage? = { null }
) {
    PERSONAL_INFO(
        "Personal Info",
        "Who you are, language, currency, reply style",
        "settings_personal_info_row"
    ),
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
    TERMUX(
        "Termux (Linux shell)",
        "Run commands in a real Linux shell",
        "settings_termux_row"
    ),
    SKILLS(
        "Skills / Plugins",
        "Built-in and community skills",
        "settings_skills_row"
    ),
    PROACTIVE(
        "Proactive Assistance",
        "Offers, OTP detection, what may be scanned",
        "settings_proactive_row"
    ),
    ASSISTIVE_BALL(
        "Assistive Ball",
        "Floating ball over other apps, hands-free calls",
        "settings_assistive_ball_row"
    ),
    APPEARANCE(
        "Appearance",
        "How the app looks",
        "settings_appearance_row"
    ),
    NOTIFICATIONS(
        "Notifications",
        "How you're alerted when a reply arrives",
        "settings_notifications_row"
    ),
    ABOUT(
        "About Us",
        "Samosa AI, other products, legal, contact",
        "settings_about_row"
    ),
    ABOUT_SAMOSA(
        "About Samosa AI",
        "Mission, products, pricing, developers",
        "settings_about_samosa_row",
        { ABOUT }
    ),
    LEGAL(
        "Legal",
        "Terms, disclaimer, data retention",
        "settings_legal_row",
        { ABOUT }
    );

    /** Pages the home list shows: everything that isn't nested inside a hub. */
    companion object {
        val topLevel: List<SettingsPage> get() = entries.filter { it.parentPage() == null }
    }
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
                // See ChatScreen: glass on a skin that has a wallpaper, and
                // indistinguishable from the default on one that doesn't.
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                ),
                title = { Text(title) },
                navigationIcon = {
                    TextButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("settings_back")
                    ) { Text("← Back") }
                }
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

/**
 * The credit figure shown to the user: the raw float scaled by ×1000 and
 * rounded to a whole number, with thousands separators. The raw value is never
 * rendered anywhere — this is the only way the balance reaches the UI.
 */
internal fun formatScaledCredits(credits: Double): String =
    NumberFormat.getIntegerInstance().format(round(credits * 1000).toLong())

/** Parses a hex color safely (e.g. #0d6efd), falling back to grey on error. */
internal fun parseBadgeColor(hex: String): Color = try {
    Color(android.graphics.Color.parseColor(hex))
} catch (_: Exception) {
    Color(0xFF6C757D)
}

/** Formats the remaining hours until the referral claim window expires. */
internal fun formatRemainingClaimHours(
    createdAt: String?,
    now: Instant = Instant.now()
): String? {
    if (createdAt.isNullOrBlank()) return null
    return try {
        val instant = Instant.parse(createdAt)
        val elapsedHours = Duration.between(instant, now).toHours()
        val remainingHours = com.gotcha.auth.REFERRAL_CLAIM_WINDOW_HOURS - elapsedHours
        if (remainingHours > 0) {
            "You have ${remainingHours}h left to claim an invite code."
        } else {
            null
        }
    } catch (_: Exception) {
        null
    }
}

/** Pill badge displaying user tier (e.g. Free, Pro, Premium, Influencer). */
@Composable
fun TierPill(tier: SamosaTier, modifier: Modifier = Modifier) {
    val bgColor = parseBadgeColor(tier.badgeColor)
    Surface(
        color = bgColor,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier
    ) {
        Text(
            text = tier.displayName.ifBlank { "Free" },
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }
}

/** Chip displaying a user tag (e.g. beta_tester, early_adopter). */
@Composable
fun TagChip(tag: String, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
        modifier = modifier
    ) {
        Text(
            text = tag,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

/** "Refer & Earn" card showing the user's invite code, stats, share action, and late claim fallback. */
@Composable
fun ReferAndEarnCard(
    user: SamosaUser,
    onClaimReferral: ((String) -> Unit)? = null,
    referralBusy: Boolean = false,
    referralError: String? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val referral = user.referral
    val code = referral.code ?: ""
    var claimInput by remember { mutableStateOf("") }

    OutlinedCard(
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "🎁 Invite Friends & Earn",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            if (code.isNotBlank()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Your invite code:",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = code,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    OutlinedButton(
                        onClick = { ReferralClipboardHelper.copyReferralCode(context, code) }
                    ) {
                        Text("Copy Code")
                    }
                }

                Button(
                    onClick = {
                        ReferralClipboardHelper.shareReferralLink(
                            context = context,
                            code = code,
                            shareUrl = referral.shareUrl
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Share Invite Link")
                }

                val earnedCredits = formatScaledCredits(referral.creditsEarned)
                Text(
                    text = "You've referred ${referral.totalReferred} friends • $earnedCredits credits earned",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (referral.canClaim && onClaimReferral != null) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                Text(
                    text = "Enter Invite Code",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )

                val remainingText = formatRemainingClaimHours(user.createdAt)
                if (remainingText != null) {
                    Text(
                        text = remainingText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = claimInput,
                        onValueChange = { claimInput = it.uppercase().trim() },
                        placeholder = { Text("AIR-XXXXXX") },
                        singleLine = true,
                        enabled = !referralBusy,
                        modifier = Modifier.weight(1f)
                    )

                    Button(
                        onClick = { onClaimReferral(claimInput) },
                        enabled = !referralBusy && claimInput.isNotBlank()
                    ) {
                        if (referralBusy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        } else {
                            Text("Apply")
                        }
                    }
                }

                if (!referralError.isNullOrBlank()) {
                    Text(
                        text = referralError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
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
    onSignOut: () -> Unit,
    /** Raw remaining credit; scaled ×1000 for display. Null hides the line. */
    creditsRemaining: Double? = null,
    /** Full user profile containing tier, tags, and referral metadata. */
    user: SamosaUser? = null,
    /** Optional callback to claim an invite code. */
    onClaimReferral: ((String) -> Unit)? = null,
    referralBusy: Boolean = false,
    referralError: String? = null,
    /** Applied to the sign-in button only, so the tour can spotlight it. */
    signInModifier: Modifier = Modifier
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = if (signedIn) "Signed in to Samosa AI" else "Not signed in",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            if (signedIn && user != null && user.tier.displayName.isNotBlank()) {
                TierPill(tier = user.tier)
            }
        }

        if (signedIn && user != null && user.tags.isNotEmpty()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                user.tags.forEach { tag ->
                    TagChip(tag = tag)
                }
            }
        }

        if (signedIn && email.isNotBlank()) {
            Text(
                text = email,
                style = MaterialTheme.typography.bodyMedium
            )
        }
        if (signedIn && creditsRemaining != null) {
            Text(
                text = "Credits remaining: ${formatScaledCredits(creditsRemaining)}",
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

            if (user != null) {
                ReferAndEarnCard(
                    user = user,
                    onClaimReferral = onClaimReferral,
                    referralBusy = referralBusy,
                    referralError = referralError
                )
            }
        } else {
            Button(
                onClick = onSignIn,
                enabled = !busy,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(signInModifier)
            ) { Text(if (busy) "Signing in…" else "Sign in with Google") }
        }
    }
}

/**
 * A label with a trailing switch — the settings screens' standard boolean row.
 *
 * [switchTestTag] tags the `Switch` rather than the row, so a test that clicks
 * it actually toggles something; a tag on the row would find a node that isn't
 * toggleable and no-op silently. [switchContentDescription] names the `Switch`
 * for the same reason — it is what a UiAutomator/Maestro flow, which cannot see
 * test tags, has to aim at.
 *
 * [enabled] disables the `Switch` (greyed out, no tap effect) for a row whose
 * action depends on a prerequisite the user has not met yet; the caller should
 * also explain the prerequisite nearby.
 */
@Composable
fun SettingsToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    isLarge: Boolean = false,
    enabled: Boolean = true,
    switchTestTag: String? = null,
    switchContentDescription: String? = null,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = if (isLarge) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
            color = if (enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            modifier = Modifier
                .then(if (switchTestTag != null) Modifier.testTag(switchTestTag) else Modifier)
                .then(
                    if (switchContentDescription != null) {
                        Modifier.semantics { contentDescription = switchContentDescription }
                    } else {
                        Modifier
                    }
                )
        )
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
