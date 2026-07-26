package com.gotcha.tools

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import com.gotcha.service.GotchaAccessibilityService

class ClipboardTool(private val context: Context) {

    private val clipboard: ClipboardManager
        get() = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    /** Read the current clipboard text. */
    fun getClipboard(): ToolResult {
        return try {
            // On API 34+ the platform blocks clipboard reads by non-IME apps.
            // Try the direct API first, then fall back to the accessibility service cache.
            val directClip = if (Build.VERSION.SDK_INT < 34) {
                clipboard.primaryClip
            } else {
                try { clipboard.primaryClip } catch (_: SecurityException) { null }
            }

            if (directClip != null && directClip.itemCount > 0) {
                val text = directClip.getItemAt(0).coerceToText(context).toString()
                if (text.isNotEmpty()) return ToolResult.ok("Clipboard: ${text.take(2000)}")
            }

            // Fall back to accessibility clipboard cache
            val a11yClip = GotchaAccessibilityService.lastClipboardData
            if (a11yClip != null && a11yClip.itemCount > 0) {
                val text = a11yClip.getItemAt(0).coerceToText(context).toString()
                if (text.isNotEmpty()) {
                    return ToolResult.ok(
                        "Clipboard (from accessibility service): ${text.take(2000)}"
                    )
                }
            }

            if (Build.VERSION.SDK_INT >= 34) {
                val hasA11y = GotchaAccessibilityService.instance != null
                if (hasA11y) {
                    ToolResult.ok("The clipboard is empty. Ask the user to copy something first.")
                } else {
                    ToolResult.ok(
                        "Clipboard reading is restricted on Android 14+. Enable the Gotcha " +
                            "accessibility service for clipboard access, or ask the user to copy " +
                            "the text again while Gotcha is open."
                    )
                }
            } else {
                ToolResult.ok("The clipboard is empty.")
            }
        } catch (e: Exception) {
            ToolResult.error("Could not read the clipboard: ${e.message}")
        }
    }

    /** Put text on the clipboard. */
    fun setClipboard(text: String): ToolResult {
        return try {
            clipboard.setPrimaryClip(ClipData.newPlainText("Gotcha", text))
            // Also update the accessibility cache so subsequent reads work
            if (GotchaAccessibilityService.instance != null) {
                GotchaAccessibilityService.lastClipboardData =
                    ClipData.newPlainText("Gotcha", text)
            }
            ToolResult.ok("Copied to the clipboard.")
        } catch (e: Exception) {
            ToolResult.error("Could not set the clipboard: ${e.message}")
        }
    }
}
