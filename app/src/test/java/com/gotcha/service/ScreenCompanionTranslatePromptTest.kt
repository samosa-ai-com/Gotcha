package com.gotcha.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the [ScreenCompanionController.translateScreenshotPrompt] wording and
 * the default language. The string is sent to the LLM verbatim, so any drift
 * here is a behavior change worth catching in a unit test.
 */
class ScreenCompanionTranslatePromptTest {

    @Test
    fun `default language is English`() {
        val prompt = ScreenCompanionController.translateScreenshotPrompt()
        assertTrue(
            "default prompt should mention English — was: $prompt",
            prompt.contains("English")
        )
    }

    @Test
    fun `explicit target language is interpolated`() {
        val prompt = ScreenCompanionController.translateScreenshotPrompt(targetLang = "Spanish")
        assertTrue(
            "prompt should mention the target language — was: $prompt",
            prompt.contains("Spanish")
        )
        assertTrue(
            "prompt should be translation-shaped",
            prompt.contains("translate")
        )
    }

    @Test
    fun `prompt mentions side-by-side markdown table`() {
        val prompt = ScreenCompanionController.translateScreenshotPrompt(targetLang = "French")
        assertTrue(
            "prompt should request a markdown table — was: $prompt",
            prompt.contains("markdown table")
        )
    }

    @Test
    fun `different languages produce different prompts`() {
        val english = ScreenCompanionController.translateScreenshotPrompt(targetLang = "English")
        val french = ScreenCompanionController.translateScreenshotPrompt(targetLang = "French")
        assertTrue(
            "different target languages should change the prompt content",
            english != french
        )
    }

    @Test
    fun `default without arg matches explicit English`() {
        val byDefault = ScreenCompanionController.translateScreenshotPrompt()
        val byArg = ScreenCompanionController.translateScreenshotPrompt(targetLang = "English")
        assertEquals(byArg, byDefault)
    }
}
