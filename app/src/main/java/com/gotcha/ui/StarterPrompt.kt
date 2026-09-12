package com.gotcha.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * One tap-to-fill suggestion on the empty home screen.
 *
 * [label] is what the chip says; [template] is what lands in the composer. They
 * are deliberately different — the chip has to stay short enough to sit three to
 * a screen, while the template is a first draft of a real prompt. Tapping fills
 * the composer and stops there: the user edits and sends, so a template may
 * carry an unfilled `…` where a name or a message belongs.
 */
internal data class StarterPrompt(val label: String, val template: String)

/**
 * The starters offered on an empty chat. Between them they demo the three things
 * people most often don't realise Gotcha can do — drive the device, answer
 * questions about what's on screen or on disk, and handle messages.
 *
 * Four of the seven (screen, both filesystem ones, notifications) are read-only,
 * so they answer in Monitor as well as Operator. The two device actions need
 * Operator; the chip does not switch mode on the user's behalf, the selector
 * directly above it does.
 */
internal val STARTER_PROMPTS = listOf(
    StarterPrompt(
        label = "Turn on Wi-Fi",
        template = "Open settings and turn on Wi-Fi"
    ),
    StarterPrompt(
        label = "Set an alarm",
        template = "Set an alarm for 7am tomorrow"
    ),
    StarterPrompt(
        label = "Read my screen",
        template = "What's on my screen?"
    ),
    StarterPrompt(
        label = "Find big files",
        template = "Find the largest files in my Downloads folder and tell me what's safe to delete"
    ),
    StarterPrompt(
        label = "Summarise a PDF",
        template = "Read the most recent PDF in my Downloads and summarise it"
    ),
    StarterPrompt(
        label = "Send a WhatsApp",
        template = "Send a WhatsApp to … saying …"
    ),
    StarterPrompt(
        label = "Catch me up",
        template = "Catch me up on my unread notifications"
    )
)

/** How many of [STARTER_PROMPTS] a single home screen offers. */
internal const val STARTER_PROMPT_COUNT = 3

/**
 * rememberSaveable saver for the drawn starters. Only the labels are stored —
 * they are unique, and the prompts themselves are a compile-time list, so a
 * restore is a lookup. A label that no longer exists (the list changed across an
 * app update, with saved state from the old one) simply drops out.
 */
internal val StarterPromptLabelsSaver = listSaver<List<StarterPrompt>, String>(
    save = { prompts -> prompts.map { it.label } },
    restore = { labels ->
        labels.mapNotNull { label -> STARTER_PROMPTS.firstOrNull { it.label == label } }
    }
)

/**
 * The tap-to-fill chip row under the agent selector on the home screen.
 *
 * Styled like the *unchosen* half of [AgentModeSelector] — hairline outline, no
 * fill, dimmed ink — for the reason spelled out there: Material's own chips mark
 * themselves with `secondaryContainer`, which every glass skin leaves
 * translucent. These are suggestions, not the screen's main event, so they stay
 * quieter than the mode selector above them and the composer below.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun StarterPromptRow(
    prompts: List<StarterPrompt>,
    onPick: (StarterPrompt) -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier.testTag("starter_prompts"),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "Try",
            style = MaterialTheme.typography.labelMedium,
            color = scheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            prompts.forEach { prompt ->
                Surface(
                    onClick = { onPick(prompt) },
                    shape = CircleShape,
                    color = Color.Transparent,
                    contentColor = scheme.onSurfaceVariant.copy(alpha = 0.75f),
                    border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.6f)),
                    modifier = Modifier.testTag("starter_prompt_${prompt.label}")
                ) {
                    Text(
                        prompt.label,
                        style = MaterialTheme.typography.labelLarge,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                    )
                }
            }
        }
    }
}
