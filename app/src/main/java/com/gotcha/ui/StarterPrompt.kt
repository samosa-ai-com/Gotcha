package com.gotcha.ui

import androidx.annotation.StringRes
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gotcha.R

/**
 * One tap-to-fill suggestion on the empty home screen.
 *
 * [label] is what the chip says; [template] is what lands in the composer. They
 * are deliberately different — the chip has to stay short enough to sit three to
 * a screen, while the template is a first draft of a real prompt. Tapping fills
 * the composer and stops there: the user edits and sends, so a template may
 * carry an unfilled `…` where a name or a message belongs.
 *
 * Public only because [Persona] carries its own list of them.
 */
data class StarterPrompt(@StringRes val label: Int, @StringRes val template: Int)

/**
 * The default starters, offered on an empty chat with no persona picked (or a
 * persona with no starters of its own — see [startersFor]). Between them they demo the three things
 * people most often don't realise Gotcha can do — drive the device, answer
 * questions about what's on screen or on disk, and handle messages.
 *
 * Four of the seven (screen, both filesystem ones, notifications) are read-only,
 * so they answer in Monitor as well as Operator. The two device actions need
 * Operator; the chip does not switch mode on the user's behalf, the selector
 * directly above it does.
 *
 * A persona's own starters replace this list outright, and are held to a
 * stricter rule: every one must be answerable in Monitor, the mode every
 * persona starts in, so none of them leads with a device action.
 */
internal val STARTER_PROMPTS = listOf(
    StarterPrompt(
        label = R.string.starter_turn_on_wi_fi,
        template = R.string.starter_turn_on_wi_fi_template
    ),
    StarterPrompt(
        label = R.string.starter_set_an_alarm,
        template = R.string.starter_set_an_alarm_template
    ),
    StarterPrompt(
        label = R.string.starter_read_my_screen,
        template = R.string.starter_read_my_screen_template
    ),
    StarterPrompt(
        label = R.string.starter_find_big_files,
        template = R.string.starter_find_big_files_template
    ),
    StarterPrompt(
        label = R.string.starter_summarise_a_pdf,
        template = R.string.starter_summarise_a_pdf_template
    ),
    StarterPrompt(
        label = R.string.starter_send_a_whatsapp,
        template = R.string.starter_send_a_whatsapp_template
    ),
    StarterPrompt(
        label = R.string.starter_catch_me_up,
        template = R.string.starter_catch_me_up_template
    )
)

/** How many starters a single home screen offers. */
internal const val STARTER_PROMPT_COUNT = 3

/**
 * The pool the home screen draws its starters from: [persona]'s own when it
 * has enough to fill a row, otherwise [STARTER_PROMPTS]. A persona's starters
 * replace the default list rather than mixing with it — a Doctor chat offering
 * "Turn on Wi-Fi" is exactly the "the persona changed nothing" feeling they
 * exist to avoid.
 */
internal fun startersFor(persona: Persona?): List<StarterPrompt> =
    persona?.starters?.takeIf { it.size >= STARTER_PROMPT_COUNT } ?: STARTER_PROMPTS

/**
 * rememberSaveable saver for the starters drawn from [pool]. Only the labels
 * are stored — they are unique within a pool, and the prompts themselves are a
 * compile-time list, so a restore is a lookup. A label that no longer exists
 * (the list changed across an app update, with saved state from the old one)
 * drops out, and the row is refilled from [pool] so a stale save never shows
 * fewer than [STARTER_PROMPT_COUNT] chips.
 */
internal fun starterPromptLabelsSaver(pool: List<StarterPrompt>) = listSaver<List<StarterPrompt>, Int>(
    save = { prompts -> prompts.map { it.label } },
    restore = { labels ->
        val restored = labels.mapNotNull { label -> pool.firstOrNull { it.label == label } }
        val fill = pool.filter { it !in restored }.shuffled()
        (restored + fill).take(STARTER_PROMPT_COUNT)
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
            stringResource(R.string.starter_try),
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
                        stringResource(prompt.label),
                        style = MaterialTheme.typography.labelLarge,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                    )
                }
            }
        }
    }
}
