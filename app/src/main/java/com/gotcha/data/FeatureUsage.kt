package com.gotcha.data

import android.content.Context
import com.gotcha.scheduled.ScheduledTask
import com.gotcha.scheduled.ScheduledTaskStore

/**
 * What the user has actually done with the two introduced features.
 *
 * The home screen uses this instead of trusting its own "seen" flags alone: a
 * flag only records a tapped chip, so someone who already keeps notes or
 * already runs a task would still be told the feature is new. Usage wins.
 */
data class FeatureUsage(
    val memory: Boolean = false,
    val schedule: Boolean = false
)

/**
 * Reads both flags. Cheap for the home screen: two small memory files and a
 * couple of directory listings, plus one scheduled-task prefs read. Call it
 * from IO.
 */
fun loadFeatureUsage(
    context: Context,
    /** Injectable for tests: the real store lives in encrypted prefs. */
    loadTasks: () -> List<ScheduledTask> = { ScheduledTaskStore(context).all() }
): FeatureUsage = FeatureUsage(
    // A listing can fail on a hostile or unmounted volume; "not used" is the
    // safe answer, since it only means the chip may be offered.
    memory = runCatching { MemoryStore.isInUse() }.getOrDefault(false),
    schedule = runCatching { scheduleInUse(loadTasks()) }.getOrDefault(false)
)

/**
 * True once the user has a task of their own, or has switched one on. The four
 * paused examples that ship with the app do not count: they are the offer, not
 * the use.
 */
internal fun scheduleInUse(tasks: List<ScheduledTask>): Boolean =
    tasks.any { it.enabled || it.presetId == null }
