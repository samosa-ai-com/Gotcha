package com.gotcha.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.CardGiftcard
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.WorkspacePremium
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gotcha.R
import com.gotcha.ui.theme.SkinAlertDialog
import com.gotcha.upsell.UpsellAudience
import com.gotcha.upsell.UpsellReason

/**
 * Opens the Samosa AI / Pro pitch for a reason. Provided by the activity, which knows the
 * audience and where the pitch's button leads; a screen with nothing provided does nothing.
 */
val LocalUpsell = staticCompositionLocalOf<(UpsellReason) -> Unit> { {} }

private data class UpsellFeature(val icon: ImageVector, @StringRes val text: Int)

/** Pro, as every nudge describes it. Deliberately general: no model names or numbers to go stale. */
private val PRO_FEATURES = listOf(
    UpsellFeature(Icons.Outlined.Terminal, R.string.upsell_pro_feature_shell),
    UpsellFeature(Icons.Outlined.AutoAwesome, R.string.upsell_pro_feature_models),
    UpsellFeature(Icons.Outlined.Speed, R.string.upsell_pro_feature_limits),
    UpsellFeature(Icons.Outlined.NewReleases, R.string.upsell_pro_feature_early)
)

/** Samosa AI, as told to someone who uses their own API key. */
private val SAMOSA_FEATURES = listOf(
    UpsellFeature(Icons.Outlined.Key, R.string.upsell_samosa_feature_no_key),
    UpsellFeature(Icons.Outlined.CardGiftcard, R.string.upsell_samosa_feature_credits),
    UpsellFeature(Icons.Outlined.Tune, R.string.upsell_samosa_feature_tuned),
    UpsellFeature(Icons.Outlined.WorkspacePremium, R.string.upsell_samosa_feature_pro)
)

@StringRes
internal fun upsellTitle(audience: UpsellAudience): Int =
    if (audience == UpsellAudience.PRO) R.string.upsell_pro_title else R.string.upsell_samosa_title

/** The opening line: why this is being said now. */
@StringRes
internal fun upsellLead(audience: UpsellAudience, reason: UpsellReason): Int =
    if (audience == UpsellAudience.PRO) {
        when (reason) {
            UpsellReason.SHELL -> R.string.upsell_pro_lead_shell
            UpsellReason.LIMITS -> R.string.upsell_pro_lead_limits
            UpsellReason.VALUE -> R.string.upsell_pro_lead_value
            UpsellReason.GENERAL -> R.string.upsell_pro_lead_general
        }
    } else {
        when (reason) {
            UpsellReason.SHELL -> R.string.upsell_samosa_lead_shell
            UpsellReason.LIMITS -> R.string.upsell_samosa_lead_limits
            UpsellReason.VALUE, UpsellReason.GENERAL -> R.string.upsell_samosa_lead_general
        }
    }

@StringRes
private fun upsellAction(audience: UpsellAudience): Int =
    if (audience == UpsellAudience.PRO) R.string.upsell_pro_action else R.string.upsell_samosa_action

/** What an inline card's button says: it opens the pitch, not the website. */
@StringRes
internal fun upsellOpenLabel(audience: UpsellAudience): Int =
    if (audience == UpsellAudience.PRO) R.string.upsell_pro_see else R.string.upsell_samosa_see

/**
 * The pitch every nudge leads to: why now, what Samosa AI or Pro brings, and one button. For Pro
 * the button opens the account website, where plans are bought; for Samosa AI it opens AI
 * settings, where the user signs in.
 */
@Composable
fun UpsellDialog(
    audience: UpsellAudience,
    reason: UpsellReason,
    onAction: () -> Unit,
    onDismiss: () -> Unit
) {
    val features = if (audience == UpsellAudience.PRO) PRO_FEATURES else SAMOSA_FEATURES
    SkinAlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("upsell_dialog"),
        title = {
            Text(
                text = stringResource(upsellTitle(audience)),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(stringResource(upsellLead(audience, reason)), style = MaterialTheme.typography.bodyLarge)
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    features.forEach { FeatureLine(it) }
                }
                Text(
                    stringResource(
                        if (audience == UpsellAudience.PRO) R.string.upsell_pro_footnote else R.string.upsell_samosa_footnote
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(onClick = onAction, modifier = Modifier.testTag("upsell_action")) {
                Text(stringResource(upsellAction(audience)))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.upsell_not_now)) }
        }
    )
}

@Composable
private fun FeatureLine(feature: UpsellFeature) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(
            feature.icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp)
        )
        Text(stringResource(feature.text), style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * A nudge inside a screen, where something the user wanted needs Samosa AI or Pro: the reason in
 * a line and a button that opens [UpsellDialog]. [onDismiss] adds a close button.
 */
@Composable
fun UpsellCard(
    audience: UpsellAudience,
    reason: UpsellReason,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    onDismiss: (() -> Unit)? = null,
    /** The card's own words and button, where the default lead and "See …" do not fit. */
    @StringRes text: Int = upsellLead(audience, reason),
    @StringRes actionLabel: Int = upsellOpenLabel(audience)
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = modifier.fillMaxWidth().testTag("upsell_card")
    ) {
        Column(modifier = Modifier.padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 6.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    if (audience == UpsellAudience.PRO) Icons.Outlined.WorkspacePremium else Icons.Outlined.Bolt,
                    contentDescription = null,
                    modifier = Modifier.padding(top = 2.dp).size(20.dp)
                )
                Text(
                    stringResource(text),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f).padding(start = 10.dp, end = 4.dp)
                )
                if (onDismiss != null) {
                    IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                        Icon(
                            Icons.Outlined.Close,
                            contentDescription = stringResource(R.string.upsell_not_now),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
            TextButton(onClick = onOpen, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(actionLabel), fontWeight = FontWeight.Medium)
            }
        }
    }
}

/** Shown on the return from the account website once the plan reads as Pro. */
@Composable
fun ProWelcomeDialog(onSetUpShell: () -> Unit, onDismiss: () -> Unit) {
    SkinAlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("pro_welcome_dialog"),
        title = {
            Text(
                text = stringResource(R.string.upsell_welcome_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        },
        text = { Text(stringResource(R.string.upsell_welcome_text), style = MaterialTheme.typography.bodyLarge) },
        confirmButton = {
            Button(onClick = onSetUpShell) { Text(stringResource(R.string.upsell_welcome_shell)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.upsell_welcome_later)) }
        }
    )
}
