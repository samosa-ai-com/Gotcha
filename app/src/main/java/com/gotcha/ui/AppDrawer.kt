package com.gotcha.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DividerDefaults
import androidx.compose.material3.DrawerDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gotcha.R
import com.gotcha.agent.ChatTitle
import com.gotcha.data.ChatSearch
import com.gotcha.data.ChatSearchHit
import com.gotcha.data.ChatSession
import com.gotcha.i18n.stringLookup
import com.gotcha.ui.theme.GotchaMono
import com.gotcha.ui.theme.LocalSkin
import com.gotcha.ui.theme.SkinAlertDialog
import com.gotcha.ui.tour.TourAnchor
import com.gotcha.ui.tour.tourAnchor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppDrawerContent(
    sessions: List<ChatSession>,
    activeSessionId: String?,
    onNewChat: () -> Unit,
    onSessionClick: (String) -> Unit,
    onDeleteSession: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenConnectors: () -> Unit,
    onOpenScheduledTasks: () -> Unit = {},
    onOpenSecondBrain: () -> Unit = {},
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
    // The drawer's search: empty shows every chat, as before.
    var query by remember { mutableStateOf("") }

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
    val itemModifier = Modifier
        .padding(NavigationDrawerItemDefaults.ItemPadding)
        .padding(vertical = 4.dp)

    // A floating panel, docked to the left edge: no start margin and square
    // start corners give the chat names the full width, while the 16dp
    // margins and 24dp corners everywhere else keep it lifted off the app.
    ModalDrawerSheet(
        drawerContainerColor = drawerColor,
        drawerShape = RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp),
        modifier = Modifier
            .width(300.dp)
            .padding(top = 16.dp, end = 16.dp, bottom = 16.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Text(
                stringResource(R.string.drawer_gotcha),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 28.dp, vertical = 20.dp)
            )
            // Search and new chat share one row: the field takes the width, the
            // button costs a tap target. Sending "New Chat" as a full row left
            // the list a line shorter for no gain.
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ChatSearchField(
                    query = query,
                    onQueryChange = { query = it },
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = {
                        // A search belongs to the list it narrowed: leaving it
                        // behind would greet the next open with a filtered list.
                        query = ""
                        onNewChat()
                    },
                    modifier = Modifier.testTag("drawer_new_chat")
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = stringResource(R.string.drawer_new_chat)
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            // Fine rules bracketing the chat list, top and bottom: the groups
            // are spaced apart, and these hairlines say where the list itself
            // starts and ends.
            HorizontalDivider(
                thickness = DividerDefaults.Thickness,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                modifier = Modifier.padding(vertical = 4.dp)
            )
            val searching = query.isNotBlank()
            // Searching stays off the main thread: every chat's transcript is
            // scanned, and a long history must not make typing stutter. The
            // short delay coalesces keystrokes; "empty" is applied at once,
            // because clearing the field should restore the list immediately.
            var hits by remember { mutableStateOf<List<ChatSearchHit>>(emptyList()) }
            var searchPending by remember { mutableStateOf(false) }
            LaunchedEffect(query, sessions) {
                if (query.isBlank()) {
                    hits = emptyList()
                    searchPending = false
                } else {
                    searchPending = true
                    delay(SEARCH_DEBOUNCE_MS)
                    hits = withContext(Dispatchers.Default) { ChatSearch.search(sessions, query) }
                    searchPending = false
                }
            }
            // One list either way: matching chats with their snippet, or all of
            // them. Kept in the drawer's own order.
            val rows = when {
                !searching -> sessions.map { it to null }
                else -> {
                    val snippets = hits.associate { it.sessionId to it.snippet }
                    sessions.filter { it.id in snippets }.map { it to snippets[it.id] }
                }
            }
            Text(
                if (searching) {
                    stringResource(R.string.drawer_search_results, rows.size)
                } else {
                    stringResource(R.string.drawer_recent_chats)
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 28.dp, vertical = 4.dp)
            )
            LazyColumn(modifier = Modifier.weight(1f)) {
                // Only once the search has run: until then the previous results
                // are still on screen, and "no chats match" would be a lie.
                if (searching && rows.isEmpty() && !searchPending) {
                    item {
                        Text(
                            stringResource(R.string.drawer_search_no_results, query.trim()),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .padding(horizontal = 28.dp, vertical = 12.dp)
                                .testTag("drawer_search_no_results")
                        )
                    }
                }
                items(rows, key = { it.first.id }) { (session, snippet) ->
                    val tokens = liveTokenBySession[session.id]
                        ?: (if (session.id == activeSessionId) activeTokenCount else session.tokenCount)
                    ChatRow(
                        session = session,
                        snippet = snippet,
                        tokens = tokens,
                        maxContextTokens = maxContextTokens,
                        selected = session.id == activeSessionId,
                        onOpen = {
                            query = ""
                            onSessionClick(session.id)
                        },
                        onDelete = { sessionToDelete = session.id },
                        modifier = itemModifier
                    )
                }
            }
            // A hairline closes the chat list before the feature doors.
            Spacer(modifier = Modifier.height(4.dp))
            HorizontalDivider(
                thickness = DividerDefaults.Thickness,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                modifier = Modifier.padding(vertical = 4.dp)
            )
            Spacer(modifier = Modifier.height(4.dp))
            // Compact doors, not list items: NavigationDrawerItem fixes its
            // rows at 56dp, which left each one-line door swimming in empty
            // space. These are 44dp and keep the drawer's pill language.
            FeatureRow(
                label = stringResource(R.string.drawer_second_brain),
                // Sits with Scheduled tasks: both are the parts of Gotcha that
                // keep working when no chat is open. This is the only door.
                icon = { Icon(Icons.Default.Psychology, contentDescription = null) },
                onClick = onOpenSecondBrain
            )
            FeatureRow(
                // Reuses the settings page title: already translated everywhere.
                label = stringResource(R.string.settings_page_scheduled_tasks),
                icon = { Icon(Icons.Default.Schedule, contentDescription = null) },
                onClick = onOpenScheduledTasks
            )
            FeatureRow(
                label = stringResource(R.string.drawer_connectors),
                icon = { Icon(Icons.Default.Link, contentDescription = null) },
                onClick = onOpenConnectors
            )
            FeatureRow(
                label = stringResource(R.string.drawer_settings),
                icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                onClick = onOpenSettings,
                modifier = Modifier.tourAnchor(TourAnchor.DRAWER_SETTINGS)
            )
            Spacer(modifier = Modifier.height(12.dp))
        }
    }

    sessionToDelete?.let { id ->
        val title = sessions.find { it.id == id }?.title?.let { ChatTitle.display(it, LocalContext.current.stringLookup()) }
            ?: stringResource(R.string.drawer_this_chat)
        SkinAlertDialog(
            onDismissRequest = { sessionToDelete = null },
            title = { Text(stringResource(R.string.drawer_delete_chat)) },
            text = { Text(stringResource(R.string.drawer_delete_confirm, title)) },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteSession(id)
                        sessionToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(stringResource(R.string.drawer_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { sessionToDelete = null }) {
                    Text(stringResource(R.string.drawer_cancel))
                }
            }
        )
    }
}

/** How long typing must settle before the drawer searches, in milliseconds. */
private const val SEARCH_DEBOUNCE_MS = 120L

/**
 * One feature door: icon plus label on a single combined tap handler. A plain
 * Surface rather than NavigationDrawerItem, whose rows are fixed at 56dp no
 * matter how little they hold.
 */
@Composable
private fun FeatureRow(
    label: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(28.dp),
        color = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = modifier
            .fillMaxWidth()
            .padding(NavigationDrawerItemDefaults.ItemPadding)
            .defaultMinSize(minHeight = 44.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            icon()
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * One chat row: the drawer's selected-pill look on a single combined tap and
 * long-press handler. A plain Surface rather than NavigationDrawerItem, which
 * already owns a click handler — nesting a second detector outside it
 * starves the outer one, which is how taps silently died. No trailing delete
 * button: the badge cost every row 48dp of name width, and a long-press asks
 * the same confirmation as before.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatRow(
    session: ChatSession,
    snippet: String?,
    tokens: Int,
    maxContextTokens: Int,
    selected: Boolean,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val rowColor = if (selected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
    } else {
        Color.Transparent
    }
    Surface(
        color = rowColor,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(28.dp),
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 56.dp)
            .combinedClickable(onClick = onOpen, onLongClick = onDelete)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Text(
                ChatTitle.display(session.title, LocalContext.current.stringLookup()),
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            // Why this chat matched, when the title didn't:
            // the line of chat text holding the query.
            if (snippet != null) {
                Text(
                    snippet,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("drawer_search_snippet")
                )
            }
            // The one thing that distinguishes a seeded chat
            // from one the user held: said on the row rather
            // than only inside, so the list itself is honest.
            if (session.isSample) {
                Text(
                    stringResource(R.string.drawer_sample_chat),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1
                )
            }
            formatContextUsage(tokens, maxContextTokens)?.let { usage ->
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
    }
}

/**
 * The drawer's chat search field: one line, always visible above the list, so
 * a chat can be found by its title or by anything said in it. A full pill with
 * a quiet border, to match the panel's roundness rather than fight it.
 */
@Composable
private fun ChatSearchField(query: String, onQueryChange: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier.testTag("drawer_search"),
        singleLine = true,
        shape = RoundedCornerShape(28.dp),
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedBorderColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        ),
        textStyle = MaterialTheme.typography.bodyMedium,
        placeholder = {
            Text(
                stringResource(R.string.drawer_search_placeholder),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.drawer_search_clear)
                    )
                }
            }
        }
    )
}

/**
 * Formats a per-chat context readout like "12,345 / 70,000 tokens (18%)".
 * Returns null when there's nothing meaningful to show yet.
 */
@Composable
private fun formatContextUsage(tokens: Int, maxContextTokens: Int): String? {
    if (tokens <= 0) return null
    fun grouped(n: Int): String = "%,d".format(n)
    return if (maxContextTokens > 0) {
        val percent = (tokens.toFloat() / maxContextTokens * 100f)
            .coerceIn(0f, 100f).toInt()
        stringResource(R.string.drawer_context_usage, grouped(tokens), grouped(maxContextTokens), percent)
    } else {
        stringResource(R.string.drawer_context_tokens, grouped(tokens))
    }
}
