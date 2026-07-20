package com.gotcha.tools

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Telephony
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SmsTool(private val context: Context) {

    private val numberPattern = Regex("^[+]?[0-9()\\-\\s#*]{2,20}$")

    fun sendSms(
        number: String,
        message: String,
        deliveryReport: Boolean? = null,
        sendAt: String? = null
    ): ToolResult {
        val trimmed = number.trim()
        if (!trimmed.matches(numberPattern)) {
            return ToolResult.error(
                "'$number' does not look like a valid phone number. You may use find_contact or read_call_log to look " +
                    "up the correct number."
            )
        }
        if (message.isBlank()) {
            return ToolResult.error(
                "The message text is empty. Please provide the text you want to send."
            )
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return ToolResult.permissionNeeded(
                Manifest.permission.SEND_SMS,
                "The SMS permission is not granted. Go to Settings → Permissions → SMS and enable it, then ask again."
            )
        }

        // Scheduled send
        if (sendAt != null) {
            val delayMs = parseSendAt(sendAt)
            if (delayMs == null) {
                return ToolResult.error(
                    "Could not parse send_at. Use an ISO-8601 timestamp like '2026-01-15T14:30:00' or epoch millis."
                )
            }
            if (delayMs <= 0) {
                return ToolResult.error("send_at must be in the future.")
            }
            scheduleSms(trimmed, message, delayMs)
            val eta = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                .format(Date(System.currentTimeMillis() + delayMs))
            return ToolResult.ok("Scheduled SMS to $trimmed for $eta (in ${delayMs / 60000} minutes).")
        }

        return try {
            val smsManager = smsManager()

            val parts = smsManager.divideMessage(message)
            val segmentCount = parts.size
            val encoding = detectEncoding(message)
            var deliveryResult: String? = null
            val sentLatch = if (deliveryReport == true) CountDownLatch(1) else null

            if (deliveryReport == true) {
                val sentIntent = createSentIntent(trimmed, message, sentLatch)
                if (segmentCount > 1) {
                    val sentIntents = arrayListOf<PendingIntent>().apply {
                        repeat(segmentCount) { add(sentIntent) }
                    }
                    smsManager.sendMultipartTextMessage(trimmed, null, parts, sentIntents, null)
                } else {
                    smsManager.sendTextMessage(trimmed, null, message, sentIntent, null)
                }
                sentLatch?.await(10, TimeUnit.SECONDS)
                // Read delivery result from the receiver
                deliveryResult = lastDeliveryResult
            } else {
                if (segmentCount > 1) {
                    smsManager.sendMultipartTextMessage(trimmed, null, parts, null, null)
                } else {
                    smsManager.sendTextMessage(trimmed, null, message, null, null)
                }
            }

            // Show recent conversation context
            val threadContext = getConversationThread(trimmed)

            buildString {
                append("Sent a text to $trimmed.")
                append(" $segmentCount segment(s)")
                if (encoding != "GSM 7-bit") append(" (using $encoding)")
                append(".")
                if (deliveryResult != null) append(" Delivery: $deliveryResult.")
                if (threadContext != null) append("\n\nConversation:\n$threadContext")
            }.let { ToolResult.ok(it) }
        } catch (_: SecurityException) {
            ToolResult.permissionNeeded(
                Manifest.permission.SEND_SMS,
                "The SMS permission is not granted. Go to Settings → Permissions → SMS and enable it, then ask again."
            )
        } catch (e: Exception) {
            ToolResult.error("Could not send the text: ${e.message}")
        }
    }

    private var lastDeliveryResult: String? = null

    private var deliveryReceiver: BroadcastReceiver? = null

    private fun createSentIntent(number: String, message: String, latch: CountDownLatch?): PendingIntent {
        val intent = Intent("com.gotcha.SMS_SENT").apply {
            setPackage(context.packageName)
            putExtra("number", number)
            putExtra("message", message)
        }
        val pi = PendingIntent.getBroadcast(
            context,
            System.currentTimeMillis().toInt() and 0x7FFFFFFF,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        if (deliveryReceiver == null) {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    val code = resultCode
                    lastDeliveryResult = when (code) {
                        Activity.RESULT_OK -> "Sent"
                        SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "Generic failure"
                        SmsManager.RESULT_ERROR_NO_SERVICE -> "No service"
                        SmsManager.RESULT_ERROR_NULL_PDU -> "Null PDU"
                        SmsManager.RESULT_ERROR_RADIO_OFF -> "Radio off"
                        else -> "Unknown ($code)"
                    }
                    latch?.countDown()
                }
            }
            deliveryReceiver = receiver
            // The delivery result is delivered by the system to our own PendingIntent,
            // so the receiver must not be exported (also avoids the API 33+ overload
            // crashing on older devices — minSdk is 26).
            ContextCompat.registerReceiver(
                context,
                receiver,
                IntentFilter("com.gotcha.SMS_SENT"),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }
        return pi
    }

    private fun smsManager(): SmsManager {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        }
    }

    private fun detectEncoding(message: String): String {
        val gsm7 = setOf(
            '@', '\u00a3', '\u0024', '\u00a5', '\u00e8', '\u00e9', '\u00f9', '\u00ec',
            '\u00f2', '\u00c7', '\u000a', '\u00d8', '\u00f8', '\u000d', '\u00c5', '\u00e5',
            '\u0394', '\u005f', '\u03a6', '\u0393', '\u039b', '\u03a9', '\u03a0', '\u03a8',
            '\u03a3', '\u0398', '\u039e', '\u0020', '\u00c6', '\u00e6', '\u00df', '\u00c9',
            ' ', '!', '"', '#', '\u00a4', '%', '&', '\'', '(', ')', '*', '+', ',', '-', '.', '/',
            '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', ':', ';', '<', '=', '>', '?',
            '\u00a1', 'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I', 'J', 'K', 'L', 'M',
            'N', 'O', 'P', 'Q', 'R', 'S', 'T', 'U', 'V', 'W', 'X', 'Y', 'Z',
            '\u00c4', '\u00d6', '\u00d1', '\u00dc', '\u00a7', '\u00bf',
            'a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'i', 'j', 'k', 'l', 'm',
            'n', 'o', 'p', 'q', 'r', 's', 't', 'u', 'v', 'w', 'x', 'y', 'z',
            '\u00e4', '\u00f6', '\u00f1', '\u00fc', '\u00e0'
        )
        return if (message.all { it in gsm7 }) "GSM 7-bit" else "UCS-2"
    }

    private fun parseSendAt(sendAt: String): Long? {
        try {
            val millis = sendAt.toLongOrNull()
            if (millis != null) return millis - System.currentTimeMillis()
        } catch (_: Exception) {}
        try {
            val formats = listOf(
                "yyyy-MM-dd'T'HH:mm:ssXXX",
                "yyyy-MM-dd'T'HH:mm:ssX",
                "yyyy-MM-dd'T'HH:mmXXX",
                "yyyy-MM-dd'T'HH:mmX",
                "yyyy-MM-dd'T'HH:mm:ss'Z'",
                "yyyy-MM-dd'T'HH:mm'Z'",
                "yyyy-MM-dd'T'HH:mm:ss",
                "yyyy-MM-dd'T'HH:mm",
                "yyyy-MM-dd HH:mm:ss",
                "yyyy-MM-dd HH:mm"
            )
            for (fmt in formats) {
                try {
                    val date = SimpleDateFormat(fmt, Locale.US).parse(sendAt)
                    if (date != null) return date.time - System.currentTimeMillis()
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
        return null
    }

    private fun scheduleSms(number: String, message: String, delayMs: Long) {
        val intent = Intent(context, ScheduledSmsReceiver::class.java).apply {
            putExtra("number", number)
            putExtra("message", message)
        }
        val pi = PendingIntent.getBroadcast(
            context,
            number.hashCode() and 0x7FFFFFFF,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarm.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + delayMs, pi)
    }

    fun readRecentSms(
        limit: Int,
        fromAddress: String? = null,
        fromDate: String? = null,
        toDate: String? = null,
        unreadOnly: Boolean? = null,
        search: String? = null,
        includeSent: Boolean? = null
    ): ToolResult {
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
            val selection = StringBuilder()
            val selectionArgs = mutableListOf<String>()

            if (!fromAddress.isNullOrBlank()) {
                if (selection.isNotEmpty()) selection.append(" AND ")
                selection.append("${Telephony.Sms.ADDRESS} LIKE ?")
                selectionArgs.add("%$fromAddress%")
            }

            val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            if (fromDate != null) {
                val fromMillis = try { dateFormat.parse(fromDate)?.time } catch (_: Exception) { null }
                if (fromMillis != null) {
                    if (selection.isNotEmpty()) selection.append(" AND ")
                    selection.append("${Telephony.Sms.DATE} >= ?")
                    selectionArgs.add(fromMillis.toString())
                }
            }
            if (toDate != null) {
                val toMillis = try { dateFormat.parse(toDate)?.time?.plus(86_400_000L - 1) } catch (
                    _: Exception
                ) { null }
                if (toMillis != null) {
                    if (selection.isNotEmpty()) selection.append(" AND ")
                    selection.append("${Telephony.Sms.DATE} <= ?")
                    selectionArgs.add(toMillis.toString())
                }
            }

            if (unreadOnly == true) {
                if (selection.isNotEmpty()) selection.append(" AND ")
                selection.append("${Telephony.Sms.READ} = 0")
            }

            if (!search.isNullOrBlank()) {
                if (selection.isNotEmpty()) selection.append(" AND ")
                selection.append("${Telephony.Sms.BODY} LIKE ?")
                selectionArgs.add("%$search%")
            }

            val sel: String? = if (selection.isNotEmpty()) selection.toString() else null
            val selArgs: Array<String>? = selectionArgs.toTypedArray().ifEmpty { null }

            val out = StringBuilder()

            // Inbox messages
            querySms(Telephony.Sms.Inbox.CONTENT_URI, take, sel, selArgs, out, "from")

            // Sent messages (optional)
            if (includeSent == true) {
                querySms(Telephony.Sms.Sent.CONTENT_URI, take, sel, selArgs, out, "to")
            }

            if (out.isEmpty()) {
                val hint = buildString {
                    if (!fromAddress.isNullOrBlank()) append(" from '$fromAddress'")
                    if (unreadOnly == true) append(" unread")
                    if (!search.isNullOrBlank()) append(" matching '$search'")
                }
                ToolResult.ok("The SMS inbox is empty$hint.")
            } else {
                ToolResult.ok(out.trimEnd().toString())
            }
        } catch (e: Exception) {
            ToolResult.error("Could not read messages: ${e.message}")
        }
    }

    private fun querySms(
        uri: android.net.Uri,
        take: Int,
        selection: String?,
        selectionArgs: Array<String>?,
        out: StringBuilder,
        direction: String
    ) {
        context.contentResolver.query(
            uri,
            arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms._ID),
            selection,
            selectionArgs,
            "${Telephony.Sms.DATE} DESC"
        )?.use { cursor ->
            val addrIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val bodyIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val dateIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
            var count = 0
            while (cursor.moveToNext() && count < take) {
                val addr = cursor.getString(addrIdx) ?: "unknown"
                val name = resolveSmsContactName(addr)
                val sender = if (name != null) "$name ($addr)" else addr
                val body = (cursor.getString(bodyIdx) ?: "").take(300)
                val date = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                    .format(Date(cursor.getLong(dateIdx)))
                when (direction) {
                    "from" -> out.append("- $date from $sender: $body\n")
                    "to" -> out.append("- $date to $sender: $body\n")
                }
                count++
            }
        }
    }

    private fun resolveSmsContactName(number: String): String? {
        return try {
            val uri = android.net.Uri.withAppendedPath(
                android.provider.ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                android.net.Uri.encode(number)
            )
            context.contentResolver.query(
                uri,
                arrayOf(android.provider.ContactsContract.PhoneLookup.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getString(
                        cursor.getColumnIndexOrThrow(
                            android.provider.ContactsContract.PhoneLookup.DISPLAY_NAME
                        )
                    )
                } else {
                    null
                }
            }
        } catch (_: Exception) { null }
    }

    private fun getConversationThread(number: String): String? {
        return try {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                return null
            }
            context.contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI,
                arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
                "${Telephony.Sms.ADDRESS} LIKE ?",
                arrayOf("%${number.take(10)}%"),
                "${Telephony.Sms.DATE} DESC"
            )?.use { cursor ->
                val addrIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                val bodyIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
                val dateIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
                val lines = mutableListOf<String>()
                while (cursor.moveToNext() && lines.size < 3) {
                    val addr = cursor.getString(addrIdx) ?: "unknown"
                    val body = (cursor.getString(bodyIdx) ?: "").take(160)
                    val date = SimpleDateFormat("HH:mm", Locale.getDefault())
                        .format(Date(cursor.getLong(dateIdx)))
                    lines.add("$date $addr: $body")
                }
                if (lines.isEmpty()) null else lines.reversed().joinToString("\n")
            }
        } catch (_: Exception) { null }
    }
}
