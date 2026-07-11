package com.gotcha.tools

import android.Manifest
import android.content.ContentProviderOperation
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

class ContactsTool(private val context: Context) {

    /** Resolve a name to one or more phone numbers (needs READ_CONTACTS). */
    fun findContact(name: String): ToolResult {
        val query = name.trim()
        if (query.isEmpty()) return ToolResult.error("Please provide a contact name to look up.")
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return ToolResult.permissionNeeded(
                Manifest.permission.READ_CONTACTS,
                "Looking up contacts needs the Contacts permission. I have requested it — please grant it and ask again."
            )
        }
        return try {
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.TYPE
            )
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                arrayOf("%$query%"),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
            ).use { cursor ->
                if (cursor == null) return ToolResult.error("Could not read contacts.")
                val nameIdx = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val results = StringBuilder()
                var count = 0
                while (cursor.moveToNext() && count < 20) {
                    val displayName = cursor.getString(nameIdx) ?: "unknown"
                    val number = cursor.getString(numIdx) ?: continue
                    results.append("- $displayName: $number\n")
                    count++
                }
                if (count == 0) ToolResult.ok("No contact matching '$query' was found.")
                else ToolResult.ok("Matches for '$query':\n$results")
            }
        } catch (e: Exception) {
            ToolResult.error("Could not look up contacts: ${e.message}")
        }
    }

    /** Create a new contact with a name and phone number (needs WRITE_CONTACTS). */
    fun addContact(name: String, number: String): ToolResult {
        val displayName = name.trim()
        val phone = number.trim()
        if (displayName.isEmpty()) return ToolResult.error("Please provide a name for the new contact.")
        if (phone.isEmpty()) return ToolResult.error("Please provide a phone number for the new contact.")
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return ToolResult.permissionNeeded(
                Manifest.permission.WRITE_CONTACTS,
                "Adding a contact needs the Contacts permission. I have requested it — please grant it and ask again."
            )
        }
        return try {
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
                    .withValue(
                        ContactsContract.Data.MIMETYPE,
                        ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE
                    )
                    .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, displayName)
                    .build()
            )
            ops.add(
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(
                        ContactsContract.Data.MIMETYPE,
                        ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE
                    )
                    .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, phone)
                    .withValue(
                        ContactsContract.CommonDataKinds.Phone.TYPE,
                        ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE
                    )
                    .build()
            )
            context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
            ToolResult.ok("Added contact '$displayName' with number $phone.")
        } catch (e: Exception) {
            ToolResult.error("Could not add the contact: ${e.message}")
        }
    }
}
