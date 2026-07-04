package com.gotcha.tools

import android.content.Context
import android.content.Intent
import android.net.Uri

class PhoneTool(private val context: Context) {

    fun dialNumber(number: String): ToolResult {
        val trimmed = number.trim()
        if (trimmed.isEmpty() || !trimmed.matches(Regex("^[+]?[0-9()\\-\\s#*]{2,20}$"))) {
            return ToolResult.error("'$number' does not look like a valid phone number.")
        }
        return try {
            val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$trimmed")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            ToolResult.ok("Opened the dialer with $trimmed. The user must press call themselves.")
        } catch (e: Exception) {
            ToolResult.error("Could not open the dialer: ${e.message}")
        }
    }
}
