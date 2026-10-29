package com.gotcha.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gotcha.R

/**
 * Settings ▸ Chats: import and whole-library backup, moved here from the
 * navigation drawer so the drawer holds destinations (chats, scheduled
 * tasks, connectors, settings) rather than one-off actions.
 */
@Composable
fun ChatsScreen(
    onBack: () -> Unit,
    onImportChats: () -> Unit,
    onBackupAllChats: () -> Unit
) {
    val overlay = rememberSettingsOverlayState()

    SettingsScaffold(
        title = stringResource(SettingsPage.CHATS.title),
        onBack = onBack,
        overlay = overlay
    ) {
        ChatsActionRow(
            label = stringResource(R.string.drawer_import_chats),
            testTag = "settings_chats_import",
            onClick = onImportChats
        )
        ChatsActionRow(
            label = stringResource(R.string.drawer_back_up_all_chats),
            testTag = "settings_chats_backup_all",
            onClick = onBackupAllChats
        )
    }
}

@Composable
private fun ChatsActionRow(label: String, testTag: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp)
            .settingsField(testTag),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium
            )
        }
        Text(text = "›", style = MaterialTheme.typography.titleLarge)
    }
}
