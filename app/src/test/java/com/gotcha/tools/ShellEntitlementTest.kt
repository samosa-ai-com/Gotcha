package com.gotcha.tools

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.gotcha.data.SettingsRepository
import com.gotcha.testsupport.FakeAndroidKeyStore
import com.gotcha.tools.ShellEntitlement.Access
import com.gotcha.tools.ShellEntitlement.TierAnswer
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests run the debug build, where [ShellEntitlement.access] always allows, so these read
 * the remembered answer through [ShellEntitlement.remembered].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShellEntitlementTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private var now = 1_000_000_000_000L
    private val hour = 60L * 60 * 1000

    private class Source(var answer: TierAnswer) : ShellEntitlement.TierSource {
        var calls = 0
        override suspend fun fetch(): TierAnswer {
            calls++
            return answer
        }
    }

    private suspend fun refresh(source: Source, force: Boolean = false) {
        ShellEntitlement.refresh(context, force, source) { now }
    }

    private fun remembered() = ShellEntitlement.remembered(context, now)

    @Before
    fun setUp() {
        ShellEntitlement.onSignedIn(context)
    }

    @Test
    fun `nothing remembered means the backend has to be asked`() {
        assertEquals(Access.NEEDS_CHECK, remembered())
        assertNull(ShellEntitlement.allowedUntil(context, now))
    }

    @Test
    fun `Pro and Influencer accounts are allowed and other tiers are not`() = runTest {
        for (tier in listOf("pro", "influencer")) {
            refresh(Source(TierAnswer.Tier(tier)), force = true)
            assertEquals(tier, Access.ALLOWED, remembered())
        }

        for (tier in listOf("free", "premium")) {
            refresh(Source(TierAnswer.Tier(tier)), force = true)
            assertEquals(tier, Access.NOT_PRO, remembered())
        }
    }

    @Test
    fun `the backend is asked once a day, not on every call`() = runTest {
        val source = Source(TierAnswer.Tier("pro"))
        refresh(source)
        now += 23 * hour
        refresh(source)
        assertEquals(1, source.calls)

        now += 2 * hour
        refresh(source)
        assertEquals(2, source.calls)
    }

    @Test
    fun `offline the shell keeps working for 72 hours after the last Pro answer`() = runTest {
        refresh(Source(TierAnswer.Tier("pro")))
        val offline = Source(TierAnswer.Unavailable)

        now += 71 * hour
        refresh(offline)
        assertEquals(Access.ALLOWED, remembered())

        now += 2 * hour
        refresh(offline)
        assertEquals(Access.NEEDS_CHECK, remembered())
    }

    @Test
    fun `a daily check that succeeds restarts the offline window`() = runTest {
        val source = Source(TierAnswer.Tier("pro"))
        refresh(source)
        now += 48 * hour
        refresh(source)
        assertEquals(now + ShellEntitlement.OFFLINE_WINDOW_MILLIS, ShellEntitlement.allowedUntil(context, now))
    }

    @Test
    fun `a plan that ended turns the shell off at the next check, not after the offline window`() = runTest {
        val source = Source(TierAnswer.Tier("pro"))
        refresh(source)
        now += 25 * hour
        source.answer = TierAnswer.Tier("free")
        refresh(source)
        assertEquals(Access.NOT_PRO, remembered())
    }

    @Test
    fun `setting the clock back does not extend the offline window`() = runTest {
        refresh(Source(TierAnswer.Tier("pro")))
        now -= 2 * hour
        assertEquals(Access.NEEDS_CHECK, remembered())
    }

    @Test
    fun `a dead session counts as signed out`() = runTest {
        refresh(Source(TierAnswer.Tier("pro")))
        refresh(Source(TierAnswer.SignedOut), force = true)
        assertEquals(Access.SIGNED_OUT, remembered())
    }

    @Test
    fun `signing out turns the shell off at once and signing in asks again`() {
        FakeAndroidKeyStore.setUp()
        val settings = SettingsRepository(context)
        kotlinx.coroutines.runBlocking { refresh(Source(TierAnswer.Tier("pro"))) }
        assertEquals(Access.ALLOWED, remembered())

        settings.clearSamosaSession()
        assertEquals(Access.SIGNED_OUT, remembered())

        settings.saveSamosaSession("token", "user@example.com")
        assertEquals(Access.NEEDS_CHECK, remembered())
    }
}
