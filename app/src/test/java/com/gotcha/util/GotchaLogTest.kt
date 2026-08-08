package com.gotcha.util

import android.util.Log
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * Debug-variant behaviour of [GotchaLog]: messages are evaluated and written to
 * logcat. The mirror-image release assertions (no output, lambda never invoked)
 * live in GotchaLogReleaseTest, which only runs under
 * `:app:testReleaseUnitTest` where `BuildConfig.DEBUG` is false.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GotchaLogTest {

    @Test
    fun `d evaluates the message and writes to Log`() {
        var evaluated = false
        GotchaLog.d("GotchaLogTest") {
            evaluated = true
            "debug message"
        }
        assertTrue(evaluated)
        val entry = ShadowLog.getLogs().last { it.tag == "GotchaLogTest" }
        assertEquals(Log.DEBUG, entry.type)
        assertEquals("debug message", entry.msg)
    }

    @Test
    fun `d with throwable passes both to Log`() {
        val cause = RuntimeException("boom")
        GotchaLog.d("GotchaLogTest", cause) { "with throwable" }
        val entry = ShadowLog.getLogs().last { it.tag == "GotchaLogTest" }
        assertEquals("with throwable", entry.msg)
        assertSame(cause, entry.throwable)
    }

    @Test
    fun `v evaluates the message and writes to Log`() {
        GotchaLog.v("GotchaLogTest") { "verbose message" }
        val entry = ShadowLog.getLogs().last { it.tag == "GotchaLogTest" }
        assertEquals(Log.VERBOSE, entry.type)
        assertEquals("verbose message", entry.msg)
    }
}
