package com.gotcha.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Page-range arithmetic for `pdf_edit`. Pure JVM: [PdfPageSpec] deliberately
 * touches no Android or pdfbox API, because an off-by-one here silently deletes
 * the wrong page rather than failing loudly.
 */
class PdfPageSpecTest {

    private fun parsed(spec: String, pageCount: Int = 10): List<Int> =
        PdfPageSpec.parse(spec, pageCount).getOrThrow()

    private fun error(spec: String, pageCount: Int = 10): String {
        val result = PdfPageSpec.parse(spec, pageCount)
        assertTrue("expected '$spec' to be rejected, got ${result.getOrNull()}", result.isFailure)
        return result.exceptionOrNull()!!.message.orEmpty()
    }

    @Test
    fun `single page is one-based`() {
        assertEquals(listOf(1), parsed("1"))
        assertEquals(listOf(10), parsed("10"))
    }

    @Test
    fun `closed range is inclusive at both ends`() {
        assertEquals(listOf(2, 3, 4), parsed("2-4"))
    }

    @Test
    fun `open ended range runs to the last page`() {
        assertEquals(listOf(8, 9, 10), parsed("8-"))
    }

    @Test
    fun `mixed list is sorted and de-duplicated`() {
        assertEquals(listOf(1, 2, 3, 7), parsed("7,1-3,2"))
    }

    @Test
    fun `all and blank select every page`() {
        assertEquals((1..10).toList(), parsed("all"))
        assertEquals((1..10).toList(), parsed("ALL"))
        assertEquals((1..10).toList(), parsed("   "))
    }

    @Test
    fun `whitespace around tokens is tolerated`() {
        assertEquals(listOf(1, 2, 5), parsed(" 1 - 2 , 5 "))
    }

    @Test
    fun `page beyond the end is rejected with the real page count`() {
        val message = error("11", pageCount = 10)
        assertTrue("message should state the page count, got: $message", message.contains("10 page"))
    }

    @Test
    fun `range beyond the end is rejected`() {
        assertTrue(error("5-99").contains("out of bounds"))
    }

    @Test
    fun `page zero is rejected rather than silently clamped`() {
        assertTrue(error("0").isNotBlank())
        assertTrue(error("0-3").isNotBlank())
    }

    @Test
    fun `backwards range is rejected`() {
        assertTrue(error("7-3").contains("backwards"))
    }

    @Test
    fun `non numeric input is rejected`() {
        assertTrue(error("two").isNotBlank())
        assertTrue(error("1-x").isNotBlank())
        assertTrue(error("1-2-3").isNotBlank())
    }

    @Test
    fun `empty document is rejected`() {
        assertTrue(PdfPageSpec.parse("1", 0).isFailure)
    }

    @Test
    fun `describe collapses consecutive runs`() {
        assertEquals("1-3, 7", PdfPageSpec.describe(listOf(1, 2, 3, 7)))
        assertEquals("5", PdfPageSpec.describe(listOf(5)))
        assertEquals("1, 3, 5", PdfPageSpec.describe(listOf(1, 3, 5)))
        assertEquals("1-4", PdfPageSpec.describe(listOf(1, 2, 3, 4)))
        assertEquals("none", PdfPageSpec.describe(emptyList()))
    }
}
