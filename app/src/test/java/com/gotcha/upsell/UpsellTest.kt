package com.gotcha.upsell

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.gotcha.data.LlmProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Who sees which nudge, and when the interrupting one is due. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpsellTest {

    private val day = 24L * 60L * 60L * 1000L
    private val installedAt = 1_000_000_000_000L

    private fun due(
        daysSinceInstall: Double,
        daysSinceShown: Double? = null,
        dismissals: Int = 0,
        runs: Int = Upsell.MIN_SUCCESSFUL_RUNS,
        daysSinceStarPrompt: Double? = null,
        audience: UpsellAudience = UpsellAudience.PRO
    ): Boolean {
        val now = installedAt + (daysSinceInstall * day).toLong()
        val at = Upsell.dialogDueAt(
            audience = audience,
            installedAt = installedAt,
            lastShownAt = daysSinceShown?.let { now - (it * day).toLong() } ?: 0L,
            dismissals = dismissals,
            successfulRuns = runs,
            starPromptShownAt = daysSinceStarPrompt?.let { now - (it * day).toLong() } ?: 0L
        )
        return at != null && now >= at
    }

    @Test
    fun `signed out users hear about Samosa AI, Free users about Pro, Pro users about nothing`() {
        assertEquals(UpsellAudience.DISCOVER_SAMOSA, Upsell.audience(signedIn = false, tierId = null))
        assertEquals(UpsellAudience.DISCOVER_SAMOSA, Upsell.audience(signedIn = false, tierId = "pro"))
        assertEquals(UpsellAudience.PRO, Upsell.audience(signedIn = true, tierId = "free"))
        assertEquals(UpsellAudience.PRO, Upsell.audience(signedIn = true, tierId = "premium"))
        assertEquals(UpsellAudience.NONE, Upsell.audience(signedIn = true, tierId = "pro"))
        assertEquals(UpsellAudience.NONE, Upsell.audience(signedIn = true, tierId = "Influencer"))
    }

    @Test
    fun `a signed in account whose plan is not known yet is not nudged`() {
        assertEquals(UpsellAudience.NONE, Upsell.audience(signedIn = true, tierId = null))
    }

    @Test
    fun `a limit is answered only by what would lift it`() {
        assertEquals(UpsellAudience.PRO, Upsell.limitsAudience(UpsellAudience.PRO, LlmProvider.SAMOSA_AI))
        // Pro does not lift another provider's limits.
        assertEquals(UpsellAudience.NONE, Upsell.limitsAudience(UpsellAudience.PRO, LlmProvider.OPENAI_COMPATIBLE))
        assertEquals(
            UpsellAudience.DISCOVER_SAMOSA,
            Upsell.limitsAudience(UpsellAudience.DISCOVER_SAMOSA, LlmProvider.OPENAI_COMPATIBLE)
        )
        assertEquals(UpsellAudience.NONE, Upsell.limitsAudience(UpsellAudience.NONE, LlmProvider.SAMOSA_AI))
    }

    @Test
    fun `the dialog waits for three days and five good tasks`() {
        assertFalse(due(daysSinceInstall = 2.9))
        assertTrue(due(daysSinceInstall = 3.0))
        assertFalse(due(daysSinceInstall = 30.0, runs = Upsell.MIN_SUCCESSFUL_RUNS - 1))
    }

    @Test
    fun `the dialog comes at most once a week`() {
        assertFalse(due(daysSinceInstall = 30.0, daysSinceShown = 6.9))
        assertTrue(due(daysSinceInstall = 30.0, daysSinceShown = 7.0))
    }

    @Test
    fun `the dialog keeps away from the star prompt`() {
        assertFalse(due(daysSinceInstall = 30.0, daysSinceStarPrompt = 1.0))
        assertTrue(due(daysSinceInstall = 30.0, daysSinceStarPrompt = 2.0))
    }

    @Test
    fun `three dismissals end the dialog, and no audience means no dialog`() {
        assertTrue(due(daysSinceInstall = 30.0, dismissals = Upsell.MAX_DISMISSALS - 1))
        assertFalse(due(daysSinceInstall = 30.0, dismissals = Upsell.MAX_DISMISSALS))
        assertFalse(due(daysSinceInstall = 30.0, audience = UpsellAudience.NONE))
    }

    @Test
    fun `dismissals are counted per audience and runs stop counting once enough`() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val store = UpsellStore(context.getSharedPreferences("upsell_test", Context.MODE_PRIVATE))
        store.markDismissed(UpsellAudience.DISCOVER_SAMOSA)
        store.markDismissed(UpsellAudience.DISCOVER_SAMOSA)
        assertEquals(2, store.dismissals(UpsellAudience.DISCOVER_SAMOSA))
        assertEquals(0, store.dismissals(UpsellAudience.PRO))
        repeat(Upsell.MIN_SUCCESSFUL_RUNS + 3) { store.recordSuccessfulRun() }
        assertEquals(Upsell.MIN_SUCCESSFUL_RUNS, store.successfulRuns())
    }
}
