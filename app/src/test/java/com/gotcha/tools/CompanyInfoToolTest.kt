package com.gotcha.tools

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `about_samosa_ai`.
 *
 * The value of this tool is entirely in the bundled asset actually shipping and
 * actually containing the facts the agent is told it can answer from — a missing
 * or gutted asset would otherwise only surface as the agent confidently making
 * things up at runtime.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30, 34])
class CompanyInfoToolTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val tool = CompanyInfoTool(context)

    @Test
    fun `about_samosa_ai reads the bundled asset`() {
        val result = tool.aboutSamosaAi()

        assertTrue(result.message, result.success)
        assertTrue("expected substantial content, got ${result.message.length} chars", result.message.length > 500)
    }

    @Test
    fun `the bundled asset answers the questions the tool description promises`() {
        val text = tool.aboutSamosaAi().message

        listOf(
            "Samosa AI",
            "Chanakya",
            "Personal Guru",
            "Voice Typing",
            "samosa-ai.com",
            "samosa.ai.com@gmail.com",
            "github.com/Rishabh-Bajpai"
        ).forEach {
            assertTrue("bundled about copy no longer mentions '$it'", text.contains(it))
        }
    }

    /**
     * The summary in the asset must send the user to the real documents rather
     * than standing in for them — the agent paraphrasing terms it invented is
     * exactly the failure the Disclaimer warns about.
     */
    @Test
    fun `the legal summary points at the in-app documents`() {
        val text = tool.aboutSamosaAi().message

        assertTrue(text.contains("Legal"))
        assertFalse("the summary must not claim to be the agreement itself", text.contains("BY INSTALLING"))
    }
}
