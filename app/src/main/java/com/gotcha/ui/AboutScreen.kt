package com.gotcha.ui

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.gotcha.tools.CompanyInfoTool

/**
 * The About Us page: who Samosa AI is, what else they make, how to reach them,
 * and the way through to the Legal documents.
 *
 * The body is the same bundled asset the `about_samosa_ai` tool reads
 * ([CompanyInfoTool.ABOUT_ASSET]), so what the agent says about the company and
 * what this page shows cannot drift apart. It is rendered with the same small
 * Markdown pass the Legal page uses.
 */
@Composable
fun AboutScreen(
    context: Context,
    onBack: () -> Unit,
    onOpenPage: (SettingsPage) -> Unit
) {
    val overlay = rememberSettingsOverlayState()
    val uriHandler = LocalUriHandler.current

    var aboutText by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        aboutText = readAsset(context, CompanyInfoTool.ABOUT_ASSET)
    }

    SettingsScaffold(title = SettingsPage.ABOUT.title, onBack = onBack, overlay = overlay) {
        Text(
            text = renderLegalMarkdown(aboutText ?: "(loading…)"),
            style = MaterialTheme.typography.bodyMedium
        )

        HorizontalDivider(thickness = 1.dp)

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "Open in your browser",
                style = MaterialTheme.typography.titleMedium
            )
            LINKS.forEach { (label, url) ->
                LinkRow(label = label, url = url, onOpen = uriHandler::openUri)
            }
        }

        HorizontalDivider(thickness = 1.dp)

        SettingsNavRow(
            page = SettingsPage.LEGAL,
            onClick = { onOpenPage(SettingsPage.LEGAL) },
            modifier = Modifier.testTag(SettingsPage.LEGAL.testTag)
        )
    }
}

@Composable
private fun LinkRow(label: String, url: String, onOpen: (String) -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.primary,
        textDecoration = TextDecoration.Underline,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen(url) }
            .padding(vertical = 8.dp)
    )
}

/**
 * Kept here rather than parsed out of the asset: the asset is prose the agent
 * reads, and pulling tappable rows out of it would mean the page silently loses
 * links whenever the copy is reworded.
 */
private val LINKS = listOf(
    "Samosa AI website" to "https://samosa-ai.com",
    "About us" to "https://samosa-ai.com/about-us",
    "Gotcha" to "https://samosa-ai.com/gotcha",
    "Gotcha documentation" to "https://samosa-ai.com/gotcha/docs",
    "Pricing" to "https://samosa-ai.com/pricing",
    "Blog" to "https://blog.samosa-ai.com",
    "GitHub" to "https://github.com/Rishabh-Bajpai",
    "Email samosa.ai.com@gmail.com" to "mailto:samosa.ai.com@gmail.com"
)
