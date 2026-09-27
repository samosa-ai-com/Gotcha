package com.gotcha.service

import com.gotcha.service.DisplayTintGuard.Companion.NIGHT_DISPLAY_ACTIVATED
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DisplayTintGuardTest {

    /** Night Light as the fake settings store sees it, with a log of every write. */
    private class FakeSettings(var nightLight: Int?, var writable: Boolean = true) :
        DisplayTintGuard.SecureSettingAccess {
        val writes = mutableListOf<Int>()
        override fun read(key: String): Int? = if (key == NIGHT_DISPLAY_ACTIVATED) nightLight else null
        override fun write(key: String, value: Int): Boolean {
            if (!writable) return false
            writes += value
            nightLight = value
            return true
        }
    }

    private class FakePending : DisplayTintGuard.PendingRestore {
        override var isPending: Boolean = false
    }

    private fun guard(settings: FakeSettings, pending: FakePending = FakePending(), enabled: Boolean = true) =
        DisplayTintGuard(settings, pending, isEnabled = { enabled }, settleMillis = 3_000L)

    @Test
    fun `turns Night Light off for the capture and back on after`() = runTest {
        val settings = FakeSettings(nightLight = 1)
        val pending = FakePending()
        val seen = guard(settings, pending).withTintSuspended {
            assertTrue("marker set while suspended", pending.isPending)
            settings.nightLight
        }
        assertEquals(0, seen)
        assertEquals(listOf(0, 1), settings.writes)
        assertFalse(pending.isPending)
    }

    @Test
    fun `waits for the fade before capturing`() = runTest {
        val start = testScheduler.currentTime
        guard(FakeSettings(nightLight = 1)).withTintSuspended {
            assertEquals(3_000L, testScheduler.currentTime - start)
        }
    }

    @Test
    fun `leaves everything alone when Night Light is already off`() = runTest {
        val settings = FakeSettings(nightLight = 0)
        val start = testScheduler.currentTime
        guard(settings).withTintSuspended { }
        assertTrue(settings.writes.isEmpty())
        assertEquals("no settle delay", start, testScheduler.currentTime)
    }

    @Test
    fun `leaves everything alone when the setting is off`() = runTest {
        val settings = FakeSettings(nightLight = 1)
        guard(settings, enabled = false).withTintSuspended { }
        assertTrue(settings.writes.isEmpty())
    }

    @Test
    fun `captures as before when it may not write secure settings`() = runTest {
        val settings = FakeSettings(nightLight = 1, writable = false)
        val pending = FakePending()
        val result = guard(settings, pending).withTintSuspended { "captured" }
        assertEquals("captured", result)
        assertEquals(1, settings.nightLight)
        assertFalse(pending.isPending)
    }

    @Test
    fun `restores Night Light when the capture throws`() = runTest {
        val settings = FakeSettings(nightLight = 1)
        try {
            guard(settings).withTintSuspended { error("capture failed") }
            fail("expected the capture's exception")
        } catch (_: IllegalStateException) {
        }
        assertEquals(1, settings.nightLight)
    }

    @Test
    fun `restores Night Light when the capture is cancelled`() = runTest {
        val settings = FakeSettings(nightLight = 1)
        val started = CompletableDeferred<Unit>()
        val job = launch {
            guard(settings).withTintSuspended {
                started.complete(Unit)
                CompletableDeferred<Unit>().await()
            }
        }
        started.await()
        job.cancel(CancellationException("user left"))
        job.join()
        assertEquals(1, settings.nightLight)
    }

    @Test
    fun `overlapping captures restore once, after the last`() = runTest {
        val settings = FakeSettings(nightLight = 1)
        val g = guard(settings)
        val releaseFirst = CompletableDeferred<Unit>()
        val first = async { g.withTintSuspended { releaseFirst.await() } }
        advanceUntilIdle()
        val second = async { g.withTintSuspended { settings.nightLight } }
        assertEquals("second capture also sees Night Light off", 0, second.await())
        assertEquals("still off while the first is running", 0, settings.nightLight)
        releaseFirst.complete(Unit)
        first.await()
        assertEquals(listOf(0, 1), settings.writes)
    }

    @Test
    fun `a restore interrupted by a process death is finished on the next start`() = runTest {
        // What a kill mid-capture leaves behind: Night Light off, marker set.
        val settings = FakeSettings(nightLight = 0)
        val pending = FakePending().apply { isPending = true }
        guard(settings, pending).recoverIfInterrupted()
        assertEquals(1, settings.nightLight)
        assertFalse(pending.isPending)
    }

    @Test
    fun `recovery does nothing without a marker`() = runTest {
        val settings = FakeSettings(nightLight = 0)
        guard(settings).recoverIfInterrupted()
        assertTrue(settings.writes.isEmpty())
    }

    @Test
    fun `a failed restore keeps the marker for the next start`() = runTest {
        val settings = FakeSettings(nightLight = 1)
        val pending = FakePending()
        guard(settings, pending).withTintSuspended { settings.writable = false }
        assertTrue(pending.isPending)
    }
}
