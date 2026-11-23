package com.gotcha.upsell

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.gotcha.data.Settings
import com.gotcha.testsupport.FakeAndroidKeyStore
import com.gotcha.tools.AgentMode
import com.gotcha.tools.ShellEntitlement
import com.gotcha.tools.ShellTools
import com.gotcha.tools.ToolExecutor
import com.gotcha.tools.ToolRegistry
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** `offer_linux_shell`: who is offered it, and what it hands back to the engine. */
@RunWith(RobolectricTestRunner::class)
// A plain Application: GotchaApp.onCreate starts its own plan check, which would race the plan
// these tests store. See ShellEntitlementTest.
@Config(sdk = [34], application = android.app.Application::class)
class LinuxShellOfferTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        FakeAndroidKeyStore.setUp()
    }

    private suspend fun rememberTier(id: String) {
        ShellEntitlement.refresh(context, force = true, source = { ShellEntitlement.TierAnswer.Tier(id) })
    }

    @Test
    fun `the offer asks the host for the shell nudge and works in Monitor`() = runTest {
        val result = ToolExecutor(context).execute(ShellTools.OFFER, JsonObject(emptyMap()), agent = AgentMode.MONITOR)

        assertTrue(result.message, result.success)
        assertEquals(UpsellReason.SHELL, result.upsell)
        assertTrue(ShellTools.OFFER in ToolRegistry.monitorTools)
    }

    @Test
    fun `offered with no shell to signed out and Free accounts, not to Pro ones`() = runTest {
        // Robolectric has neither Termux nor Gotcha Shell installed.
        assertTrue(Upsell.offersLinuxShell(context, Settings(samosaSessionToken = "")))

        val signedIn = Settings(samosaSessionToken = "token")
        rememberTier("free")
        assertTrue(Upsell.offersLinuxShell(context, signedIn))
        rememberTier("pro")
        assertFalse(Upsell.offersLinuxShell(context, signedIn))
    }
}
