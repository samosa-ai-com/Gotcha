package com.gotcha.scheduled

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.gotcha.R
import com.gotcha.agent.ChatRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.ZoneId

/** What [ChatRunner.startScheduled] made of a task. */
sealed class ScheduledStart {
    data class Started(val sessionId: String) : ScheduledStart()

    /** Another run holds the agent; nothing was started. */
    object Busy : ScheduledStart()

    /**
     * Another run holds the agent, so this start is queued: the task waits
     * with a busy retry and runs when the runner frees up.
     */
    object Queued : ScheduledStart()

    data class Failed(val detail: String) : ScheduledStart()
}

/**
 * Starts the runs of scheduled tasks and writes down how they went (issue #132).
 * One lock covers both, so a run that ends at once can't have its result
 * written before the record that it started.
 */
object ScheduledTaskRuns {

    private val lock = Mutex()

    /** Starts every task that is due now, records the rest of what happened, and re-arms the alarm. */
    suspend fun fireDue(app: Application, now: Long = System.currentTimeMillis()) {
        lock.withLock {
            val store = ScheduledTaskStore(app)
            val zone = ZoneId.systemDefault()
            for (task in store.all().sortedBy { it.dueAt ?: Long.MAX_VALUE }) {
                when (val decision = ScheduledTaskPlanner.decide(task, now, zone)) {
                    ScheduledTaskPlanner.Decision.Wait -> Unit
                    is ScheduledTaskPlanner.Decision.Missed -> store.put(decision.task)
                    // The first due task takes the runner; the ones after it find it
                    // busy and wait their turn.
                    ScheduledTaskPlanner.Decision.Run -> start(app, store, task, now, zone, userInitiated = false)
                }
            }
        }
        ScheduledTaskScheduler.reschedule(app)
    }

    /**
     * Runs task [id] now, for "Run now" in Settings or a tap on its "tap to run" notification.
     * The task's schedule is left as it is, unless this was its scheduled time.
     */
    suspend fun runNow(app: Application, id: String, now: Long = System.currentTimeMillis()): ScheduledStart {
        val result = lock.withLock {
            val store = ScheduledTaskStore(app)
            val task = store.get(id) ?: return@withLock ScheduledStart.Failed(app.getString(R.string.scheduled_task_gone))
            start(app, store, task, now, ZoneId.systemDefault(), userInitiated = true)
        }
        ScheduledTaskScheduler.reschedule(app)
        return result
    }

    /** The run of task [taskId] in [sessionId] ended with [status]; [detail] is its error, if any. */
    suspend fun recordOutcome(
        context: Context,
        taskId: String,
        sessionId: String,
        status: TaskRunStatus,
        detail: String?,
        now: Long = System.currentTimeMillis()
    ) {
        lock.withLock {
            ScheduledTaskStore(context).update(taskId) {
                ScheduledTaskPlanner.finished(it, status, sessionId, detail, now)
            }
        }
        ScheduledTaskScheduler.reschedule(context)
    }

    private suspend fun start(
        app: Application,
        store: ScheduledTaskStore,
        task: ScheduledTask,
        now: Long,
        zone: ZoneId,
        userInitiated: Boolean
    ): ScheduledStart {
        val notifier = ScheduledTaskNotifier(app)
        val attachments = store.loadAttachments(task)
        if (attachments == null) {
            val detail = app.getString(R.string.scheduled_task_files_missing)
            store.update(task.id) { ScheduledTaskPlanner.notStarted(it, TaskRunStatus.NOT_STARTED, detail, now, zone) }
            // notStarted clears any wait, so its notification must go too.
            notifier.cancel(task.id)
            return ScheduledStart.Failed(detail)
        }
        if (!userInitiated && !ScheduledTaskScheduler.canRunUnattended(app)) {
            // Started from a window alarm, the keep-alive service is refused and the
            // run would be frozen with the app; a tap on the notification runs it instead.
            store.update(task.id) { ScheduledTaskPlanner.notStarted(it, TaskRunStatus.NEEDS_TAP, null, now, zone) }
            notifier.postNeedsTap(task)
            return ScheduledStart.Failed(app.getString(R.string.scheduled_task_needs_exact_alarms))
        }
        val result = withContext(Dispatchers.Main) { ChatRunner.of(app).startScheduled(task, attachments) }
        when (result) {
            is ScheduledStart.Started -> {
                store.update(task.id) { ScheduledTaskPlanner.started(it, result.sessionId, now, zone) }
                notifier.cancel(task.id)
            }
            ScheduledStart.Busy -> {
                // A paused task has no alarm coming, so a wait for it would
                // never fire: don't promise a queue, just stay busy.
                if (userInitiated && !task.enabled) return result
                // Automatic fires and manual starts alike wait their turn: the
                // occurrence is claimed and retried, never dropped. A manual
                // start reports Queued so the UI can say so; its retry fires
                // through the alarm path like any other wait. The enabled
                // check runs inside the update: a pause landing mid-start
                // must not leave a wait no alarm will ever fire.
                val updated = store.update(task.id) {
                    if (!it.enabled) it else ScheduledTaskPlanner.busy(it, now, zone)
                }
                // A wait that just gave up (SKIPPED_BUSY, no retry) is not
                // queued: report Busy so nothing is promised.
                if (updated?.enabled == true && updated.retryAt != null) {
                    notifier.postWaiting(updated)
                    return if (userInitiated) ScheduledStart.Queued else result
                }
                notifier.cancel(task.id)
                return result
            }
            is ScheduledStart.Failed -> {
                store.update(task.id) {
                    ScheduledTaskPlanner.notStarted(it, TaskRunStatus.NOT_STARTED, result.detail, now, zone)
                }
                notifier.cancel(task.id)
            }
            ScheduledStart.Queued -> {
                // startScheduled never returns this; kept for exhaustiveness.
            }
        }
        return result
    }
}

/**
 * The scheduled-task alarm going off. Not exported: only Gotcha's own PendingIntent fires it.
 *
 * The pass (decrypting the store, loading files, starting the runner, for every
 * due task) can outlast a broadcast's time on a cold start, and Android may
 * then kill the process before the starts are recorded or the alarm re-armed.
 * So a handoff alarm is armed first, before any of that work, and cleared once
 * the pass is done; a pass cut short is made again when it fires. Making it
 * again is safe: a task whose start was recorded is no longer due, and one
 * whose start was not had its run killed with the process.
 */
class ScheduledTaskReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as Application
        if (intent.action != ACTION_FIRE) return
        ScheduledTaskScheduler.armHandoff(app)
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                ScheduledTaskRuns.fireDue(app)
            } catch (e: Exception) {
                Log.w(TAG, "Scheduled task work failed", e)
                // fireDue re-arms only when it finishes; without this the next task would never fire.
                runCatching { ScheduledTaskScheduler.reschedule(app) }
            } finally {
                ScheduledTaskScheduler.cancelHandoff(app)
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "ScheduledTasks"
        const val ACTION_FIRE = "com.gotcha.scheduled.FIRE"
    }
}
