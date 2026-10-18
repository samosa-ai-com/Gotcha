package com.gotcha.ui

import android.app.Application
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.gotcha.R
import com.gotcha.agent.AttachmentFiles
import com.gotcha.agent.ChatTitle
import com.gotcha.agent.ComposerAttachment
import com.gotcha.data.ChatHistoryRepository
import com.gotcha.i18n.stringLookup
import com.gotcha.scheduled.Repeat
import com.gotcha.scheduled.RepeatKind
import com.gotcha.scheduled.ScheduledStart
import com.gotcha.scheduled.ScheduledTask
import com.gotcha.scheduled.ScheduledTaskPlanner
import com.gotcha.scheduled.ScheduledTaskRuns
import com.gotcha.scheduled.ScheduledTaskScheduler
import com.gotcha.scheduled.ScheduledTaskStore
import com.gotcha.scheduled.TaskRun
import com.gotcha.scheduled.TaskRunStatus
import com.gotcha.tools.AgentMode
import com.gotcha.ui.theme.SkinAlertDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale
import java.util.UUID

/** [editing] value for the add form, as opposed to a task id. */
private const val NEW_TASK = "+new"

/**
 * Settings ▸ Scheduled tasks (issue #132): prompts Gotcha sends itself at set
 * times, each run landing in a chat like any other. One row per task with its
 * switch, schedule, next and last run, and its history, failures included;
 * the add/edit form is inline, like the connector cards'.
 */
@Composable
fun ScheduledTasksScreen(onBack: () -> Unit, onOpenChat: (String) -> Unit) {
    val context = LocalContext.current
    val store = remember { ScheduledTaskStore(context) }
    val scope = rememberCoroutineScope()
    val overlay = rememberSettingsOverlayState()
    // Bumped after every change, and on resume (the run may have ended, or the
    // exact-alarm grant changed), so the list is read again.
    var tick by remember { mutableStateOf(0) }
    var editing by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<ScheduledTask?>(null) }
    val tasks = remember(tick) { store.all() }
    val canRunUnattended = remember(tick) { ScheduledTaskScheduler.canRunUnattended(context) }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) tick++ }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    /** Saves [task] as edited, re-armed, and the alarm with it. */
    fun changed(task: ScheduledTask) {
        scope.launch {
            withContext(Dispatchers.IO) {
                store.put(ScheduledTaskPlanner.armed(task, System.currentTimeMillis(), ZoneId.systemDefault()))
                ScheduledTaskScheduler.reschedule(context)
            }
            tick++
        }
    }

    val startedText = stringResource(R.string.scheduled_run_now_started)
    val busyText = stringResource(R.string.scheduled_run_now_busy)
    SettingsScaffold(title = stringResource(SettingsPage.SCHEDULED_TASKS.title), onBack = onBack, overlay = overlay) {
        Text(stringResource(R.string.scheduled_intro), style = MaterialTheme.typography.bodyMedium)
        if (!canRunUnattended) ExactAlarmHint(context)
        if (tasks.isEmpty() && editing != NEW_TASK) {
            Text(
                stringResource(R.string.scheduled_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        tasks.forEach { task ->
            HorizontalDivider()
            if (editing == task.id) {
                TaskEditor(
                    store = store,
                    initial = task,
                    onSaved = {
                        editing = null
                        tick++
                    },
                    onCancel = { editing = null }
                )
            } else {
                TaskRow(
                    task = task,
                    onEnabledChange = { changed(task.copy(enabled = it)) },
                    onEdit = { editing = task.id },
                    onRunNow = {
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                ScheduledTaskRuns.runNow(context.applicationContext as Application, task.id)
                            }
                            overlay.show(
                                when (result) {
                                    is ScheduledStart.Started -> startedText
                                    ScheduledStart.Busy -> busyText
                                    is ScheduledStart.Failed -> result.detail
                                }
                            )
                            tick++
                        }
                    },
                    onDelete = { deleting = task },
                    onOpenChat = onOpenChat
                )
            }
        }
        HorizontalDivider()
        if (editing == NEW_TASK) {
            TaskEditor(
                store = store,
                initial = null,
                onSaved = {
                    editing = null
                    tick++
                },
                onCancel = { editing = null }
            )
        } else {
            Button(
                onClick = { editing = NEW_TASK },
                modifier = Modifier.fillMaxWidth().testTag("scheduled_add_task")
            ) { Text(stringResource(R.string.scheduled_add_task)) }
        }
    }

    deleting?.let { task ->
        SkinAlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.scheduled_delete_title, task.name)) },
            text = { Text(stringResource(R.string.scheduled_delete_body)) },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            store.delete(task.id)
                            ScheduledTaskScheduler.reschedule(context)
                        }
                        if (editing == task.id) editing = null
                        tick++
                    }
                }) { Text(stringResource(R.string.scheduled_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.scheduled_cancel)) }
            }
        )
    }
}

/** Shown while Gotcha may not set exact alarms: tasks then wait for a tap instead of running. */
@Composable
private fun ExactAlarmHint(context: Context) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            stringResource(R.string.scheduled_exact_alarm_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            OutlinedButton(
                onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(
                                android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                Uri.parse("package:${context.packageName}")
                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                },
                modifier = Modifier.testTag("scheduled_allow_exact_alarms")
            ) { Text(stringResource(R.string.scheduled_exact_alarm_allow)) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TaskRow(
    task: ScheduledTask,
    onEnabledChange: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onRunNow: () -> Unit,
    onDelete: () -> Unit,
    onOpenChat: (String) -> Unit
) {
    val context = LocalContext.current
    var showHistory by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(task.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Text(scheduleText(context, task.start, task.repeat), style = MaterialTheme.typography.bodySmall)
            }
            Switch(
                checked = task.enabled,
                onCheckedChange = onEnabledChange,
                modifier = Modifier.testTag("scheduled_task_enabled_${task.id}")
            )
        }
        if (task.createdByAgent) {
            Text(
                stringResource(R.string.scheduled_set_up_by_gotcha),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
        val next = task.dueAt
        Text(
            when {
                !task.enabled -> stringResource(R.string.scheduled_paused)
                next == null -> stringResource(R.string.scheduled_no_more_runs)
                else -> stringResource(R.string.scheduled_next_run, formatDateTime(next))
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            stringResource(
                if (task.sameChat) R.string.scheduled_target_same_chat else R.string.scheduled_target_new_chat
            ) + " · " + stringResource(task.mode.labelRes()),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        task.lastRun?.let { run ->
            Text(
                stringResource(R.string.scheduled_last_run, statusText(run.status), formatDateTime(run.at)),
                style = MaterialTheme.typography.bodySmall,
                color = if (run.status.isProblem()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = onEdit) { Text(stringResource(R.string.scheduled_edit)) }
            TextButton(onClick = onRunNow, modifier = Modifier.testTag("scheduled_run_now_${task.id}")) {
                Text(stringResource(R.string.scheduled_run_now))
            }
            if (task.history.isNotEmpty()) {
                TextButton(onClick = { showHistory = !showHistory }) {
                    Text(
                        stringResource(if (showHistory) R.string.scheduled_hide_history else R.string.scheduled_history)
                    )
                }
            }
            TextButton(onClick = onDelete) { Text(stringResource(R.string.scheduled_delete)) }
        }
        if (showHistory) {
            task.history.asReversed().forEach { run -> HistoryRow(run, onOpenChat) }
        }
    }
}

@Composable
private fun HistoryRow(run: TaskRun, onOpenChat: (String) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                formatDateTime(run.at) + " · " + statusText(run.status),
                style = MaterialTheme.typography.bodySmall,
                color = if (run.status.isProblem()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
            )
            run.detail?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        run.sessionId?.let { id ->
            TextButton(onClick = { onOpenChat(id) }) { Text(stringResource(R.string.scheduled_open_chat)) }
        }
    }
}

/** The add/edit form. [initial] null adds a task. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
@Suppress("LongMethod", "CyclomaticComplexMethod")
private fun TaskEditor(
    store: ScheduledTaskStore,
    initial: ScheduledTask?,
    onSaved: () -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val start = remember { initial?.start ?: defaultStart() }
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var prompt by remember { mutableStateOf(initial?.prompt.orEmpty()) }
    var date by remember { mutableStateOf(start.toLocalDate()) }
    var time by remember { mutableStateOf(start.toLocalTime()) }
    var kind by remember { mutableStateOf(initial?.repeat?.kind ?: RepeatKind.NONE) }
    var interval by remember { mutableStateOf((initial?.repeat?.interval ?: 1).toString()) }
    var weekdays by remember { mutableStateOf(initial?.repeat?.weekdays?.toSet() ?: emptySet()) }
    var mode by remember { mutableStateOf(initial?.mode ?: AgentMode.MONITOR) }
    var personaId by remember { mutableStateOf(initial?.personaId) }
    var sameChat by remember { mutableStateOf(initial?.sameChat ?: false) }
    var chatSessionId by remember { mutableStateOf(initial?.chatSessionId) }
    var retries by remember { mutableStateOf(initial?.maxRetries ?: ScheduledTask.DEFAULT_MAX_RETRIES) }
    var files by remember { mutableStateOf<List<ComposerAttachment>>(emptyList()) }
    var error by remember { mutableStateOf("") }
    var chats by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            initial?.let { files = store.loadAttachments(it).orEmpty() }
            chats = ChatHistoryRepository(context).mapSessions { if (it.isSample) null else it.id to it.title }
                .filterNotNull()
        }
    }

    val unreadable = stringResource(R.string.scheduled_file_unreadable)
    val tooMany = stringResource(R.string.scheduled_too_many_files, ComposerAttachment.MAX_PER_MESSAGE)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val loaded = withContext(Dispatchers.IO) { uris.map { readPickedFile(context, it) } }
            val ok = loaded.filterNotNull()
            val room = ComposerAttachment.MAX_PER_MESSAGE - files.size
            files = files + ok.take(room.coerceAtLeast(0))
            error = when {
                ok.size > room -> tooMany
                ok.size < loaded.size -> unreadable
                else -> ""
            }
        }
    }

    val missingName = stringResource(R.string.scheduled_error_name)
    val missingPrompt = stringResource(R.string.scheduled_error_prompt)
    val pastTime = stringResource(R.string.scheduled_error_past)
    val badInterval = stringResource(R.string.scheduled_error_interval, ScheduledTask.MAX_INTERVAL)
    val tooMuchText = stringResource(R.string.scheduled_error_too_much_text)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(80) },
            label = { Text(stringResource(R.string.scheduled_field_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("scheduled_field_name")
        )
        OutlinedTextField(
            value = prompt,
            onValueChange = { prompt = it.take(4000) },
            label = { Text(stringResource(R.string.scheduled_field_prompt)) },
            minLines = 3,
            modifier = Modifier.fillMaxWidth().testTag("scheduled_field_prompt")
        )

        Text(stringResource(R.string.scheduled_field_when), style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { showDatePicker(context, date) { date = it } }) {
                Text(date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)))
            }
            OutlinedButton(onClick = { showTimePicker(context, time) { time = it } }) {
                Text(time.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)))
            }
        }

        Text(stringResource(R.string.scheduled_field_repeat), style = MaterialTheme.typography.labelLarge)
        ChoiceChips(
            options = listOf(
                RepeatKind.NONE to stringResource(R.string.scheduled_repeat_none),
                RepeatKind.HOURLY to stringResource(R.string.scheduled_repeat_hourly),
                RepeatKind.DAILY to stringResource(R.string.scheduled_repeat_daily),
                RepeatKind.WEEKLY to stringResource(R.string.scheduled_repeat_weekly),
                RepeatKind.MONTHLY to stringResource(R.string.scheduled_repeat_monthly)
            ),
            selected = kind,
            onSelect = { kind = it }
        )
        if (kind == RepeatKind.HOURLY || kind == RepeatKind.DAILY) {
            OutlinedTextField(
                value = interval,
                onValueChange = { interval = it.filter(Char::isDigit).take(3) },
                label = {
                    Text(
                        stringResource(
                            if (kind == RepeatKind.HOURLY) R.string.scheduled_field_every_hours else R.string.scheduled_field_every_days
                        )
                    )
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (kind == RepeatKind.WEEKLY) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                val shown = weekdays.ifEmpty { setOf(date.dayOfWeek.value) }
                DayOfWeek.entries.forEach { day ->
                    val chosen = day.value in shown
                    SolidChip(
                        label = day.getDisplayName(TextStyle.SHORT, Locale.getDefault()),
                        selected = chosen,
                        onClick = {
                            val next = if (chosen) shown - day.value else shown + day.value
                            weekdays = next.ifEmpty { shown }
                        }
                    )
                }
            }
        }

        Text(stringResource(R.string.scheduled_field_mode), style = MaterialTheme.typography.labelLarge)
        ChoiceChips(
            options = AgentMode.entries.map { it to stringResource(it.labelRes()) },
            selected = mode,
            onSelect = { mode = it }
        )
        Text(
            stringResource(R.string.scheduled_mode_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Text(stringResource(R.string.scheduled_field_persona), style = MaterialTheme.typography.labelLarge)
        ChoiceChips(
            options = listOf<Pair<String?, String>>(null to stringResource(R.string.scheduled_persona_none)) +
                PERSONAS.map { it.id to stringResource(it.name) },
            selected = personaId,
            onSelect = { personaId = it }
        )

        Text(stringResource(R.string.scheduled_field_target), style = MaterialTheme.typography.labelLarge)
        ChoiceChips(
            options = listOf(
                false to stringResource(R.string.scheduled_target_new_chat),
                true to stringResource(R.string.scheduled_target_same_chat)
            ),
            selected = sameChat,
            onSelect = { sameChat = it }
        )
        if (sameChat) {
            val strings = context.stringLookup()
            Text(
                stringResource(R.string.scheduled_same_chat_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            ChoiceChips(
                options = listOf<Pair<String?, String>>(null to stringResource(R.string.scheduled_chat_first_run)) +
                    chats.take(MAX_CHATS_OFFERED).map { (id, title) -> id to ChatTitle.display(title, strings) },
                selected = chatSessionId,
                onSelect = { chatSessionId = it }
            )
        }

        Text(stringResource(R.string.scheduled_field_files), style = MaterialTheme.typography.labelLarge)
        files.forEach { file ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(file.name, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                TextButton(onClick = { files = files.filterNot { it.id == file.id } }) {
                    Text(stringResource(R.string.scheduled_remove_file))
                }
            }
        }
        OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }) {
            Text(stringResource(R.string.scheduled_add_files))
        }

        Text(stringResource(R.string.scheduled_field_retries), style = MaterialTheme.typography.labelLarge)
        ChoiceChips(
            options = (0..3).map { it to pluralStringResource(R.plurals.scheduled_retries, it, it) },
            selected = retries,
            onSelect = { retries = it }
        )
        Text(
            stringResource(R.string.scheduled_unattended_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (error.isNotBlank()) {
            Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    val every = interval.toIntOrNull()
                    val startAt = LocalDateTime.of(date, time.withSecond(0).withNano(0))
                    error = when {
                        name.isBlank() -> missingName
                        prompt.isBlank() -> missingPrompt
                        (kind == RepeatKind.HOURLY || kind == RepeatKind.DAILY) &&
                            (every == null || every !in 1..ScheduledTask.MAX_INTERVAL) -> badInterval
                        kind == RepeatKind.NONE &&
                            !startAt.atZone(ZoneId.systemDefault()).toInstant().isAfter(Instant.now()) -> pastTime
                        AttachmentFiles.documentChars(files) > ComposerAttachment.MAX_TOTAL_DOCUMENT_CHARS -> tooMuchText
                        else -> ""
                    }
                    if (error.isNotEmpty()) return@Button
                    val task = (
                        initial ?: ScheduledTask(
                            id = UUID.randomUUID().toString(),
                            name = "",
                            prompt = "",
                            startLocal = "",
                            createdAt = System.currentTimeMillis()
                        )
                        ).copy(
                        name = name.trim(),
                        prompt = prompt.trim(),
                        startLocal = startAt.toString(),
                        repeat = Repeat(
                            kind = kind,
                            interval = if (kind == RepeatKind.HOURLY || kind == RepeatKind.DAILY) every ?: 1 else 1,
                            weekdays = if (kind == RepeatKind.WEEKLY) weekdays.sorted() else emptyList()
                        ),
                        agentMode = mode.name,
                        personaId = personaId,
                        sameChat = sameChat,
                        chatSessionId = if (sameChat) chatSessionId else null,
                        maxRetries = retries,
                        attachmentNames = files.map { it.name }
                    )
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            store.saveAttachments(task.id, files)
                            store.put(
                                ScheduledTaskPlanner.armed(task, System.currentTimeMillis(), ZoneId.systemDefault())
                            )
                            ScheduledTaskScheduler.reschedule(context)
                        }
                        onSaved()
                    }
                },
                modifier = Modifier.testTag("scheduled_save")
            ) { Text(stringResource(R.string.scheduled_save)) }
            TextButton(onClick = onCancel) { Text(stringResource(R.string.scheduled_cancel)) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChoiceChips(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEach { (value, label) ->
            SolidChip(label = label, selected = value == selected, onClick = { onSelect(value) })
        }
    }
}

/** Solid when chosen, like the persona chips: the default fill goes translucent on the glass skins. */
@Composable
private fun SolidChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = if (selected) {
            { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
        } else {
            null
        },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primary,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
            selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimary
        )
    )
}

/** The schedule in the user's language: "Every 2 days at 08:00", "Once, 19 Oct 2026, 08:00". */
internal fun scheduleText(context: Context, start: LocalDateTime, repeat: Repeat): String {
    val time = start.toLocalTime().format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
    val res = context.resources
    return when (repeat.kind) {
        RepeatKind.NONE -> context.getString(
            R.string.scheduled_schedule_once,
            start.format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT))
        )
        // "Every hour" has a string of its own: in some languages the plural for
        // one also covers zero, so it has to show the number.
        RepeatKind.HOURLY -> if (repeat.interval == 1) {
            context.getString(R.string.scheduled_schedule_every_hour)
        } else {
            res.getQuantityString(R.plurals.scheduled_schedule_hours, repeat.interval, repeat.interval)
        }
        RepeatKind.DAILY -> if (repeat.interval == 1) {
            context.getString(R.string.scheduled_schedule_every_day, time)
        } else {
            res.getQuantityString(R.plurals.scheduled_schedule_days, repeat.interval, repeat.interval, time)
        }
        RepeatKind.WEEKLY -> context.getString(
            R.string.scheduled_schedule_weekly,
            repeat.weekdays.ifEmpty { listOf(start.dayOfWeek.value) }
                .joinToString { DayOfWeek.of(it).getDisplayName(TextStyle.SHORT, Locale.getDefault()) },
            time
        )
        RepeatKind.MONTHLY -> context.getString(R.string.scheduled_schedule_monthly, start.dayOfMonth, time)
    }
}

@Composable
private fun statusText(status: TaskRunStatus): String = stringResource(
    when (status) {
        TaskRunStatus.DONE -> R.string.scheduled_status_done
        TaskRunStatus.FAILED -> R.string.scheduled_status_failed
        TaskRunStatus.STOPPED -> R.string.scheduled_status_stopped
        TaskRunStatus.MISSED -> R.string.scheduled_status_missed
        TaskRunStatus.SKIPPED_BUSY -> R.string.scheduled_status_skipped_busy
        TaskRunStatus.NEEDS_TAP -> R.string.scheduled_status_needs_tap
        TaskRunStatus.NOT_STARTED -> R.string.scheduled_status_not_started
    }
)

private fun AgentMode.labelRes(): Int = when (this) {
    AgentMode.MONITOR -> R.string.chat_monitor
    AgentMode.OPERATOR -> R.string.chat_operator
}

private fun TaskRunStatus.isProblem(): Boolean = this != TaskRunStatus.DONE && this != TaskRunStatus.STOPPED

private fun formatDateTime(at: Long): String =
    LocalDateTime.ofInstant(Instant.ofEpochMilli(at), ZoneId.systemDefault())
        .format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT))

/** A new task starts on the next whole hour. */
private fun defaultStart(): LocalDateTime =
    LocalDateTime.now().withMinute(0).withSecond(0).withNano(0).plusHours(1)

private fun showDatePicker(context: Context, date: LocalDate, onPick: (LocalDate) -> Unit) {
    DatePickerDialog(
        context,
        { _, year, month, day -> onPick(LocalDate.of(year, month + 1, day)) },
        date.year,
        date.monthValue - 1,
        date.dayOfMonth
    ).show()
}

private fun showTimePicker(context: Context, time: LocalTime, onPick: (LocalTime) -> Unit) {
    TimePickerDialog(
        context,
        { _, hour, minute -> onPick(LocalTime.of(hour, minute)) },
        time.hour,
        time.minute,
        DateFormat.is24HourFormat(context)
    ).show()
}

/** Reads one picked file into an attachment, as the composer does; null when it can't be read. */
private fun readPickedFile(context: Context, uri: Uri): ComposerAttachment? = runCatching {
    val resolver = context.contentResolver
    val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
    val name = resolver.query(uri, null, null, null, null)?.use { cursor ->
        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (cursor.moveToFirst() && index >= 0) cursor.getString(index) else null
    }?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment.orEmpty().ifBlank { "file" }
    AttachmentFiles.fromBytes(bytes, name, resolver.getType(uri))
}.getOrNull()

/** How many recent chats the same-chat picker offers. */
private const val MAX_CHATS_OFFERED = 8
