package com.gotcha.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gotcha.R
import com.gotcha.ui.theme.SkinAlertDialog

/** Where "Star on GitHub" goes. */
const val GITHUB_REPO_URL = "https://github.com/samosa-ai-com/Gotcha"

private const val DAY_MS = 24L * 60L * 60L * 1000L

/** How long after install before the first ask: give Gotcha a chance first. */
internal const val STAR_PROMPT_FIRST_AFTER_MS = 3L * DAY_MS

/** The gap between two asks. */
internal const val STAR_PROMPT_COOLDOWN_MS = 5L * DAY_MS

/** "Not now" this many times and the ask stops for good. */
internal const val STAR_PROMPT_MAX_DISMISSALS = 4

/**
 * When the "Enjoying Gotcha?" ask is next due, or null once it never will be.
 * [answered] is set once the user tapped either answer (star or feedback),
 * which ends the ask for good; a plain dismissal only counts towards
 * [STAR_PROMPT_MAX_DISMISSALS]. [lastShownAt] is zero until the first ask.
 */
internal fun starPromptDueAt(
    installedAt: Long,
    lastShownAt: Long,
    dismissals: Int,
    answered: Boolean
): Long? = when {
    answered -> null
    dismissals >= STAR_PROMPT_MAX_DISMISSALS -> null
    else -> maxOf(installedAt + STAR_PROMPT_FIRST_AFTER_MS, lastShownAt + STAR_PROMPT_COOLDOWN_MS)
}

/**
 * Asks whether the user enjoys Gotcha: yes leads to a GitHub star, no to the
 * feedback form. [onFeedback] is null when no feedback form is configured, and
 * the button is left out.
 */
@Composable
fun StarPromptDialog(
    onStar: () -> Unit,
    onFeedback: (() -> Unit)?,
    onDismiss: () -> Unit
) {
    SkinAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.star_prompt_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Text(
                text = stringResource(
                    if (onFeedback != null) R.string.star_prompt_body else R.string.star_prompt_body_no_feedback
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        // Stacked rather than in a row: three labels don't fit side by side in
        // the longer translations.
        confirmButton = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(onClick = onStar, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.star_prompt_star))
                }
                if (onFeedback != null) {
                    OutlinedButton(onClick = onFeedback, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.star_prompt_feedback))
                    }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.star_prompt_not_now))
                }
            }
        }
    )
}
