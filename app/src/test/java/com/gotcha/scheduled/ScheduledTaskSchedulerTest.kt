package com.gotcha.scheduled

import android.app.AlarmManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.gotcha.testsupport.FakeAndroidKeyStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * [ScheduledTaskScheduler]'s handoff alarm (issue #132): armed before a pass's
 * slow work, it stands apart from the task alarm, so a pass killed half way is
 * made again whatever the task alarm was set to.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScheduledTaskSchedulerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val alarms = shadowOf(context.getSystemService(AlarmManager::class.java))

    @Before
    fun setUp() {
        FakeAndroidKeyStore.setUp()
        ScheduledTaskStore(context).let { store -> store.all().forEach { store.delete(it.id) } }
    }

    @Test
    fun `the handoff fires a minute on, and is not cleared by re-arming the task alarm`() {
        val now = 1_000_000L
        ScheduledTaskScheduler.armHandoff(context, now)
        assertEquals(now + ScheduledTaskScheduler.HANDOFF_MS, alarms.scheduledAlarms.single().triggerAtTime)

        // No task is due, so the task alarm is cancelled; the handoff stays.
        ScheduledTaskScheduler.reschedule(context)
        assertEquals(1, alarms.scheduledAlarms.size)

        ScheduledTaskScheduler.cancelHandoff(context)
        assertTrue(alarms.scheduledAlarms.isEmpty())
    }
}
