package com.gotcha.tools

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Runs [DocumentParser] against the real sample files committed under
 * `app/src/test/resources/sample-documents/` — the same kit users transfer to a
 * device to manually test chat attachments. PDF needs pdfbox-android, so this
 * lives under Robolectric like [DocumentParserPdfTest].
 *
 * Each assertion mirrors what the README in `sample-documents/` promises, so a
 * regression in extraction (or a silent format change) fails here first.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SampleDocumentsTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        DocumentParser.init(ApplicationProvider.getApplicationContext())
        val anchor = checkNotNull(javaClass.getResource("/sample-documents/readme.txt")) {
            "sample-document fixtures missing from test resources"
        }
        dir = File(anchor.toURI()).parentFile
    }

    private fun extract(name: String): ExtractedDocument {
        val bytes = File(dir, name).readBytes()
        // mimeType = null exercises extension-based detection, which is the path
        // taken on-device when the picker returns application/octet-stream.
        return DocumentParser.extract(bytes, name, null)
    }

    // ---- Office / PDF ----

    @Test
    fun `pdf extracts text and reports its page count`() {
        val doc = extract("report.pdf")
        assertTrue(doc.text.contains("Launch Report"))
        assertEquals(2, doc.pageCount)
    }

    @Test
    fun `docx extracts paragraphs and headings`() {
        val doc = extract("proposal.docx")
        assertTrue(doc.text.contains("Project Falcon"))
        assertTrue(doc.text.contains("Acceptance criteria"))
    }

    @Test
    fun `xlsx extracts every sheet with shared inline and numeric cells`() {
        val doc = extract("budget.xlsx")
        assertTrue(doc.text.contains("Phones"))
        assertTrue(doc.text.contains("Grand total"))
        assertTrue(doc.text.contains("Hardware"))
        assertTrue(doc.text.contains("Travel"))
    }

    @Test
    fun `pptx joins the text of all slides`() {
        val doc = extract("deck.pptx")
        assertTrue(doc.text.contains("Project Falcon"))
        assertTrue(doc.text.contains("Document attachments in chat"))
        assertTrue(doc.text.contains("What's next"))
    }

    @Test
    fun `html is reduced to its text`() {
        assertTrue(extract("page.html").text.contains("Launch Report"))
    }

    @Test
    fun `rtf is read as plain text`() {
        val doc = extract("document.rtf")
        assertTrue(doc.text.contains("Project Falcon"))
        assertFalse(doc.truncated)
    }

    // ---- plain text formats ----

    @Test
    fun `plain text files extract their content`() {
        // `.gitignore` and `Main.kt` exist only in the on-device transfer kit:
        // Gradle's default resource excludes drop them, so they can't be fixtures.
        val expectations = mapOf(
            "readme.txt" to "Project Falcon",
            "notes.md" to "Acceptance criteria",
            "data.csv" to "falcon-app",
            "data.tsv" to "active_users",
            "config.json" to "documentAttachments",
            "data.xml" to "modules",
            "settings.yaml" to "document_attachments",
            "config.ini" to "wakeWord",
            "app.properties" to "document.max.attachment.bytes",
            "sample.env" to "MAX_ATTACHMENT_BYTES",
            "app.log" to "Corrupt document",
            "server.py" to "SUPPORTED_EXTS",
            "styles.css" to "attachment-chip",
            "queries.sql" to "CREATE TABLE"
        )
        for ((name, expected) in expectations) {
            val doc = extract(name)
            assertTrue(
                "$name should contain '$expected', got: '${doc.text.take(120)}'",
                doc.text.contains(expected)
            )
            assertFalse("$name should not be truncated", doc.truncated)
        }
    }

    // ---- edge / error paths ----

    @Test
    fun `oversized text is truncated with a visible note`() {
        val doc = extract("long.txt")
        assertTrue(doc.truncated)
        assertTrue(doc.text.contains("truncated"))
        assertTrue(doc.text.length <= DocumentParser.MAX_EXTRACTED_CHARS)
    }

    @Test
    fun `legacy doc and xls are rejected as unsupported`() {
        for (name in listOf("legacy.doc", "legacy.xls")) {
            try {
                extract(name)
                fail("expected UnsupportedFormat for $name")
            } catch (e: DocumentError.UnsupportedFormat) {
                assertTrue("$name should mention legacy formats", e.message!!.contains("not supported"))
            }
        }
    }

    @Test
    fun `corrupt pdf surfaces a clear error`() {
        try {
            extract("corrupt.pdf")
            fail("expected Corrupt")
        } catch (e: DocumentError.Corrupt) {
            assertTrue(e.message!!.isNotBlank())
        }
    }
}
