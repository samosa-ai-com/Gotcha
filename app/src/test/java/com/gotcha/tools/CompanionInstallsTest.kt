package com.gotcha.tools

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CompanionInstallsTest {

    @After
    fun tearDown() = CompanionInstalls.clear()

    @Test
    fun `success is observable and consumed once`() {
        CompanionInstalls.publish(CompanionInstalls.Outcome.SUCCESS)
        val notice = checkNotNull(CompanionInstalls.notices.value)

        assertEquals(CompanionInstalls.Outcome.SUCCESS, CompanionInstalls.consume(notice)?.outcome)
        assertNull(CompanionInstalls.notices.value)
        assertNull(CompanionInstalls.consume(notice))
    }

    @Test
    fun `an older refresh cannot consume a newer failure`() {
        CompanionInstalls.publish(CompanionInstalls.Outcome.SUCCESS)
        val old = checkNotNull(CompanionInstalls.notices.value)
        CompanionInstalls.publish(CompanionInstalls.Outcome.FAILED, "Install cancelled")

        assertNull(CompanionInstalls.consume(old))
        val current = checkNotNull(CompanionInstalls.notices.value)
        assertEquals("Install cancelled", CompanionInstalls.consume(current)?.reason)
    }
}
