package com.gotcha.terminal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.gotcha.tools.CompanionTerminal.Refusal
import com.gotcha.tools.CompanionUnavailable
import com.gotcha.tools.ShellEntitlement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Every way the terminal can refuse to start has a sentence for the user, with its numbers filled in. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TerminalMessagesTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun text(refusal: Refusal) = refusalMessage(context, refusal)

    @Test
    fun `every refusal says something and leaves no placeholder behind`() {
        val all = listOf(
            Refusal.NotAllowed(ShellEntitlement.Access.SIGNED_OUT),
            Refusal.NotAllowed(ShellEntitlement.Access.NOT_PRO),
            Refusal.NotAllowed(ShellEntitlement.Access.NEEDS_CHECK),
            Refusal.Unavailable(CompanionUnavailable.Reason.NOT_INSTALLED),
            Refusal.Unavailable(CompanionUnavailable.Reason.UNTRUSTED),
            Refusal.Unavailable(CompanionUnavailable.Reason.BIND_REFUSED),
            Refusal.Unavailable(CompanionUnavailable.Reason.BIND_TIMED_OUT),
            Refusal.TooOld(2, 3),
            Refusal.NoLinuxSystem(installing = false),
            Refusal.NoLinuxSystem(installing = true),
            Refusal.TooManySessions(4),
            Refusal.TooManySessions(0),
            Refusal.Failed("denied"),
            Refusal.Failed(null)
        )
        all.forEach { refusal ->
            val message = text(refusal)
            assertTrue("$refusal gave '$message'", message.isNotBlank() && "%" !in message && "$" !in message)
        }
    }

    @Test
    fun `the numbers and the detail are in the message`() {
        assertTrue("2" in text(Refusal.TooOld(2, 3)) && "3" in text(Refusal.TooOld(2, 3)))
        assertTrue("4" in text(Refusal.TooManySessions(4)))
        assertTrue("denied" in text(Refusal.Failed("denied")))
    }

    @Test
    fun `the plan messages differ by reason`() {
        val signedOut = text(Refusal.NotAllowed(ShellEntitlement.Access.SIGNED_OUT))
        val notPro = text(Refusal.NotAllowed(ShellEntitlement.Access.NOT_PRO))
        val needsCheck = text(Refusal.NotAllowed(ShellEntitlement.Access.NEEDS_CHECK))
        assertEquals(3, setOf(signedOut, notPro, needsCheck).size)
    }
}
