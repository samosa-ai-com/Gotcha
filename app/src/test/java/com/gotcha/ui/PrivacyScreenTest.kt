package com.gotcha.ui

import com.gotcha.R
import com.gotcha.telemetry.ConsentSurface
import com.gotcha.telemetry.Telemetry
import com.gotcha.telemetry.TelemetryStatus
import com.gotcha.testsupport.RepoPaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Privacy page's usage-data switch, separated from Compose so the states
 * and the write path can be pinned down in a unit test. The delete flow is
 * gone; this also guards against it coming back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PrivacyScreenTest {

    private fun status(
        configured: Boolean = true,
        enabled: Boolean = true,
        mobileData: Boolean = false,
        paused: Boolean = false,
        lastSentAt: Long = 0L,
        queued: Int = 0
    ) = TelemetryStatus(configured, enabled, mobileData, paused, lastSentAt, queued)

    @Test
    fun `status line distinguishes every state`() {
        assertEquals(UsageDataState.NOT_CONFIGURED, usageDataState(null))
        assertEquals(UsageDataState.NOT_CONFIGURED, usageDataState(status(configured = false)))
        assertEquals(UsageDataState.OFF, usageDataState(status(enabled = false)))
        assertEquals(UsageDataState.PAUSED, usageDataState(status(paused = true)))
        assertEquals(UsageDataState.WAITING, usageDataState(status()))
        assertEquals(UsageDataState.SENT, usageDataState(status(lastSentAt = 1_700_000_000_000L)))
    }

    @Test
    fun `the regional default reads as on before the first decision`() {
        // A build whose region is `other` with no consent required already
        // reports enabled; nothing has been sent yet, so the line says so.
        assertEquals(UsageDataState.WAITING, usageDataState(status(enabled = true, lastSentAt = 0L)))
    }

    @Test
    fun `a later choice only changes the stored value`() {
        val enabled = mutableListOf<Boolean>()
        var consentSurface: ConsentSurface? = null

        applyUsageDataChoice(
            on = false,
            hasConsent = true,
            setEnabled = { enabled += it },
            recordConsent = { _, surface -> consentSurface = surface }
        )

        assertEquals(listOf(false), enabled)
        assertEquals(null, consentSurface)
    }

    @Test
    fun `a first choice made in settings records a settings consent`() {
        var enabled: Boolean? = null
        var recorded: Pair<Boolean, ConsentSurface>? = null

        applyUsageDataChoice(
            on = true,
            hasConsent = false,
            setEnabled = { enabled = it },
            recordConsent = { on, surface -> recorded = on to surface }
        )

        assertEquals(null, enabled)
        assertEquals(true to ConsentSurface.SETTINGS, recorded)
    }

    @Test
    fun `the delete flow is gone`() {
        val source = RepoPaths.mainSource("com/gotcha/ui/PrivacyScreen.kt")
        assertFalse(source.contains("deleteMyData"))
        assertFalse(source.contains("privacy_delete"))
        assertFalse(source.contains("hasInstallId"))
        assertFalse(Telemetry::class.java.declaredMethods.any { it.name == "deleteMyData" })

        val stringFields = R.string::class.java.fields.map { it.name }
        assertFalse(stringFields.any { it.startsWith("privacy_delete_") })
        assertFalse(stringFields.any { it.startsWith("privacy_enable_dialog_") })
        assertTrue(stringFields.contains("privacy_usage_data_status_not_configured"))
    }
}
