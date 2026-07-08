package com.gotcha.tools

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CallLog
import androidx.core.content.ContextCompat

class PhoneTool(private val context: Context) {

    private val numberPattern = Regex("^[+]?[0-9()\\-\\s#*]{2,20}$")

    fun dialNumber(number: String): ToolResult {
        val trimmed = number.trim()
        if (trimmed.isEmpty() || !trimmed.matches(numberPattern)) {
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

    /** Place a call directly (needs CALL_PHONE). Gated by the sensitive-action confirmation dialog. */
    fun callNumber(number: String): ToolResult {
        val trimmed = number.trim()
        if (trimmed.isEmpty() || !trimmed.matches(numberPattern)) {
            return ToolResult.error("'$number' does not look like a valid phone number.")
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return ToolResult.permissionNeeded(
                Manifest.permission.CALL_PHONE,
                "Placing a call directly needs the Phone permission. I have requested it — " +
                    "please grant it and ask again."
            )
        }
        return try {
            val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$trimmed")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            ToolResult.ok("Calling $trimmed.")
        } catch (e: SecurityException) {
            ToolResult.permissionNeeded(
                Manifest.permission.CALL_PHONE,
                "Placing a call needs the Phone permission. I have requested it — please grant it and ask again."
            )
        } catch (e: Exception) {
            ToolResult.error("Could not place the call: ${e.message}")
        }
    }

    /** Read recent call-log entries (needs READ_CALL_LOG). */
    fun readCallLog(limit: Int): ToolResult {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return ToolResult.permissionNeeded(
                Manifest.permission.READ_CALL_LOG,
                "Reading the call log needs the Call log permission. I have requested it — please grant it and ask again."
            )
        }
        val take = limit.coerceIn(1, 50)
        return try {
            val projection = arrayOf(
                CallLog.Calls.NUMBER,
                CallLog.Calls.CACHED_NAME,
                CallLog.Calls.TYPE,
                CallLog.Calls.DATE,
                CallLog.Calls.DURATION
            )
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI, projection, null, null,
                "${CallLog.Calls.DATE} DESC"
            ).use { cursor ->
                if (cursor == null) return ToolResult.error("Could not read the call log.")
                val numberIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
                val nameIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME)
                val typeIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.TYPE)
                val dateIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.DATE)
                val durIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.DURATION)
                val entries = StringBuilder()
                var count = 0
                while (cursor.moveToNext() && count < take) {
                    val number = cursor.getString(numberIdx) ?: "unknown"
                    val name = cursor.getString(nameIdx)
                    val type = when (cursor.getInt(typeIdx)) {
                        CallLog.Calls.INCOMING_TYPE -> "incoming"
                        CallLog.Calls.OUTGOING_TYPE -> "outgoing"
                        CallLog.Calls.MISSED_TYPE -> "missed"
                        CallLog.Calls.REJECTED_TYPE -> "rejected"
                        else -> "other"
                    }
                    val date = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                        .format(java.util.Date(cursor.getLong(dateIdx)))
                    val dur = cursor.getLong(durIdx)
                    val who = if (!name.isNullOrBlank()) "$name ($number)" else number
                    entries.append("- $date  $type  $who  ${dur}s\n")
                    count++
                }
                if (count == 0) ToolResult.ok("The call log is empty.")
                else ToolResult.ok("Last $count call(s):\n$entries")
            }
        } catch (e: Exception) {
            ToolResult.error("Could not read the call log: ${e.message}")
        }
    }
}
