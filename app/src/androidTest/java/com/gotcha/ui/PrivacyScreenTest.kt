package com.gotcha.ui

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gotcha.ui.theme.GotchaTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PrivacyScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun privacyScreenHasNoDeleteAction() {
        composeRule.setContent {
            GotchaTheme { PrivacyScreen(onBack = {}, onOpenPage = {}) }
        }
        composeRule.onNodeWithTag("settings_back").assertExists()
        composeRule.onNodeWithTag("privacy_open_policy").assertExists()
        // The only buttons are Back and the policy link. A delete action
        // reintroduced under any name adds a button and fails this assertion.
        composeRule.onAllNodes(
            SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)
        ).assertCountEquals(2)
    }
}
