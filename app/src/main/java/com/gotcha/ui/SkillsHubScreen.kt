package com.gotcha.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.gotcha.R

/**
 * Skills and connectors: the accounts the assistant can reach (Google,
 * Microsoft, Notion, Home Assistant, email, MCP servers) beside the skills that
 * tell it how to use them and the apps around them.
 *
 * Connectors used to be reachable only from the navigation drawer, so searching
 * Settings for "Notion" or "Google" found nothing. The row here opens the same
 * Connectors screen as the drawer; Back from it returns to this page.
 */
@Composable
fun SkillsHubScreen(
    onBack: () -> Unit,
    onOpenPage: (SettingsPage) -> Unit,
    onOpenConnectors: () -> Unit
) {
    val overlay = rememberSettingsOverlayState()

    SettingsScaffold(
        title = stringResource(SettingsPage.SKILLS_AND_CONNECTORS.title),
        onBack = onBack,
        overlay = overlay
    ) {
        Text(
            stringResource(R.string.skills_hub_intro),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        SettingsGroupCard(title = null) {
            SettingsLinkRow(
                icon = Icons.Outlined.Cable,
                title = stringResource(R.string.drawer_connectors),
                summary = stringResource(R.string.skills_hub_connectors_summary),
                onClick = onOpenConnectors,
                horizontalPadding = 16.dp,
                modifier = Modifier.testTag("settings_connectors_row")
            )
            SettingsGroupDivider()
            SettingsNavRow(
                page = SettingsPage.SKILLS,
                onClick = { onOpenPage(SettingsPage.SKILLS) },
                horizontalPadding = 16.dp,
                modifier = Modifier.testTag(SettingsPage.SKILLS.testTag)
            )
        }
    }
}
