package com.gotcha.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.HideImage
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.gotcha.R

/**
 * Settings ▸ Chats and storage: import and whole-library backup (moved here from
 * the navigation drawer, so the drawer holds destinations rather than one-off
 * actions), and the two clean-ups that used to hide under AI model ▸ Advanced
 * next to the agent's loop limits — clearing the LLM response cache and the
 * debug screenshots.
 */
@Composable
fun ChatsScreen(
    onBack: () -> Unit,
    onImportChats: () -> Unit,
    onBackupAllChats: () -> Unit,
    onClearLlmCache: () -> Unit = {},
    onClearDebugScreenshots: () -> Unit = {}
) {
    val overlay = rememberSettingsOverlayState()
    val context = LocalContext.current

    SettingsScaffold(
        title = stringResource(SettingsPage.CHATS.title),
        onBack = onBack,
        overlay = overlay
    ) {
        SettingsGroupCard(title = stringResource(R.string.chats_group_chats)) {
            SettingsLinkRow(
                icon = Icons.Outlined.FileOpen,
                title = stringResource(R.string.drawer_import_chats),
                summary = stringResource(R.string.chats_import_summary),
                onClick = onImportChats,
                horizontalPadding = 16.dp,
                modifier = Modifier.settingsField("settings_chats_import")
            )
            SettingsGroupDivider()
            SettingsLinkRow(
                icon = Icons.Outlined.Backup,
                title = stringResource(R.string.drawer_back_up_all_chats),
                summary = stringResource(R.string.chats_backup_summary),
                onClick = onBackupAllChats,
                horizontalPadding = 16.dp,
                modifier = Modifier.settingsField("settings_chats_backup_all")
            )
        }
        SettingsGroupCard(title = stringResource(R.string.chats_group_storage)) {
            SettingsLinkRow(
                icon = Icons.Outlined.CleaningServices,
                title = stringResource(R.string.ai_config_clear_llm_cache),
                summary = stringResource(R.string.chats_clear_llm_cache_summary),
                onClick = {
                    onClearLlmCache()
                    overlay.show(context.getString(R.string.ai_config_llm_response_cache_cleared))
                },
                horizontalPadding = 16.dp,
                modifier = Modifier.settingsField("settings_clear_llm_cache")
            )
            SettingsGroupDivider()
            SettingsLinkRow(
                icon = Icons.Outlined.HideImage,
                title = stringResource(R.string.ai_config_clear_debug_screenshots),
                summary = stringResource(R.string.chats_clear_debug_screenshots_summary),
                onClick = {
                    onClearDebugScreenshots()
                    overlay.show(context.getString(R.string.ai_config_debug_screenshots_cleared))
                },
                horizontalPadding = 16.dp,
                modifier = Modifier.settingsField("settings_clear_debug_screenshots")
            )
        }
    }
}
