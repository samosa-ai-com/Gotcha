package com.gotcha.ui

import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.gotcha.R

/**
 * Shell: the hub for the two ways the assistant can run Linux commands — Gotcha
 * Shell, the companion app with a full Linux system, and Termux. They used to be
 * two separate rows on the settings home list, which hid that they are
 * alternatives for the same job.
 */
@Composable
fun ShellHubScreen(
    onBack: () -> Unit,
    onOpenPage: (SettingsPage) -> Unit
) {
    val overlay = rememberSettingsOverlayState()

    SettingsScaffold(title = stringResource(SettingsPage.SHELL.title), onBack = onBack, overlay = overlay) {
        Text(
            stringResource(R.string.shell_hub_intro),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        listOf(SettingsPage.LINUX_SHELL, SettingsPage.TERMUX).forEach { page ->
            HorizontalDivider(thickness = 1.dp)
            SettingsNavRow(
                page = page,
                onClick = { onOpenPage(page) },
                modifier = Modifier.testTag(page.testTag)
            )
        }
        HorizontalDivider(thickness = 1.dp)
    }
}
