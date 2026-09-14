package com.gotcha.tools

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.gotcha.ui.SettingsPage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `about_gotcha`.
 *
 * The asset shipping at all is the first thing to check, as with
 * [CompanyInfoToolTest]. The second is drift, which is the risk specific to this
 * document: it names settings pages and capabilities that the code can rename
 * underneath it, and a stale settings path is worse than none — the agent states
 * it with confidence and sends the user somewhere that no longer exists. So the
 * assertions below are made against the enums themselves rather than against
 * copies of their strings.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30, 34])
class AppInfoToolTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val tool = AppInfoTool(context)

    @Test
    fun `about_gotcha reads the bundled asset`() {
        val result = tool.aboutGotcha()

        assertTrue(result.message, result.success)
        assertTrue("expected substantial content, got ${result.message.length} chars", result.message.length > 500)
    }

    @Test
    fun `every settings page the app can open is documented`() {
        val text = tool.aboutGotcha().message

        SettingsPage.entries.forEach { page ->
            assertTrue(
                "the handbook does not mention the '${page.title}' settings page, so the agent " +
                    "cannot tell the user how to reach it",
                text.contains(page.title)
            )
        }
    }

    @Test
    fun `every gated capability is documented with the tools it unlocks`() {
        val text = tool.aboutGotcha().message

        // The label reads as a sentence fragment ("it needs <label>"), so match on its
        // distinguishing noun rather than the whole phrase.
        val subjects = mapOf(
            Capability.ACCESSIBILITY to "accessibility service",
            Capability.NOTIFICATION_LISTENER to "Notification access",
            Capability.DEVICE_ADMIN to "Device admin",
            Capability.ROOT to "Root",
            Capability.TERMUX to "Termux",
            Capability.HEALTH_CONNECT to "Health Connect",
            Capability.OVERLAY to "Display over other apps"
        )
        assertTrue(
            "a Capability was added or removed without updating this test or the handbook",
            subjects.keys == Capability.entries.toSet()
        )
        subjects.forEach { (capability, subject) ->
            assertTrue(
                "the handbook does not document '$subject', which gates ${capability.tools.size} tool(s)",
                text.contains(subject)
            )
        }
    }

    @Test
    fun `both copilot modes are explained`() {
        val text = tool.aboutGotcha().message

        assertTrue(text.contains("Monitor"))
        assertTrue(text.contains("Operator"))
    }

    /**
     * The two info tools must stay in their own lanes: this one exists so the
     * agent stops guessing at app behaviour, not so it can paraphrase the legal
     * documents that [CompanyInfoTool] already refuses to stand in for.
     */
    @Test
    fun `company and legal questions are handed to the other tool`() {
        val text = tool.aboutGotcha().message

        assertTrue(text.contains("about_samosa_ai"))
        assertFalse("the handbook must not restate the agreements", text.contains("BY INSTALLING"))
    }
}
