package com.gotcha

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The first screen's three choices. The terms and age boxes are the gate; the
 * usage-data box is a preference and must never be able to enable or disable
 * the button on its own.
 */
class LegalConsentChoicesTest {

    @Test
    fun `usage data box never gates the button`() {
        assertFalse(LegalConsentChoices(usageData = true).canContinue)
        assertFalse(LegalConsentChoices(termsAccepted = true, usageData = true).canContinue)
        assertFalse(LegalConsentChoices(ageConfirmed = true, usageData = true).canContinue)
        assertTrue(
            LegalConsentChoices(termsAccepted = true, ageConfirmed = true, usageData = false).canContinue
        )
    }

    @Test
    fun `the required boxes still gate on their own`() {
        assertFalse(LegalConsentChoices(termsAccepted = true).canContinue)
        assertFalse(LegalConsentChoices(ageConfirmed = true).canContinue)
        assertFalse(LegalConsentChoices().canContinue)
    }

    @Test
    fun `the usage box starts on only outside the consent regions`() {
        assertTrue(initialUsageDataChecked("other", defaultUsageData = true))
        assertFalse(initialUsageDataChecked("eea", defaultUsageData = false))
        assertFalse(initialUsageDataChecked("uk", defaultUsageData = false))
        assertFalse(initialUsageDataChecked(null, defaultUsageData = false))
        // Fail closed even if a stale caller hands over an "on" default.
        assertFalse(initialUsageDataChecked(null, defaultUsageData = true))
    }

    @Test
    fun `submit forces the box off when the region is still unknown`() {
        assertFalse(usageDataOnSubmit(checked = true, region = null))
        assertTrue(usageDataOnSubmit(checked = true, region = "other"))
        assertTrue(usageDataOnSubmit(checked = true, region = "eea"))
        assertFalse(usageDataOnSubmit(checked = false, region = "other"))
    }
}
