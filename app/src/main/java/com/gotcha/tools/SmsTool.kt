package com.gotcha.tools

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Telephony
import android.telephony.SmsManager
import androidx.core.content.ContextCompat

class SmsTool(private val context: Context) {

    private val numberPattern = Regex("^[+]?[0-9()\\-\\s#*]{2,20}$")

    /** Send a text message directly (needs SEND_SMS). Gated by the sensitive-action confirmation dialog. */
    fun sendSms(number: String, message: String): ToolResult {
        val trimmed = number.trim()
        if (!trimmed.matches(numberPattern)) {
            return ToolResult.error("'$number' does not look like a valid phone number.")
        }
        if (message.isBlank()) return ToolResult.error("The message text is empty.")
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return ToolResult.permissionNeeded(
                Manifest.permission.SEND_SMS,
                "The SMS permission is not granted. Go to Settings → Permissions → SMS and enable it, then ask again."
            )
        }
        return try {
            val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
            val parts = smsManager.divideMessage(message)
            if (parts.size > 1) {
                smsManager.sendMultipartTextMessage(trimmed, null, parts, null, null)
            } else {
                smsManager.sendTextMessage(trimmed, null, message, null, null)
            }
            ToolResult.ok("Sent a text to $trimmed.")
        } catch (e: SecurityException) {
            ToolResult.permissionNeeded(
                Manifest.permission.SEND_SMS,
                "The SMS permission is not granted. Go to Settings → Permissions → SMS and enable it, then ask again."
            )
        } catch (e: Exception) {
            ToolResult.error("Could not send the text: ${e.message}")
        }
    }

    /** Read recent inbox messages (needs READ_SMS). */
    fun readRecentSms(limit: Int): ToolResult {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return ToolResult.permissionNeeded(
                Manifest.permission.READ_SMS,
                "The Read SMS permission is not granted. Go to Settings → Permissions → Read SMS and enable it, then ask again."
            )
        }
        val take = limit.coerceIn(1, 50)
        return try {
            val projection = arrayOf(
                Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY,
                Telephony.Sms.DATE
            )
            context.contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI, projection, null, null,
                "${Telephony.Sms.DATE} DESC"
            ).use { cursor ->
                if (cursor == null) return ToolResult.error("Could not read messages.")
                val addrIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                val bodyIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
                val dateIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
                val out = StringBuilder()
                var count = 0
                while (cursor.moveToNext() && count < take) {
                    val addr = cursor.getString(addrIdx) ?: "unknown"
                    val body = (cursor.getString(bodyIdx) ?: "").take(300)
                    val date = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                        .format(java.util.Date(cursor.getLong(dateIdx)))
                    out.append("- $date from $addr: $body\n")
                    count++
                }
                if (count == 0) ToolResult.ok("The SMS inbox is empty.")
                else ToolResult.ok("Last $count message(s):\n$out")
            }
        } catch (e: Exception) {
            ToolResult.error("Could not read messages: ${e.message}")
        }
    }
}
