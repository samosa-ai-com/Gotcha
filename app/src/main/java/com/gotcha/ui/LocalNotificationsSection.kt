package com.gotcha.ui

import android.app.TimePickerDialog
import android.content.Context
import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.gotcha.data.Settings
import com.gotcha.notifications.LocalNotificationScheduler
import com.gotcha.notifications.LocalNotificationStore
import com.gotcha.notifications.inQuietHours
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Choices offered for the inactivity reminder, in days. */
private val INACTIVITY_DAY_CHOICES = listOf(2, 3, 4, 7, 14)

/** Choices offered for the daily cap. */
private val DAILY_CAP_CHOICES = listOf(1, 2, 3)

/**
 * "Gotcha notifications" on the Notifications page (issue #100): the proactive
 * notifications Gotcha decides to send from what happens on the phone —
 * reminders about unfinished chats, routines and quiet spells, and the daily
 * tip (#101) — with quiet hours, a daily cap, whether they may name chats, and
 * clearing the history. Server messages stay a section of their own.
 *
 * Every control saves as it changes, like the rest of the page.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LocalNotificationsSection(
    initial: Settings,
    onSave: ((Settings) -> Settings) -> Unit,
    canPost: Boolean,
    requestPermission: () -> Unit
) {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(initial.localNotificationsEnabled) }
    var unfinished by remember { mutableStateOf(initial.unfinishedChatRemindersEnabled) }
    var routines by remember { mutableStateOf(initial.routineSuggestionsEnabled) }
    var inactivity by remember { mutableStateOf(initial.inactivityRemindersEnabled) }
    var inactivityDays by remember { mutableStateOf(initial.inactivityDays) }
    var tips by remember { mutableStateOf(initial.dailyTipsEnabled) }
    var tipMinute by remember { mutableStateOf(initial.dailyTipMinuteOfDay) }
    var quiet by remember { mutableStateOf(initial.quietHoursEnabled) }
    var quietStart by remember { mutableStateOf(initial.quietHoursStartMinute) }
    var quietEnd by remember { mutableStateOf(initial.quietHoursEndMinute) }
    var cap by remember { mutableStateOf(initial.maxLocalNotificationsPerDay) }
    var mention by remember { mutableStateOf(initial.notificationsMentionChats) }
    var confirmClear by remember { mutableStateOf(false) }
    var cleared by remember { mutableStateOf(false) }

    /** A switch that also asks for the notification permission when turned on without it. */
    @Composable
    fun Toggle(label: String, checked: Boolean, tag: String, onChange: (Boolean) -> Unit) {
        SettingsToggleRow(
            label = label,
            checked = checked,
            onCheckedChange = {
                onChange(it)
                if (it && !canPost) requestPermission()
            },
            isLarge = true,
            switchTestTag = tag
        )
    }

    Text("Gotcha notifications", style = MaterialTheme.typography.titleMedium)
    Text(
        "Reminders and tips Gotcha decides to send, worked out on this phone from your chats. " +
            "Nothing about how you use Gotcha is uploaded to decide them.",
        style = MaterialTheme.typography.bodySmall
    )
    Toggle("Send Gotcha notifications", enabled, "settings_local_notifications_enabled") {
        enabled = it
        onSave { s -> s.copy(localNotificationsEnabled = it) }
    }
    if (!enabled) return

    Toggle("Unfinished chats", unfinished, "settings_unfinished_reminders") {
        unfinished = it
        onSave { s -> s.copy(unfinishedChatRemindersEnabled = it) }
    }
    Hint("When a task failed, was stopped, or Gotcha asked you something and you didn't answer.")

    Toggle("Routines", routines, "settings_routine_suggestions") {
        routines = it
        onSave { s -> s.copy(routineSuggestionsEnabled = it) }
    }
    Hint("When something you ask regularly is due again. Tap to ask it again, edited first if you like.")

    Toggle("After a quiet spell", inactivity, "settings_inactivity_reminders") {
        inactivity = it
        onSave { s -> s.copy(inactivityRemindersEnabled = it) }
    }
    if (inactivity) {
        Hint("When you haven't opened Gotcha for:")
        ChoiceChips(INACTIVITY_DAY_CHOICES, inactivityDays, { "$it days" }, "settings_inactivity_days") {
            inactivityDays = it
            onSave { s -> s.copy(inactivityDays = it) }
        }
    }

    Toggle("Daily tip", tips, "settings_daily_tips_enabled") {
        tips = it
        onSave { s -> s.copy(dailyTipsEnabled = it) }
    }
    Hint(
        "One thing to try each day, picked from what you haven't used yet. Tap it to start a chat " +
            "with the prompt ready to edit or send. Skipped on days you've already used Gotcha."
    )
    if (tips) {
        TimeButton("Time", tipMinute, "settings_daily_tip_time") {
            tipMinute = it
            onSave { s -> s.copy(dailyTipMinuteOfDay = it) }
            LocalNotificationScheduler.scheduleTip(context, it)
        }
        if (quiet && inQuietHours(tipMinute, quietStart, quietEnd)) {
            Warning("This time is inside your quiet hours, so no tip will arrive.")
        }
    }

    Spacer(Modifier.height(8.dp))
    Toggle("Quiet hours", quiet, "settings_quiet_hours") {
        quiet = it
        onSave { s -> s.copy(quietHoursEnabled = it) }
    }
    if (quiet) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TimeButton("From", quietStart, "settings_quiet_start") {
                quietStart = it
                onSave { s -> s.copy(quietHoursStartMinute = it) }
            }
            TimeButton("To", quietEnd, "settings_quiet_end") {
                quietEnd = it
                onSave { s -> s.copy(quietHoursEndMinute = it) }
            }
        }
    }

    Text("At most a day", style = MaterialTheme.typography.bodyMedium)
    ChoiceChips(DAILY_CAP_CHOICES, cap, { it.toString() }, "settings_daily_cap") {
        cap = it
        onSave { s -> s.copy(maxLocalNotificationsPerDay = it) }
    }
    Hint("Tips and reminders count; finished tasks and server messages don't.")

    Toggle("Name chats in notifications", mention, "settings_mention_chats") {
        mention = it
        onSave { s -> s.copy(notificationsMentionChats = it) }
    }
    Hint(
        "Off, notifications only say that something needs you, never which chat or request. " +
            "Chats kept out of notifications (from a chat's ⋮ menu, and Doctor chats unless you change it) " +
            "are never named, and the lock screen never shows either."
    )

    if (!canPost) {
        Warning("Notifications are blocked for Gotcha, so none will be shown.")
    }

    TextButton(
        onClick = { confirmClear = true },
        enabled = !cleared,
        modifier = Modifier.testTag("settings_clear_notification_history")
    ) { Text(if (cleared) "Notification history cleared" else "Clear notification history") }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear notification history?") },
            text = {
                Text(
                    "This empties the list behind the bell on the home screen. Reminders Gotcha has " +
                        "already sent won't be sent again."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    LocalNotificationStore(context).clearHistory()
                    cleared = true
                    confirmClear = false
                }) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Warning(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChoiceChips(
    choices: List<T>,
    selected: T,
    label: (T) -> String,
    tag: String,
    onSelect: (T) -> Unit
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().testTag(tag)
    ) {
        choices.forEach { choice ->
            val isSelected = choice == selected
            FilterChip(
                selected = isSelected,
                onClick = { onSelect(choice) },
                label = { Text(label(choice)) },
                leadingIcon = if (isSelected) {
                    { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                } else {
                    null
                },
                // Solid, like the switches: the default selected fill is translucent on
                // the glass skins and reads as unselected.
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                    selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    }
}

@Composable
private fun TimeButton(label: String, minuteOfDay: Int, tag: String, onPick: (Int) -> Unit) {
    val context = LocalContext.current
    TextButton(
        onClick = { showTimePicker(context, minuteOfDay, onPick) },
        modifier = Modifier.testTag(tag)
    ) { Text("$label: ${formatMinuteOfDay(minuteOfDay)}") }
}

private fun showTimePicker(context: Context, minuteOfDay: Int, onPick: (Int) -> Unit) {
    TimePickerDialog(
        context,
        { _, hour, minute -> onPick(hour * 60 + minute) },
        minuteOfDay / 60,
        minuteOfDay % 60,
        DateFormat.is24HourFormat(context)
    ).show()
}

private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

internal fun formatMinuteOfDay(minuteOfDay: Int): String =
    LocalTime.of(minuteOfDay / 60 % 24, minuteOfDay % 60).format(TIME_FORMAT)
