package com.gotcha.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gotcha.notifications.InboxEntry

/**
 * The in-app notification inbox (issue #100): everything Gotcha has notified
 * about in the last 30 days — its own reminders and tips, finished tasks and
 * messages from Samosa AI — newest first. Tapping an entry goes where its
 * notification would have gone.
 *
 * Opening the inbox counts as reading it, so the badge clears; the entries
 * that were unread keep their dot for this visit.
 */
@Composable
fun InboxScreen(
    load: () -> List<InboxEntry>,
    onOpened: () -> Unit,
    onClear: () -> Unit,
    onOpenEntry: (InboxEntry) -> Unit,
    onBack: () -> Unit
) {
    var entries by remember { mutableStateOf(load()) }
    var confirmClear by remember { mutableStateOf(false) }
    val overlay = rememberSettingsOverlayState()
    LaunchedEffect(Unit) { onOpened() }

    SettingsScaffold(
        title = "Notifications",
        onBack = onBack,
        overlay = overlay,
        header = if (entries.isEmpty()) {
            null
        } else {
            {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(
                        onClick = { confirmClear = true },
                        modifier = Modifier.testTag("inbox_clear")
                    ) { Text("Clear history") }
                }
            }
        }
    ) {
        if (entries.isEmpty()) {
            Text(
                "Nothing here yet. Reminders, daily tips, finished tasks and messages from " +
                    "Samosa AI are listed here for 30 days.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("inbox_empty")
            )
        }
        entries.forEach { entry -> InboxRow(entry, onClick = { onOpenEntry(entry) }) }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear notification history?") },
            text = {
                Text("This empties the list. Reminders Gotcha has already sent won't be sent again.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onClear()
                        entries = emptyList()
                        confirmClear = false
                    },
                    modifier = Modifier.testTag("inbox_clear_confirm")
                ) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun InboxRow(entry: InboxEntry, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = scheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag("inbox_entry")
    ) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            Box(modifier = Modifier.padding(top = 6.dp, end = 10.dp).size(8.dp)) {
                if (!entry.read) {
                    Surface(shape = CircleShape, color = scheme.primary, modifier = Modifier.size(8.dp)) {}
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "${entry.categoryOrNull?.label ?: "Notification"} · ${inboxTime(entry.postedAt)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant
                )
                Text(
                    entry.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    entry.body,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** "just now", "5m ago", "3h ago", "2d ago". */
internal fun inboxTime(epochMillis: Long, now: Long = System.currentTimeMillis()): String {
    val minutes = (now - epochMillis) / 60_000L
    return when {
        minutes < 1L -> "just now"
        minutes < 60L -> "${minutes}m ago"
        minutes < 24L * 60L -> "${minutes / 60L}h ago"
        else -> "${minutes / (24L * 60L)}d ago"
    }
}
