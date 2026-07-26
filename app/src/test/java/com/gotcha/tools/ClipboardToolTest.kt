package com.gotcha.tools

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.gotcha.service.GotchaAccessibilityService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Covers `get_clipboard` / `set_clipboard`, including the API 34 fallback path
 * (`ClipboardTool.getClipboard`) where the platform blocks clipboard reads by
 * non-IME apps and the tool falls back to the accessibility service's cache.
 *
 * `@Config(sdk = ...)` runs the same assertions on both sides of that branch in
 * seconds — far cheaper than an emulator matrix row, and it covers the same code.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30, 33, 34])
class ClipboardToolTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val tool = ClipboardTool(context)

    private val clipboard: ClipboardManager
        get() = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    @After
    fun clearAccessibilityCache() {
        GotchaAccessibilityService.lastClipboardData = null
    }

    @Test
    fun `set then get round-trips the text`() {
        assertTrue(tool.setClipboard("hello there").success)

        val result = tool.getClipboard()
        assertTrue(result.success)
        assertTrue("expected the text back, got: ${result.message}", result.message.contains("hello there"))
    }

    @Test
    fun `set writes through to the platform clipboard`() {
        tool.setClipboard("written by the tool")

        val clip = clipboard.primaryClip
        assertEquals(1, clip?.itemCount)
        assertEquals("written by the tool", clip?.getItemAt(0)?.coerceToText(context)?.toString())
    }

    @Test
    fun `long clipboard contents are truncated`() {
        tool.setClipboard("x".repeat(5000))

        val result = tool.getClipboard()
        assertTrue(result.success)
        // 2000 chars of payload plus the "Clipboard: " prefix.
        assertTrue("payload was not truncated: ${result.message.length}", result.message.length < 2100)
    }

    @Test
    fun `empty clipboard reports empty rather than failing`() {
        val result = tool.getClipboard()

        assertTrue(result.success)
        assertTrue(
            "expected an 'empty'/'restricted' explanation, got: ${result.message}",
            result.message.contains("empty", ignoreCase = true) ||
                result.message.contains("restricted", ignoreCase = true)
        )
    }

    @Test
    fun `falls back to the accessibility cache when the platform clipboard is empty`() {
        GotchaAccessibilityService.lastClipboardData = ClipData.newPlainText("Gotcha", "cached copy")

        val result = tool.getClipboard()

        assertTrue(result.success)
        assertTrue(
            "expected the cached value, got: ${result.message}",
            result.message.contains("cached copy")
        )
        assertTrue(
            "the fallback path should say where the text came from: ${result.message}",
            result.message.contains("accessibility", ignoreCase = true)
        )
    }

    @Test
    fun `the platform clipboard wins over a stale accessibility cache`() {
        GotchaAccessibilityService.lastClipboardData = ClipData.newPlainText("Gotcha", "stale")
        tool.setClipboard("fresh")

        val result = tool.getClipboard()

        assertTrue("expected the fresh value, got: ${result.message}", result.message.contains("fresh"))
    }
}
