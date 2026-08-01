package com.gotcha.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DrawerDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gotcha.data.ChatSession
import com.gotcha.ui.theme.GotchaMono
import com.gotcha.ui.theme.LocalSkin
import com.gotcha.ui.theme.SkinAlertDialog
import com.gotcha.ui.tour.TourAnchor
import com.gotcha.ui.tour.tourAnchor

@Composable
fun AppDrawerContent(
    sessions: List<ChatSession>,
    activeSessionId: String?,
    onNewChat: () -> Unit,
    onSessionClick: (String) -> Unit,
    onDeleteSession: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenConnectors: () -> Unit,
    maxContextTokens: Int = 0,
    activeTokenCount: Int = 0,
    /**
     * Live per-session token counts published from [com.gotcha.agent.ChatViewModel.onTokenCount].
     * When present for a given session, this overlay wins over both [activeTokenCount]
     * and the persisted [ChatSession.tokenCount] so the running session's row
     * updates within the same frame as the round, without waiting for the
     * end-of-round disk save.
     */
    liveTokenBySession: Map<String, Int> = emptyMap()
) {
    var sessionToDelete by remember { mutableStateOf<String?>(null) }

    // The drawer covers the app rather than floating in it, so it gets a ground
    // of its own. Stated explicitly rather than left to DrawerDefaults, because
    // which surface role the default reads has changed between Material 3
    // versions and a see-through sidebar is unreadable.
    val skin = LocalSkin.current
    val drawerColor = if (skin.isGlass) skin.ground else DrawerDefaults.containerColor

    // Every row was painting a pale container, selected or not, so the list read
    // as a stack of white lozenges with no gaps — their pill corners interlocking
    // where they met. Only the open chat gets a background now, and it is a tint
    // of the skin's own accent rather than a wash of white.
    val itemColors = NavigationDrawerItemDefaults.colors(
        selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
        unselectedContainerColor = Color.Transparent,
        selectedTextColor = MaterialTheme.colorScheme.onSurface,
        unselectedTextColor = MaterialTheme.colorScheme.onSurface,
        selectedIconColor = MaterialTheme.colorScheme.primary,
        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant
    )
    val itemModifier = Modifier
        .padding(NavigationDrawerItemDefaults.ItemPadding)
        .padding(vertical = 2.dp)

    ModalDrawerSheet(
        drawerContainerColor = drawerColor,
        modifier = Modifier.width(300.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Text(
                "Gotcha",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 28.dp, vertical = 20.dp)
            )
            NavigationDrawerItem(
                label = { Text("New Chat") },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                selected = false,
                onClick = onNewChat,
                colors = itemColors,
                modifier = itemModifier
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Text(
                "Recent chats",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 28.dp, vertical = 4.dp)
            )
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(sessions, key = { it.id }) { session ->
                    val tokens = liveTokenBySession[session.id]
                        ?: (if (session.id == activeSessionId) activeTokenCount else session.tokenCount)
                    val usage = formatContextUsage(tokens, maxContextTokens)
                    NavigationDrawerItem(
                        label = {
                            Column {
                                Text(
                                    session.title,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (usage != null) {
                                    // Tabular figures: this counter ticks while a
                                    // reply streams, and proportional digits make
                                    // the whole line twitch as it does.
                                    Text(
                                        usage,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = GotchaMono,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        },
                        selected = session.id == activeSessionId,
                        onClick = { onSessionClick(session.id) },
                        badge = {
                            IconButton(onClick = { sessionToDelete = session.id }) {
                                Icon(
                                    Icons.Outlined.Delete,
                                    contentDescription = "Delete chat",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        colors = itemColors,
                        modifier = itemModifier
                    )
                }
            }
            HorizontalDivider()
            NavigationDrawerItem(
                label = { Text("Connectors") },
                icon = { Icon(Icons.Default.Link, contentDescription = null) },
                selected = false,
                onClick = onOpenConnectors,
                colors = itemColors,
                modifier = itemModifier
            )
            NavigationDrawerItem(
                label = { Text("Settings") },
                icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                selected = false,
                onClick = onOpenSettings,
                colors = itemColors,
                modifier = itemModifier.tourAnchor(TourAnchor.DRAWER_SETTINGS)
            )
            Spacer(modifier = Modifier.height(12.dp))
        }
    }

    sessionToDelete?.let { id ->
        val title = sessions.find { it.id == id }?.title ?: "this chat"
        SkinAlertDialog(
            onDismissRequest = { sessionToDelete = null },
            title = { Text("Delete Chat?") },
            text = { Text("Are you sure you want to permanently delete \"$title\"? This action cannot be undone.") },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteSession(id)
                        sessionToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { sessionToDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

/**
 * Formats a per-chat context readout like "12,345 / 70,000 tokens (18%)".
 * Returns null when there's nothing meaningful to show yet.
 */
private fun formatContextUsage(tokens: Int, maxContextTokens: Int): String? {
    if (tokens <= 0) return null
    fun grouped(n: Int): String = "%,d".format(n)
    return if (maxContextTokens > 0) {
        val percent = (tokens.toFloat() / maxContextTokens * 100f)
            .coerceIn(0f, 100f).toInt()
        "${grouped(tokens)} / ${grouped(maxContextTokens)} · $percent%"
    } else {
        "${grouped(tokens)} tokens"
    }
}
