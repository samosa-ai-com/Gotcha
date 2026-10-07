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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.gotcha.R
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

    Text(
        stringResource(R.string.local_notifications_gotcha_notifications),
        style = MaterialTheme.typography.titleMedium
    )
    Text(
        stringResource(R.string.local_notifications_reminders_and_tips_gotcha_decides),
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
    Hint(stringResource(R.string.local_notifications_when_a_task_failed_was))

    Toggle("Routines", routines, "settings_routine_suggestions") {
        routines = it
        onSave { s -> s.copy(routineSuggestionsEnabled = it) }
    }
    Hint(stringResource(R.string.local_notifications_when_something_you_ask_regularly))

    Toggle("After a quiet spell", inactivity, "settings_inactivity_reminders") {
        inactivity = it
        onSave { s -> s.copy(inactivityRemindersEnabled = it) }
    }
    if (inactivity) {
        Hint(stringResource(R.string.local_notifications_when_you_haven_t_opened))
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
        stringResource(R.string.local_notifications_one_thing_to_try_each)
    )
    if (tips) {
        TimeButton("Time", tipMinute, "settings_daily_tip_time") {
            tipMinute = it
            onSave { s -> s.copy(dailyTipMinuteOfDay = it) }
            LocalNotificationScheduler.scheduleTip(context, it)
        }
        if (quiet && inQuietHours(tipMinute, quietStart, quietEnd)) {
            Warning(stringResource(R.string.local_notifications_this_time_is_inside_your))
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

    Text(stringResource(R.string.local_notifications_at_most_a_day), style = MaterialTheme.typography.bodyMedium)
    ChoiceChips(DAILY_CAP_CHOICES, cap, { it.toString() }, "settings_daily_cap") {
        cap = it
        onSave { s -> s.copy(maxLocalNotificationsPerDay = it) }
    }
    Hint(stringResource(R.string.local_notifications_tips_and_reminders_count_finished))

    Toggle("Name chats in notifications", mention, "settings_mention_chats") {
        mention = it
        onSave { s -> s.copy(notificationsMentionChats = it) }
    }
    Hint(
        stringResource(R.string.local_notifications_off_notifications_only_say_that)
    )

    if (!canPost) {
        Warning(stringResource(R.string.local_notifications_notifications_are_blocked_for_gotcha))
    }

    TextButton(
        onClick = { confirmClear = true },
        enabled = !cleared,
        modifier = Modifier.testTag("settings_clear_notification_history")
    ) {
        Text(
            stringResource(
                if (cleared) {
                    R.string.local_notifications_history_cleared
                } else {
                    R.string.local_notifications_clear_history
                }
            )
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.local_notifications_clear_notification_history)) },
            text = {
                Text(
                    stringResource(R.string.local_notifications_this_empties_the_list_behind)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    LocalNotificationStore(context).clearHistory()
                    cleared = true
                    confirmClear = false
                }) { Text(stringResource(R.string.local_notifications_clear)) }
            },
            dismissButton = {
                TextButton(
                    onClick = { confirmClear = false }
                ) { Text(stringResource(R.string.local_notifications_cancel)) }
            }
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
    ) { Text(stringResource(R.string.time_button_label, label, formatMinuteOfDay(minuteOfDay))) }
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
