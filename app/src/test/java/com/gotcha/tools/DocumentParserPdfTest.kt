package com.gotcha.tools

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * PDF extraction via pdfbox-android. pdfbox-android calls `android.util.Log` and
 * loads its glyph tables from Android assets, so this tier runs under
 * Robolectric (which provides both) rather than plain JVM.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DocumentParserPdfTest {

    @Before
    fun setUp() {
        // Point pdfbox's resource loader at the (merged) app assets.
        DocumentParser.init(ApplicationProvider.getApplicationContext())
    }

    @Test
    fun `pdf text is extracted and page count reported`() {
        val doc = DocumentParser.extract(minimalPdf("Hello PDF World"), "report.pdf", "application/pdf")
        assertTrue(
            "extracted text should contain the page content, got: '${doc.text}'",
            doc.text.contains("Hello PDF World")
        )
        assertEquals(1, doc.pageCount)
        assertEquals("application/pdf", doc.mimeType)
    }

    @Test
    fun `pdf is recognised by extension alone`() {
        val doc = DocumentParser.extract(minimalPdf("Extension based"), "file.pdf", null)
        assertTrue(doc.text.contains("Extension based"))
        assertEquals(1, doc.pageCount)
    }

    @Test
    fun `corrupt pdf throws Corrupt`() {
        try {
            DocumentParser.extract("this is not a pdf".toByteArray(), "broken.pdf", "application/pdf")
            fail("expected Corrupt")
        } catch (e: DocumentError.Corrupt) {
            assertTrue(e.message!!.isNotBlank())
        }
    }

    /**
     * Builds a minimal one-page PDF (Catalog → Pages → Page → Contents) with a
     * single Helvetica text run, computing correct xref byte offsets so any
     * strict parser can read it without the repair-fallback path.
     */
    private fun minimalPdf(text: String): ByteArray {
        val stream = "BT /F1 24 Tf 100 700 Td ($text) Tj ET"
        val objects = listOf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] " +
                "/Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>",
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
            "<< /Length ${stream.toByteArray().size} >>\nstream\n$stream\nendstream"
        )

        val sb = StringBuilder("%PDF-1.4\n")
        val offsets = mutableListOf<Int>()
        for ((index, obj) in objects.withIndex()) {
            offsets.add(sb.length)
            sb.append("${index + 1} 0 obj\n$obj\nendobj\n")
        }
        val xrefOffset = sb.length
        sb.append("xref\n0 ${objects.size + 1}\n")
        sb.append("0000000000 65535 f \n")
        for (offset in offsets) {
            sb.append(offset.toString().padStart(10, '0')).append(" 00000 n \n")
        }
        sb.append("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xrefOffset\n%%EOF\n")
        return sb.toString().toByteArray()
    }
}
