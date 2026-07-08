package com.gotcha.tools

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

class ClipboardTool(private val context: Context) {

    private val clipboard: ClipboardManager
        get() = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    /** Read the current clipboard text (works while the app is foreground). */
    fun getClipboard(): ToolResult {
        return try {
            val clip = clipboard.primaryClip
            if (clip == null || clip.itemCount == 0) {
                return ToolResult.ok("The clipboard is empty.")
            }
            val text = clip.getItemAt(0).coerceToText(context).toString()
            if (text.isEmpty()) ToolResult.ok("The clipboard holds no text.")
            else ToolResult.ok("Clipboard: ${text.take(2000)}")
        } catch (e: Exception) {
            ToolResult.error("Could not read the clipboard: ${e.message}")
        }
    }

    /** Put text on the clipboard. */
    fun setClipboard(text: String): ToolResult {
        return try {
            clipboard.setPrimaryClip(ClipData.newPlainText("Gotcha", text))
            ToolResult.ok("Copied to the clipboard.")
        } catch (e: Exception) {
            ToolResult.error("Could not set the clipboard: ${e.message}")
        }
    }
}
