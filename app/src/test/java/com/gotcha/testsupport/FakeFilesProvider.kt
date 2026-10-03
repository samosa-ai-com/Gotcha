package com.gotcha.testsupport

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File

/**
 * A content provider standing in for the system file picker's documents: serves
 * [files] by path segment with a display name, MIME type and bytes. Register it
 * with `Robolectric.buildContentProvider(FakeFilesProvider::class.java).create(AUTHORITY)`
 * and hand out [uri]s.
 */
class FakeFilesProvider : ContentProvider() {

    data class FakeFile(val name: String, val mimeType: String, val bytes: ByteArray)

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String? = files[uri.lastPathSegment]?.mimeType

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        val file = files[uri.lastPathSegment] ?: return null
        return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply {
            addRow(arrayOf<Any>(file.name, file.bytes.size.toLong()))
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val file = files[uri.lastPathSegment] ?: return null
        val tmp = File.createTempFile("fake-files", null).apply {
            deleteOnExit()
            writeBytes(file.bytes)
        }
        return ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0

    companion object {
        const val AUTHORITY = "com.gotcha.test.files"
        val files = mutableMapOf<String, FakeFile>()

        /** Registers a file and returns the content:// URI that serves it. */
        fun add(key: String, name: String, mimeType: String, bytes: ByteArray): Uri {
            files[key] = FakeFile(name, mimeType, bytes)
            return uri(key)
        }

        fun uri(key: String): Uri = Uri.parse("content://$AUTHORITY/$key")
    }
}
