package com.gotcha.tools

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.CallLog
import android.provider.ContactsContract
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PhoneTool(private val context: Context) {

    private val numberPattern = Regex("^[+]?[0-9()\\-\\s#*]{2,20}$")

    fun dialNumber(number: String): ToolResult {
        val trimmed = number.trim()
        if (trimmed.isEmpty() || !trimmed.matches(numberPattern)) {
            return ToolResult.error(
                "'$number' does not look like a valid phone number. You may try find_contact or read_call_log to look " +
                    "up the correct number."
            )
        }
        val digitsOnly = trimmed.filter { it.isDigit() }
        if (digitsOnly.isEmpty()) {
            return ToolResult.error(
                "'$number' has no digits — a phone number needs at least one digit. You may find a contact first with find_contact or " +
                    "check the call log with read_call_log."
            )
        }
        return try {
            val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$trimmed")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (intent.resolveActivity(context.packageManager) == null) {
                return ToolResult.error(
                    "No dialer app is available on this device. You may try call_number instead if the Phone permission is granted."
                )
            }
            context.startActivity(intent)
            ToolResult.ok("Opened the dialer with $trimmed. The user must press call themselves.")
        } catch (e: Exception) {
            ToolResult.error("Could not open the dialer: ${e.message}")
        }
    }

    fun callNumber(number: String, speakerphone: Boolean? = null, simSlot: String? = null): ToolResult {
        val trimmed = number.trim()
        if (trimmed.isEmpty() || !trimmed.matches(numberPattern)) {
            return ToolResult.error(
                "'$number' does not look like a valid phone number. You may look up the correct number with find_contact or " +
                    "check recent calls with read_call_log."
            )
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return ToolResult.permissionNeeded(
                Manifest.permission.CALL_PHONE,
                "The Phone permission is not granted. Go to Settings → Permissions → Phone and enable it, then ask again."
            )
        }
        return try {
            val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$trimmed")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (simSlot != null) {
                    val subId = resolveSubId(simSlot)
                    if (subId != null) {
                        putExtra("android.telephony.extra.SUBSCRIPTION_INDEX", subId)
                    }
                }
            }
            context.startActivity(intent)

            val extras = mutableListOf<String>()
            if (speakerphone == true) {
                try {
                    val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    @Suppress("DEPRECATION")
                    am.isSpeakerphoneOn = true
                    extras.add("Speakerphone on.")
                } catch (_: Exception) { }
            }
            if (simSlot != null) extras.add("Using $simSlot.")
            val extra = if (extras.isNotEmpty()) " ${extras.joinToString(" ")}" else ""
            ToolResult.ok("Calling $trimmed.$extra")
        } catch (_: SecurityException) {
            ToolResult.permissionNeeded(
                Manifest.permission.CALL_PHONE,
                "The Phone permission is not granted. Go to Settings → Permissions → Phone and enable it, then ask again."
            )
        } catch (e: Exception) {
            ToolResult.error(
                "Could not place the call: ${e.message}. You may try dial_number instead (opens dialer without calling), or check " +
                    "the number with find_contact first."
            )
        }
    }

    private fun resolveSubId(simSlot: String): Int? {
        val phoneStateGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED
        if (!phoneStateGranted) return null
        return try {
            val subs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                context.getSystemService(SubscriptionManager::class.java)?.activeSubscriptionInfoList
            } else {
                @Suppress("DEPRECATION")
                SubscriptionManager.from(context).activeSubscriptionInfoList
            } ?: return null
            when (simSlot.trim().lowercase()) {
                "sim1", "slot1", "1", "primary", "main" -> subs.firstOrNull()?.subscriptionId
                "sim2", "slot2", "2", "secondary" -> subs.getOrNull(1)?.subscriptionId
                else -> null
            }
        } catch (_: Exception) { null }
    }

    @Suppress("CyclomaticComplexMethod")
    fun readCallLog(
        limit: Int,
        callTypeFilter: String? = null,
        contact: String? = null,
        fromDate: String? = null,
        toDate: String? = null
    ): ToolResult {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return ToolResult.permissionNeeded(
                Manifest.permission.READ_CALL_LOG,
                "The Call Log permission is not granted. Go to Settings → Permissions → Call Log and enable it, then ask again."
            )
        }
        val take = limit.coerceIn(1, 50)
        return try {
            val selection = StringBuilder()
            val selectionArgs = mutableListOf<String>()

            if (callTypeFilter != null) {
                val typeVal = when (callTypeFilter.trim().lowercase()) {
                    "incoming" -> CallLog.Calls.INCOMING_TYPE
                    "outgoing" -> CallLog.Calls.OUTGOING_TYPE
                    "missed" -> CallLog.Calls.MISSED_TYPE
                    "rejected" -> CallLog.Calls.REJECTED_TYPE
                    else -> null
                }
                if (typeVal != null) {
                    if (selection.isNotEmpty()) selection.append(" AND ")
                    selection.append("${CallLog.Calls.TYPE} = ?")
                    selectionArgs.add(typeVal.toString())
                }
            }

            if (contact != null) {
                if (selection.isNotEmpty()) selection.append(" AND ")
                selection.append("(${CallLog.Calls.NUMBER} LIKE ? OR ${CallLog.Calls.CACHED_NAME} LIKE ?)")
                selectionArgs.add("%$contact%")
                selectionArgs.add("%$contact%")
            }

            val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            if (fromDate != null) {
                val fromMillis = try { dateFormat.parse(fromDate)?.time } catch (_: Exception) { null }
                if (fromMillis != null) {
                    if (selection.isNotEmpty()) selection.append(" AND ")
                    selection.append("${CallLog.Calls.DATE} >= ?")
                    selectionArgs.add(fromMillis.toString())
                }
            }
            if (toDate != null) {
                val toMillis = try { dateFormat.parse(toDate)?.time?.plus(86_400_000L - 1) } catch (
                    _: Exception
                ) { null }
                if (toMillis != null) {
                    if (selection.isNotEmpty()) selection.append(" AND ")
                    selection.append("${CallLog.Calls.DATE} <= ?")
                    selectionArgs.add(toMillis.toString())
                }
            }

            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(
                    CallLog.Calls.NUMBER,
                    CallLog.Calls.CACHED_NAME,
                    CallLog.Calls.TYPE,
                    CallLog.Calls.DATE,
                    CallLog.Calls.DURATION
                ),
                selection.ifEmpty { null }?.toString(),
                selectionArgs.toTypedArray().ifEmpty { null },
                "${CallLog.Calls.DATE} DESC"
            ).use { cursor ->
                if (cursor == null) {
                    return ToolResult.error(
                        "Could not read the call log. You may check the Call Log permission in Settings → Permissions."
                    )
                }
                val numberIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
                val nameIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME)
                val typeIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.TYPE)
                val dateIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.DATE)
                val durIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.DURATION)
                val entries = StringBuilder()
                var count = 0
                while (cursor.moveToNext() && count < take) {
                    val number = cursor.getString(numberIdx) ?: "unknown"
                    var name = cursor.getString(nameIdx)
                    if (name.isNullOrBlank()) {
                        name = resolveContactName(number)
                    }
                    val type = when (cursor.getInt(typeIdx)) {
                        CallLog.Calls.INCOMING_TYPE -> "incoming"
                        CallLog.Calls.OUTGOING_TYPE -> "outgoing"
                        CallLog.Calls.MISSED_TYPE -> "missed"
                        CallLog.Calls.REJECTED_TYPE -> "rejected"
                        else -> "other"
                    }
                    val date = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                        .format(Date(cursor.getLong(dateIdx)))
                    val durSecs = cursor.getLong(durIdx)
                    val dur = if (durSecs >= 60) "${durSecs / 60}m ${durSecs % 60}s" else "${durSecs}s"
                    val who = if (!name.isNullOrBlank()) "$name ($number)" else number
                    entries.append("- $date  $type  $who  $dur\n")
                    count++
                }
                if (count == 0) {
                    ToolResult.ok("The call log is empty.")
                } else {
                    ToolResult.ok("Last $count call(s):\n$entries")
                }
            }
        } catch (e: Exception) {
            ToolResult.error("Could not read the call log: ${e.message}")
        }
    }

    private fun resolveContactName(number: String): String? {
        return try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.PhoneLookup.DISPLAY_NAME))
                } else {
                    null
                }
            }
        } catch (_: Exception) { null }
    }
}
