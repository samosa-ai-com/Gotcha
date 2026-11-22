package com.gotcha

import android.content.Context
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gotcha.ui.theme.GotchaTheme
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The consent gate: "I agree" needs both the accept and the age checkbox (#136 review). */
@RunWith(AndroidJUnit4::class)
class LegalConsentDialogTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var agreed = 0

    @Before
    fun setUp() {
        // No stored region, so the optional usage-data box starts off and the
        // toggle assertions below do not depend on where this device ran.
        ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("telemetry", Context.MODE_PRIVATE)
            .edit().clear().commit()
        composeRule.setContent {
            GotchaTheme { LegalConsentDialog(onAgree = { agreed++ }) }
        }
    }

    private fun tap(tag: String) {
        composeRule.onNodeWithTag(tag).performScrollTo().performClick()
    }

    private fun agreeButton() = composeRule.onNodeWithTag("legal_consent_agree_button")

    @Test
    fun agreeStaysOffUntilBothBoxesAreTicked() {
        agreeButton().assertIsNotEnabled()

        tap("legal_consent_toggle_row")
        agreeButton().assertIsNotEnabled()

        tap("legal_consent_toggle_row")
        tap("legal_consent_age_row")
        agreeButton().assertIsNotEnabled()

        tap("legal_consent_toggle_row")
        agreeButton().assertIsEnabled().performClick()
        composeRule.runOnIdle { assertEquals(1, agreed) }
    }

    @Test
    fun tappingARowTogglesItsOwnCheckbox() {
        composeRule.onNodeWithTag("legal_consent_toggle_row").assertIsOff()
        composeRule.onNodeWithTag("legal_consent_age_row").assertIsOff()
        composeRule.onNodeWithTag("legal_consent_usage_row").assertIsOff()

        tap("legal_consent_age_row")
        composeRule.onNodeWithTag("legal_consent_age_row").assertIsOn()
        composeRule.onNodeWithTag("legal_consent_toggle_row").assertIsOff()
        composeRule.onNodeWithTag("legal_consent_usage_row").assertIsOff()

        tap("legal_consent_age_row")
        composeRule.onNodeWithTag("legal_consent_age_row").assertIsOff()
    }

    /**
     * The usage-data box is a preference, not an acceptance. It must never be
     * able to enable the button on its own, and ticking it must not disable a
     * button the two required boxes already enabled.
     */
    @Test
    fun usageDataBoxNeverGatesTheButton() {
        tap("legal_consent_usage_row")
        agreeButton().assertIsNotEnabled()

        tap("legal_consent_toggle_row")
        tap("legal_consent_age_row")
        agreeButton().assertIsEnabled()

        tap("legal_consent_usage_row")
        composeRule.onNodeWithTag("legal_consent_usage_row").assertIsOff()
        agreeButton().assertIsEnabled().performClick()
        composeRule.runOnIdle { assertEquals(1, agreed) }
    }
}
