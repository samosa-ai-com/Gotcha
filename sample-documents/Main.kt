package com.gotcha.sample

import com.gotcha.tools.DocumentParser

/**
 * Sample Kotlin file for attachment testing.
 *
 * Pick this file in chat mode: the source text below is what the model sees.
 */
object AttachmentDemo {

    private val MAX_ATTACHMENT_BYTES = 20L * 1024 * 1024
    private val MAX_EXTRACTED_CHARS = 40_000

    fun describeExtracted(doc: DocumentParser.ExtractedDocument): String {
        val pages = doc.pageCount?.let { "$it pages" } ?: "n/a"
        val size = if (doc.truncated) "truncated" else "full text"
        return "File: ${doc.fileName} | MIME: ${doc.mimeType} | Pages: $pages | $size"
    }
}
