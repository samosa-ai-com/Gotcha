package com.gotcha.agent

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.gotcha.tools.DocumentParser
import com.gotcha.tools.ScreenPerception

/**
 * Turns a file's bytes into a [ComposerAttachment] the way the composer does:
 * an image is downscaled to JPEG base64, anything else has its text extracted
 * by [DocumentParser]. Shared by the composer and scheduled tasks (issue #132),
 * whose files are read once, when the task is saved.
 */
internal object AttachmentFiles {

    /**
     * Null when [bytes] claim to be an image that can't be decoded. An unreadable
     * or unsupported document throws [com.gotcha.tools.DocumentError], whose
     * message is fit to show the user.
     */
    fun fromBytes(bytes: ByteArray, name: String, mimeType: String?, maxDimension: Int = 1024): ComposerAttachment? {
        val id = java.util.UUID.randomUUID().toString()
        if (mimeType?.startsWith("image/") == true) {
            return imageBase64(bytes, maxDimension)?.let { ComposerAttachment.Image(id, name, it) }
        }
        val extracted = DocumentParser.extract(bytes, name, mimeType)
        return ComposerAttachment.Document(
            id,
            Attachment(
                name = name,
                mimeType = extracted.mimeType,
                size = bytes.size.toLong(),
                text = extracted.text,
                pageCount = extracted.pageCount,
                truncated = extracted.truncated
            )
        )
    }

    fun imageBase64(bytes: ByteArray, maxDimension: Int = 1024): String? = try {
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { bitmap ->
            ScreenPerception.compressBitmap(
                bitmap = bitmap,
                maxDimension = maxDimension,
                quality = 85,
                format = Bitmap.CompressFormat.JPEG,
                recycleInput = true
            )
        }
    } catch (_: Exception) {
        null
    }

    /** A MIME type for a file the agent names by path, from its extension. */
    fun mimeTypeFor(name: String): String? =
        android.webkit.MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())

    /** Extracted document text across [attachments], to hold to [ComposerAttachment.MAX_TOTAL_DOCUMENT_CHARS]. */
    fun documentChars(attachments: List<ComposerAttachment>): Int =
        attachments.sumOf { (it as? ComposerAttachment.Document)?.attachment?.text?.length ?: 0 }
}
