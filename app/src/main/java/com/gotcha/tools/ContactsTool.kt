package com.gotcha.tools

import android.Manifest
import android.content.ContentProviderOperation
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

class ContactsTool(private val context: Context) {

    fun findContact(name: String? = null, number: String? = null): ToolResult {
        if (name.isNullOrBlank() && number.isNullOrBlank()) {
            return ToolResult.error("Provide either a name or a phone number to search for.")
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return ToolResult.permissionNeeded(
                Manifest.permission.READ_CONTACTS,
                "The Contacts permission is not granted. Go to Settings → Permissions → Contacts and enable it, then ask again."
            )
        }
        return try {
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.TYPE,
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID
            )
            val selection = StringBuilder()
            val selectionArgs = mutableListOf<String>()
            if (!name.isNullOrBlank()) {
                selection.append("${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?")
                selectionArgs.add("%$name%")
            }
            if (!number.isNullOrBlank()) {
                if (selection.isNotEmpty()) selection.append(" OR ")
                selection.append("${ContactsContract.CommonDataKinds.Phone.NUMBER} LIKE ?")
                selectionArgs.add("%$number%")
            }
            val contacts = mutableListOf<Map<String, String>>()
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                selection.toString(),
                selectionArgs.toTypedArray(),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
            ).use { cursor ->
                if (cursor == null) return ToolResult.error("Could not read contacts.")
                val nameIdx = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val typeIdx = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.TYPE)
                while (cursor.moveToNext()) {
                    val displayName = cursor.getString(nameIdx) ?: "unknown"
                    val phoneNumber = cursor.getString(numIdx) ?: continue
                    val phoneType = when (cursor.getInt(typeIdx)) {
                        ContactsContract.CommonDataKinds.Phone.TYPE_HOME -> "home"
                        ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE -> "mobile"
                        ContactsContract.CommonDataKinds.Phone.TYPE_WORK -> "work"
                        ContactsContract.CommonDataKinds.Phone.TYPE_MAIN -> "main"
                        ContactsContract.CommonDataKinds.Phone.TYPE_FAX_WORK -> "fax work"
                        ContactsContract.CommonDataKinds.Phone.TYPE_FAX_HOME -> "fax home"
                        ContactsContract.CommonDataKinds.Phone.TYPE_PAGER -> "pager"
                        ContactsContract.CommonDataKinds.Phone.TYPE_OTHER -> "other"
                        else -> "other"
                    }
                    contacts.add(mapOf(
                        "name" to displayName,
                        "number" to phoneNumber,
                        "type" to phoneType
                    ))
                }
            }
            if (contacts.isEmpty()) {
                val hint = if (!name.isNullOrBlank()) "'$name'" else "'$number'"
                return ToolResult.ok("No contact matching $hint was found.")
            }
            // Group by contact name for richer output
            val grouped = contacts.groupBy { it["name"]!! }
            val sb = StringBuilder()
            grouped.entries.take(10).forEach { (contactName, entries) ->
                val emails = resolveEmail(entries.first()["number"] ?: "")
                val org = resolveOrganization(entries.first()["number"] ?: "")
                sb.append("- $contactName")
                if (org != null) sb.append(" ($org)")
                sb.append(":\n")
                entries.forEach { e ->
                    sb.append("    ${e["type"]}: ${e["number"]}\n")
                }
                if (emails.isNotEmpty()) {
                    sb.append("    email: ${emails.joinToString(", ")}\n")
                }
            }
            if (grouped.size > 10) {
                sb.append("... and ${grouped.size - 10} more contacts with that name.\n")
            }
            ToolResult.ok(sb.toString().trimEnd())
        } catch (e: Exception) {
            ToolResult.error("Could not look up contacts: ${e.message}")
        }
    }

    private fun resolveEmail(number: String): List<String> {
        return try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            val emails = mutableListOf<String>()
            context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup._ID),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val contactId = cursor.getString(0)
                    context.contentResolver.query(
                        ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                        arrayOf(ContactsContract.CommonDataKinds.Email.ADDRESS),
                        "${ContactsContract.CommonDataKinds.Email.CONTACT_ID} = ?",
                        arrayOf(contactId), null
                    )?.use { emailCursor ->
                        while (emailCursor.moveToNext()) {
                            emails.add(emailCursor.getString(0))
                        }
                    }
                }
            }
            emails
        } catch (_: Exception) { emptyList() }
    }

    private fun resolveOrganization(number: String): String? {
        return try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup._ID),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val contactId = cursor.getString(0)
                    context.contentResolver.query(
                        ContactsContract.Data.CONTENT_URI,
                        arrayOf(ContactsContract.CommonDataKinds.Organization.COMPANY),
                        "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
                        arrayOf(contactId, ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE),
                        null
                    )?.use { orgCursor ->
                        if (orgCursor.moveToFirst()) orgCursor.getString(0) else null
                    }
                } else null
            }
        } catch (_: Exception) { null }
    }

    fun addContact(
        name: String,
        number: String,
        phoneType: String? = null,
        email: String? = null,
        organization: String? = null
    ): ToolResult {
        val displayName = name.trim()
        val phone = number.trim()
        if (displayName.isEmpty()) return ToolResult.error("Please provide a name for the new contact.")
        if (phone.isEmpty()) return ToolResult.error("Please provide a phone number for the new contact.")
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return ToolResult.permissionNeeded(
                Manifest.permission.WRITE_CONTACTS,
                "The Contacts permission is not granted. Go to Settings → Permissions → Contacts and enable it, then ask again."
            )
        }
        return try {
            // Duplicate detection
            val dupes = findExisting(displayName, phone)
            if (dupes > 0) {
                return ToolResult.error(
                    "A contact matching '$displayName' or '$phone' already exists ($dupes match(es)). " +
                    "Use a different name or delete the existing contact first."
                )
            }
            val typeVal = when (phoneType?.trim()?.lowercase()) {
                "home" -> ContactsContract.CommonDataKinds.Phone.TYPE_HOME
                "mobile" -> ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE
                "work" -> ContactsContract.CommonDataKinds.Phone.TYPE_WORK
                "main" -> ContactsContract.CommonDataKinds.Phone.TYPE_MAIN
                "fax" -> ContactsContract.CommonDataKinds.Phone.TYPE_FAX_WORK
                "pager" -> ContactsContract.CommonDataKinds.Phone.TYPE_PAGER
                else -> ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE
            }
            val ops = arrayListOf<ContentProviderOperation>()
            ops.add(
                ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                    .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                    .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
                    .build()
            )
            ops.add(
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, displayName)
                    .build()
            )
            ops.add(
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, phone)
                    .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, typeVal)
                    .build()
            )
            if (!email.isNullOrBlank()) {
                ops.add(
                    ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                        .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                        .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE)
                        .withValue(ContactsContract.CommonDataKinds.Email.ADDRESS, email.trim())
                        .withValue(ContactsContract.CommonDataKinds.Email.TYPE, ContactsContract.CommonDataKinds.Email.TYPE_WORK)
                        .build()
                )
            }
            if (!organization.isNullOrBlank()) {
                ops.add(
                    ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                        .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                        .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE)
                        .withValue(ContactsContract.CommonDataKinds.Organization.COMPANY, organization.trim())
                        .withValue(ContactsContract.CommonDataKinds.Organization.TYPE, ContactsContract.CommonDataKinds.Organization.TYPE_WORK)
                        .build()
                )
            }
            context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
            val extras = buildString {
                append(" with number $phone")
                if (phoneType != null) append(" ($phoneType)")
                if (!email.isNullOrBlank()) append(", email $email")
                if (!organization.isNullOrBlank()) append(", $organization")
            }
            ToolResult.ok("Added contact '$displayName'$extras.")
        } catch (e: Exception) {
            ToolResult.error("Could not add the contact: ${e.message}")
        }
    }

    private fun findExisting(name: String, number: String): Int {
        return try {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf("COUNT(*)"),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} = ? OR ${ContactsContract.CommonDataKinds.Phone.NUMBER} = ?",
                arrayOf(name, number), null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getInt(0) else 0
            } ?: 0
        } catch (_: Exception) { 0 }
    }
}
