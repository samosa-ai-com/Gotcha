package com.gotcha.ui

import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.gotcha.ui.tour.TourAnchor
import com.gotcha.ui.tour.tourAnchor

/**
 * AI: the hub for everything the assistant thinks, hears and speaks with — the
 * language model on one page, speech and transcription on the other.
 *
 * They used to be two unrelated rows on the settings home list, which read as if
 * choosing a voice had nothing to do with choosing a brain; both are the same
 * decision about which provider does the work, and both are usually configured
 * in one sitting.
 */
@Composable
fun AiHubScreen(
    onBack: () -> Unit,
    onOpenPage: (SettingsPage) -> Unit
) {
    val overlay = rememberSettingsOverlayState()

    SettingsScaffold(title = SettingsPage.AI.title, onBack = onBack, overlay = overlay) {
        Text(
            "The model does the thinking; speech gives it a voice and ears. " +
                "One provider can cover both. Which language it speaks and answers " +
                "in lives under Settings → Language.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        listOf(SettingsPage.AI_CONFIG, SettingsPage.SPEECH).forEach { page ->
            HorizontalDivider(thickness = 1.dp)
            SettingsNavRow(
                page = page,
                onClick = { onOpenPage(page) },
                modifier = Modifier
                    .testTag(page.testTag)
                    .then(
                        // The tour walks the user to the model page from here.
                        if (page == SettingsPage.AI_CONFIG) {
                            Modifier.tourAnchor(TourAnchor.SETTINGS_AI_CONFIG)
                        } else {
                            Modifier
                        }
                    )
            )
        }
        HorizontalDivider(thickness = 1.dp)
    }
}
