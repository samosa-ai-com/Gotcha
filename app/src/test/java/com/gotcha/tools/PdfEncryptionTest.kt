package com.gotcha.tools

import androidx.test.core.app.ApplicationProvider
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Password-protected PDFs. These are the reason pdfbox's bouncycastle dependency
 * is no longer excluded, so this test is what justifies that APK cost: if it can
 * pass without the provider, the exclusion should come back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PdfEncryptionTest {

    private lateinit var tool: PdfTool
    private lateinit var dir: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        DocumentParser.init(context)
        tool = PdfTool(context)
        dir = File(context.filesDir, "pdfcrypt").apply {
            deleteRecursively()
            mkdirs()
        }
    }

    /** A 2-page PDF locked with [password] at the given key length (40/128/256 bit). */
    private fun encryptedPdf(name: String, password: String, keyLength: Int): File {
        val file = File(dir, name)
        PDDocument().use { doc ->
            doc.addPage(PDPage())
            doc.addPage(PDPage())
            val policy = StandardProtectionPolicy(password, password, AccessPermission()).apply {
                encryptionKeyLength = keyLength
            }
            doc.protect(policy)
            doc.save(file)
        }
        return file
    }

    private fun assertReadable(keyLength: Int) {
        val file = encryptedPdf("locked$keyLength.pdf", "s3cret", keyLength)
        val result = tool.edit(operation = "info", input = file.path, password = "s3cret")
        assertTrue("$keyLength-bit encrypted PDF should open, got: ${result.message}", result.success)
        assertTrue(result.message.contains("2 page"))
        assertTrue("should report that it was encrypted, got: ${result.message}", result.message.contains("encrypted"))
    }

    @Test
    fun `40 bit encrypted pdf opens with the password`() = assertReadable(40)

    @Test
    fun `128 bit encrypted pdf opens with the password`() = assertReadable(128)

    @Test
    fun `256 bit AES encrypted pdf opens with the password`() = assertReadable(256)

    @Test
    fun `output of an edited encrypted pdf is written unencrypted`() {
        val file = encryptedPdf("locked.pdf", "s3cret", 128)
        val out = File(dir, "page1.pdf")
        val result = tool.edit(
            operation = "extract_pages",
            input = file.path,
            output = out.path,
            pages = "1",
            password = "s3cret",
            confirmed = true
        )
        assertTrue("got: ${result.message}", result.success)
        PDDocument.load(out).use { doc -> assertFalse("output must not stay encrypted", doc.isEncrypted) }
    }

    @Test
    fun `a wrong password is reported as such, not as a crash`() {
        val file = encryptedPdf("locked.pdf", "s3cret", 128)
        val result = tool.edit(operation = "info", input = file.path, password = "wrong")
        assertFalse(result.success)
        assertTrue("got: ${result.message}", result.message.contains("password"))
    }

    @Test
    fun `a missing password tells the model to ask the user`() {
        val file = encryptedPdf("locked.pdf", "s3cret", 128)
        val result = tool.edit(operation = "info", input = file.path)
        assertFalse(result.success)
        assertTrue("got: ${result.message}", result.message.contains("password-protected"))
    }

    // ---- the consent gate ----

    @Test
    fun `editing a protected pdf is refused until the user has been warned`() {
        val file = encryptedPdf("locked.pdf", "s3cret", 128)
        val out = File(dir, "page1.pdf")

        val refused = tool.edit(
            operation = "extract_pages",
            input = file.path,
            output = out.path,
            pages = "1",
            password = "s3cret"
        )
        assertFalse("an unconfirmed edit of a locked PDF must be refused", refused.success)
        assertTrue("got: ${refused.message}", refused.message.contains("confirmed=true"))
        assertTrue(
            "the refusal must state the consequence, got: ${refused.message}",
            refused.message.contains("without the password")
        )
        assertFalse("nothing may be written before consent", out.exists())
    }

    @Test
    fun `every write operation is gated, not just extract`() {
        val a = encryptedPdf("a.pdf", "s3cret", 128)
        val b = encryptedPdf("b.pdf", "s3cret", 128)
        val gated = listOf(
            tool.edit(operation = "split", input = a.path, output = File(dir, "out").path, password = "s3cret"),
            tool.edit(
                operation = "delete_pages",
                input = a.path,
                output = File(dir, "d.pdf").path,
                pages = "1",
                password = "s3cret"
            ),
            tool.edit(
                operation = "rotate_pages",
                input = a.path,
                output = File(dir, "r.pdf").path,
                degrees = 90,
                password = "s3cret"
            ),
            tool.edit(
                operation = "merge",
                inputs = listOf(a.path, b.path),
                output = File(dir, "m.pdf").path,
                password = "s3cret"
            )
        )
        gated.forEach { result ->
            assertFalse("expected a refusal, got: ${result.message}", result.success)
            assertTrue("got: ${result.message}", result.message.contains("confirmed=true"))
        }
        assertFalse("the split must not have written anything", File(dir, "out/a-p1.pdf").exists())
        assertFalse(File(dir, "m.pdf").exists())
    }

    @Test
    fun `info is not gated because it writes nothing`() {
        val file = encryptedPdf("locked.pdf", "s3cret", 128)
        val result = tool.edit(operation = "info", input = file.path, password = "s3cret")
        assertTrue("reading a locked PDF needs no consent, got: ${result.message}", result.success)
        assertTrue(
            "info should still flag the consequence, got: ${result.message}",
            result.message.contains("UNPROTECTED")
        )
    }

    @Test
    fun `the success message states that protection was lost`() {
        val file = encryptedPdf("locked.pdf", "s3cret", 128)
        val result = tool.edit(
            operation = "extract_pages",
            input = file.path,
            output = File(dir, "page1.pdf").path,
            pages = "1",
            password = "s3cret",
            confirmed = true
        )
        assertTrue(result.success)
        assertTrue("got: ${result.message}", result.message.contains("NOT"))
    }

    @Test
    fun `an unprotected pdf is never gated and gets no warning`() {
        val plain = File(dir, "plain.pdf")
        PDDocument().use { doc ->
            doc.addPage(PDPage())
            doc.addPage(PDPage())
            doc.save(plain)
        }
        val result = tool.edit(
            operation = "extract_pages",
            input = plain.path,
            output = File(dir, "p1.pdf").path,
            pages = "1"
        )
        assertTrue("got: ${result.message}", result.success)
        assertFalse("no protection was lost, so say nothing", result.message.contains("NOT"))
    }
}
