package com.gotcha.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Pure-JVM behaviour for [DocumentParser] — everything except PDF (which needs
 * pdfbox-android and lives in [DocumentParserPdfTest] under Robolectric).
 *
 * Fixtures are built programmatically (in-memory ZIPs for Office files) so no
 * binary test assets are committed.
 */
class DocumentParserTest {

    // ---- text formats ----

    @Test
    fun `plain text file extracts as-is`() {
        val doc = DocumentParser.extract("hello world".toByteArray(), "notes.txt", "text/plain")
        assertEquals("hello world", doc.text)
        assertEquals("text/plain", doc.mimeType)
        assertFalse(doc.truncated)
        assertNull(doc.pageCount)
    }

    @Test
    fun `text file is recognised by extension when mime is generic`() {
        val doc = DocumentParser.extract("a,b\n1,2".toByteArray(), "data.csv", "application/octet-stream")
        assertEquals("a,b\n1,2", doc.text)
    }

    @Test
    fun `UTF-8 BOM is stripped from text files`() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "content".toByteArray()
        val doc = DocumentParser.extract(bytes, "bom.txt", "text/plain")
        assertEquals("content", doc.text)
    }

    @Test
    fun `unknown extension with no mime is unsupported`() {
        try {
            DocumentParser.extract("binary?".toByteArray(), "weird.xyz", null)
            fail("expected UnsupportedFormat")
        } catch (e: DocumentError.UnsupportedFormat) {
            assertTrue(e.message!!.contains("xyz"))
        }
    }

    // ---- HTML (jsoup) ----

    @Test
    fun `html is reduced to its text`() {
        val html = "<html><body><h1>Title</h1><p>Some <b>bold</b> text.</p></body></html>"
        val doc = DocumentParser.extract(html.toByteArray(), "page.html", "text/html")
        assertEquals("Title Some bold text.", doc.text)
    }

    // ---- RTF ----

    @Test
    fun `rtf control words are stripped to plain text`() {
        val rtf = """{\rtf1\ansi\ansicpg1252\deff0
{\pard \ql \f0 \sa180 \li0 \fi0 \b \fs36 Project Falcon\par}
{\pard \f0 Hello \b world\par}}"""
        val doc = DocumentParser.extract(rtf.toByteArray(), "doc.rtf", null)
        assertTrue("control words must be dropped, got: '${doc.text}'", doc.text.contains("Project Falcon"))
        assertTrue(doc.text.contains("Hello world"))
    }

    @Test
    fun `rtf unicode and hex escapes decode`() {
        val rtf = "{\\rtf1\\ansi\\uc1 Q3 \\u8212- \\u-57324? em dash caf\\'e9}"
        val doc = DocumentParser.extract(rtf.toByteArray(), "doc.rtf", null)
        assertTrue(
            "escapes should decode, got: '${doc.text}'",
            doc.text.contains("Q3 — — em dash café")
        )
    }

    @Test
    fun `rtf escaped braces and backslashes are kept`() {
        val rtf = "{\\rtf1\\ansi {literal \\{brace\\}} \\\\ backslash}"
        val doc = DocumentParser.extract(rtf.toByteArray(), "doc.rtf", null)
        assertTrue(doc.text.contains("{brace}"))
        assertTrue(doc.text.contains("\\ backslash"))
    }

    @Test
    fun `rtf ignorable destinations are dropped`() {
        val rtf = "{\\rtf1\\ansi {\\*\\fonttbl {\\f0 Arial;}} Hello}"
        val doc = DocumentParser.extract(rtf.toByteArray(), "doc.rtf", null)
        assertFalse("font table must not leak into the text", doc.text.contains("fonttbl"))
        assertTrue(doc.text.contains("Hello"))
    }

    @Test
    fun `rtf-named file without an rtf header falls back to plain text`() {
        val doc = DocumentParser.extract("just some text".toByteArray(), "doc.rtf", null)
        assertEquals("just some text", doc.text)
    }

    // ---- .docx ----

    @Test
    fun `docx concatenates text runs per paragraph`() {
        val bytes = zipWith(
            "word/document.xml" to
                """<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                <w:body>
                  <w:p><w:r><w:t>Hello </w:t></w:r><w:r><w:t>world</w:t></w:r></w:p>
                  <w:p><w:r><w:t>Second paragraph</w:t></w:r></w:p>
                </w:body>
              </w:document>"""
        )
        val doc = DocumentParser.extract(bytes, "notes.docx", null)
        assertEquals("Hello world\nSecond paragraph", doc.text)
        assertEquals("application/vnd.openxmlformats-officedocument.wordprocessingml.document", doc.mimeType)
    }

    @Test
    fun `docx without the document part is corrupt`() {
        val bytes = zipWith("word/stuff.xml" to "<x/>")
        try {
            DocumentParser.extract(bytes, "broken.docx", null)
            fail("expected Corrupt")
        } catch (e: DocumentError.Corrupt) {
            assertTrue(e.message!!.contains("word/document.xml"))
        }
    }

    @Test
    fun `docx with a doctype entity is rejected`() {
        val malicious = zipWith(
            "word/document.xml" to
                """<?xml version="1.0"?>
                <!DOCTYPE w:document [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:body><w:p><w:r><w:t>&xxe;</w:t></w:r></w:p></w:body>
                </w:document>"""
        )
        try {
            DocumentParser.extract(malicious, "evil.docx", null)
            fail("expected Corrupt for doctype-bearing docx")
        } catch (e: DocumentError.Corrupt) {
            // The entity must never be resolved; a parse failure is the safe outcome.
            assertTrue(e.message!!.contains("invalid XML"))
        }
    }

    @Test
    fun `empty docx yields empty text`() {
        val bytes = zipWith(
            "word/document.xml" to
                """<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                <w:body><w:p><w:r><w:t>   </w:t></w:r></w:p></w:body>
              </w:document>"""
        )
        assertEquals("", DocumentParser.extract(bytes, "empty.docx", null).text)
    }

    // ---- .xlsx ----

    @Test
    fun `xlsx resolves shared strings and inline strings by row`() {
        val bytes = zipWith(
            "xl/sharedStrings.xml" to
                """<sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" count="2" uniqueCount="2">
                <si><t>Alpha</t></si><si><t>Beta</t></si>
              </sst>""",
            "xl/worksheets/sheet1.xml" to
                """<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                <sheetData>
                  <row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1"><v>42</v></c></row>
                  <row r="2"><c r="A2" t="s"><v>1</v></c><c r="B2" t="inlineStr"><is><t>inline text</t></is></c></row>
                </sheetData>
              </worksheet>"""
        )
        val doc = DocumentParser.extract(bytes, "data.xlsx", null)
        assertEquals("Alpha | 42\nBeta | inline text", doc.text)
        assertEquals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", doc.mimeType)
    }

    @Test
    fun `xlsx with no worksheets is corrupt`() {
        val bytes = zipWith("xl/sharedStrings.xml" to "<sst xmlns=\"x\"/>")
        try {
            DocumentParser.extract(bytes, "empty.xlsx", null)
            fail("expected Corrupt")
        } catch (e: DocumentError.Corrupt) {
            assertTrue(e.message!!.contains("worksheets"))
        }
    }

    // ---- .pptx ----

    @Test
    fun `pptx joins slide text`() {
        val bytes = zipWith(
            "ppt/slides/slide1.xml" to
                """<p:sld xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"
                     xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">
                <p:cSld><p:spTree>
                  <p:sp><p:txBody><a:p><a:r><a:t>Slide </a:t></a:r><a:r><a:t>one</a:t></a:r></a:p></p:txBody></p:sp>
                </p:spTree></p:cSld>
              </p:sld>"""
        )
        val doc = DocumentParser.extract(bytes, "deck.pptx", null)
        assertEquals("Slide one", doc.text)
    }

    // ---- errors ----

    @Test
    fun `legacy doc and xls are unsupported with a helpful message`() {
        for (name in listOf("old.doc", "old.xls")) {
            try {
                DocumentParser.extract("not a real office file".toByteArray(), name, null)
                fail("expected UnsupportedFormat for $name")
            } catch (e: DocumentError.UnsupportedFormat) {
                assertTrue(e.message!!.contains("not supported"))
            }
        }
    }

    @Test
    fun `a zip that is not an office file is unsupported`() {
        val bytes = zipWith("random.txt" to "hi")
        try {
            DocumentParser.extract(bytes, "archive.zip", "application/zip")
            fail("expected UnsupportedFormat")
        } catch (e: DocumentError.UnsupportedFormat) {
            assertTrue(e.message!!.contains("application/zip"))
        }
    }

    @Test
    fun `oversized files throw TooLarge`() {
        val bytes = ByteArray(DocumentParser.MAX_DOC_BYTES.toInt() + 1)
        try {
            DocumentParser.extract(bytes, "big.pdf", "application/pdf")
            fail("expected TooLarge")
        } catch (e: DocumentError.TooLarge) {
            assertTrue(e.message!!.contains("20 MB"))
        }
    }

    @Test
    fun `garbage bytes for a pdf-looking file are corrupt`() {
        try {
            DocumentParser.extract("not a pdf at all".toByteArray(), "broken.pdf", "application/pdf")
            fail("expected Corrupt")
        } catch (e: DocumentError.Corrupt) {
            // pdfbox fails cleanly on non-PDF input.
            assertTrue(e.message!!.isNotBlank())
        }
    }

    // ---- truncation ----

    @Test
    fun `long text is truncated with a visible note`() {
        val longText = "a".repeat(50_000)
        val doc = DocumentParser.extract(longText.toByteArray(), "big.txt", "text/plain")
        assertTrue(doc.truncated)
        assertTrue(
            "truncated text must stay at or under the cap",
            doc.text.length <= DocumentParser.MAX_EXTRACTED_CHARS
        )
        assertTrue("truncation must be visible", doc.text.contains("truncated"))
    }

    @Test
    fun `short text is not truncated`() {
        val doc = DocumentParser.extract("short".toByteArray(), "short.txt", "text/plain")
        assertFalse(doc.truncated)
        assertEquals("short", doc.text)
    }

    @Test
    fun `mime is canonicalised to lowercase`() {
        val doc = DocumentParser.extract("hi".toByteArray(), "note.TXT", "Text/Plain")
        assertEquals("text/plain", doc.mimeType)
    }

    // ---- helpers ----

    private fun zipWith(vararg entries: Pair<String, String>): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            for ((name, content) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(content.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
        }
        return baos.toByteArray()
    }
}
