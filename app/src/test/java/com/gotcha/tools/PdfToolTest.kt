package com.gotcha.tools

import androidx.test.core.app.ApplicationProvider
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * `pdf_edit` end to end. Like [DocumentParserPdfTest] this runs under Robolectric
 * because pdfbox-android needs `android.util.Log` and its asset-backed glyph
 * tables; everything is kept inside the app sandbox so [FileResolver] grants
 * access without a permission stub.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PdfToolTest {

    private lateinit var tool: PdfTool
    private lateinit var dir: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        DocumentParser.init(context)
        tool = PdfTool(context)
        dir = File(context.filesDir, "pdftest").apply {
            deleteRecursively()
            mkdirs()
        }
    }

    // ---- helpers ----

    /** Writes a PDF whose page N carries the text "Page N", so page identity survives a round trip. */
    private fun makePdf(name: String, pageCount: Int, label: String = "Page"): File {
        val file = File(dir, name)
        PDDocument().use { doc ->
            repeat(pageCount) { i ->
                val page = PDPage(PDRectangle.A4)
                doc.addPage(page)
                PDPageContentStream(doc, page).use { stream ->
                    stream.beginText()
                    stream.setFont(PDType1Font.HELVETICA, 24f)
                    stream.newLineAtOffset(72f, 700f)
                    stream.showText("$label ${i + 1}")
                    stream.endText()
                }
            }
            doc.save(file)
        }
        return file
    }

    private fun textOf(file: File): String =
        DocumentParser.extract(file.readBytes(), file.name, "application/pdf").text

    private fun pageCountOf(file: File): Int =
        DocumentParser.extract(file.readBytes(), file.name, "application/pdf").pageCount ?: -1

    private fun ok(result: ToolResult): ToolResult {
        assertTrue("expected success, got: ${result.message}", result.success)
        return result
    }

    // ---- info ----

    @Test
    fun `info reports the page count`() {
        val src = makePdf("in.pdf", 3)
        val result = ok(tool.edit(operation = "info", input = src.path))
        assertTrue("got: ${result.message}", result.message.contains("3 page"))
    }

    // ---- merge ----

    @Test
    fun `merge joins documents in the given order`() {
        val a = makePdf("a.pdf", 2, label = "Alpha")
        val b = makePdf("b.pdf", 1, label = "Beta")
        val out = File(dir, "merged.pdf")

        ok(tool.edit(operation = "merge", inputs = listOf(a.path, b.path), output = out.path))

        assertEquals(3, pageCountOf(out))
        val text = textOf(out)
        assertTrue(
            "merged text should hold both sources, got: $text",
            text.contains("Alpha 1") && text.contains("Beta 1")
        )
        assertTrue("Alpha must precede Beta", text.indexOf("Alpha 1") < text.indexOf("Beta 1"))
    }

    @Test
    fun `merge of a single file is refused`() {
        val a = makePdf("a.pdf", 1)
        val result = tool.edit(operation = "merge", inputs = listOf(a.path), output = File(dir, "m.pdf").path)
        assertFalse(result.success)
        assertTrue(result.message.contains("at least two"))
    }

    // ---- split ----

    @Test
    fun `split writes one file per page`() {
        val src = makePdf("doc.pdf", 3)
        ok(tool.edit(operation = "split", input = src.path))

        val parts = (1..3).map { File(dir, "doc-p$it.pdf") }
        parts.forEach { assertTrue("${it.name} should exist", it.exists()) }
        assertEquals(1, pageCountOf(parts[0]))
        assertTrue(textOf(parts[2]).contains("Page 3"))
    }

    // ---- extract ----

    @Test
    fun `extract keeps exactly the selected pages in order`() {
        val src = makePdf("doc.pdf", 5)
        val out = File(dir, "slice.pdf")
        ok(tool.edit(operation = "extract_pages", input = src.path, output = out.path, pages = "2-3,5"))

        assertEquals(3, pageCountOf(out))
        val text = textOf(out)
        assertTrue(text.contains("Page 2") && text.contains("Page 3") && text.contains("Page 5"))
        assertFalse("page 1 should be gone, got: $text", text.contains("Page 1"))
        assertFalse("page 4 should be gone, got: $text", text.contains("Page 4"))
    }

    @Test
    fun `extract leaves the input untouched`() {
        val src = makePdf("doc.pdf", 4)
        val before = src.readBytes()
        ok(tool.edit(operation = "extract_pages", input = src.path, output = File(dir, "o.pdf").path, pages = "1"))
        org.junit.Assert.assertArrayEquals("the source PDF must not be modified", before, src.readBytes())
    }

    // ---- delete ----

    @Test
    fun `delete drops the selected pages`() {
        val src = makePdf("doc.pdf", 4)
        val out = File(dir, "trimmed.pdf")
        ok(tool.edit(operation = "delete_pages", input = src.path, output = out.path, pages = "2"))

        assertEquals(3, pageCountOf(out))
        val text = textOf(out)
        assertFalse("page 2 should be gone, got: $text", text.contains("Page 2"))
        assertTrue(text.contains("Page 1") && text.contains("Page 3") && text.contains("Page 4"))
    }

    @Test
    fun `deleting every page is refused`() {
        val src = makePdf("doc.pdf", 2)
        val result = tool.edit(
            operation = "delete_pages",
            input = src.path,
            output = File(dir, "e.pdf").path,
            pages = "all"
        )
        assertFalse(result.success)
        assertTrue(result.message.contains("empty PDF"))
    }

    // ---- rotate ----

    @Test
    fun `rotate turns only the selected pages and accumulates`() {
        val src = makePdf("doc.pdf", 3)
        val out = File(dir, "rot.pdf")
        ok(tool.edit(operation = "rotate_pages", input = src.path, output = out.path, pages = "2", degrees = 90))

        PDDocument.load(out).use { doc ->
            assertEquals(0, doc.getPage(0).rotation)
            assertEquals(90, doc.getPage(1).rotation)
            assertEquals(0, doc.getPage(2).rotation)
        }

        val twice = File(dir, "rot2.pdf")
        ok(tool.edit(operation = "rotate_pages", input = out.path, output = twice.path, pages = "2", degrees = 90))
        PDDocument.load(twice).use { doc -> assertEquals(180, doc.getPage(1).rotation) }
    }

    @Test
    fun `rotate defaults to every page`() {
        val src = makePdf("doc.pdf", 2)
        val out = File(dir, "rot.pdf")
        ok(tool.edit(operation = "rotate_pages", input = src.path, output = out.path, degrees = 180))
        PDDocument.load(out).use { doc ->
            assertEquals(180, doc.getPage(0).rotation)
            assertEquals(180, doc.getPage(1).rotation)
        }
    }

    @Test
    fun `rotate rejects an angle that is not a quarter turn`() {
        val src = makePdf("doc.pdf", 1)
        val result = tool.edit(
            operation = "rotate_pages",
            input = src.path,
            output = File(dir, "r.pdf").path,
            degrees = 45
        )
        assertFalse(result.success)
        assertTrue(result.message.contains("90"))
    }

    // ---- guards ----

    @Test
    fun `existing output is not clobbered without overwrite`() {
        val src = makePdf("doc.pdf", 2)
        val out = makePdf("taken.pdf", 1, label = "Existing")

        val refused = tool.edit(operation = "extract_pages", input = src.path, output = out.path, pages = "1")
        assertFalse(refused.success)
        assertTrue(refused.message.contains("overwrite=true"))
        assertTrue("the existing file must survive", textOf(out).contains("Existing 1"))

        ok(tool.edit(operation = "extract_pages", input = src.path, output = out.path, pages = "1", overwrite = true))
        assertTrue(textOf(out).contains("Page 1"))
    }

    @Test
    fun `writing back over the input succeeds with overwrite`() {
        val src = makePdf("doc.pdf", 4)
        ok(tool.edit(operation = "delete_pages", input = src.path, output = src.path, pages = "1", overwrite = true))
        assertEquals(3, pageCountOf(src))
        assertFalse(textOf(src).contains("Page 1"))
    }

    @Test
    fun `a non-PDF input is rejected by signature`() {
        val fake = File(dir, "notes.pdf").apply { writeText("I am plainly not a PDF") }
        val result = tool.edit(operation = "info", input = fake.path)
        assertFalse(result.success)
        assertTrue(result.message.contains("%PDF-"))
    }

    @Test
    fun `a missing input is reported with the resolved path`() {
        val result = tool.edit(operation = "info", input = File(dir, "ghost.pdf").path)
        assertFalse(result.success)
        assertTrue(result.message.contains("does not exist"))
    }

    @Test
    fun `an unknown operation lists the valid ones`() {
        val result = tool.edit(operation = "flatten")
        assertFalse(result.success)
        assertTrue(result.message.contains("merge"))
    }

    @Test
    fun `a missing required parameter names the parameter`() {
        val result = tool.edit(
            operation = "extract_pages",
            input = makePdf("doc.pdf", 1).path,
            output = File(dir, "o.pdf").path
        )
        assertFalse(result.success)
        assertTrue(result.message.contains("pages"))
    }

    @Test
    fun `no temporary files are left behind`() {
        val src = makePdf("doc.pdf", 3)
        ok(tool.edit(operation = "extract_pages", input = src.path, output = File(dir, "o.pdf").path, pages = "1"))
        val leftovers = dir.listFiles()!!.filter { it.name.contains("gotcha-tmp") }
        assertTrue("temp files left behind: ${leftovers.map { it.name }}", leftovers.isEmpty())
    }
}
