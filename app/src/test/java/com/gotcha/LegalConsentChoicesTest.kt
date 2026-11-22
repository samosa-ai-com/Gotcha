package com.gotcha

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The first screen's three answers. The terms and age boxes are the gate; the
 * usage-data box is a preference and must never be able to enable or disable
 * the button on its own.
 *
 * Usage data is on by default in every region. The region only decides whether
 * the row appears on this screen, never whether it starts on.
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
    fun `usage data starts on`() {
        assertTrue(LegalConsentChoices().usageData)
    }

    @Test
    fun `the row is shown only where the region asks for it`() {
        assertFalse(usageRowVisible("other"))
        assertTrue(usageRowVisible("eea"))
        assertTrue(usageRowVisible("uk"))
        // Not classified yet: show the control rather than hide it.
        assertTrue(usageRowVisible(null))
    }

    @Test
    fun `the user can turn it off`() {
        assertFalse(LegalConsentChoices().withUsageDataChoice(false).usageData)
        assertTrue(LegalConsentChoices().withUsageDataChoice(false).withUsageDataChoice(true).usageData)
    }
}
