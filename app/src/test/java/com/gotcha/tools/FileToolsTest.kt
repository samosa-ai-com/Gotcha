package com.gotcha.tools

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.gotcha.testsupport.ShadowExternalStorageManager
import org.junit.After
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
 * `read_file`, `write_file`, `list_files`, `edit`, `glob` and `grep` against a real
 * temp directory inside the app sandbox (which [FileResolver] lets through without
 * "All files access").
 *
 * These are pure path/content logic with high bug density and low test cost — the
 * batch the plan calls out as the best value in the Robolectric tier.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30, 34], shadows = [ShadowExternalStorageManager::class])
class FileToolsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val fileTool = FileTool(context)
    private val editTool = EditTool(context)
    private val globTool = GlobTool(context)
    private val grepTool = GrepTool(context)

    private lateinit var workDir: File
    private lateinit var savedWorkingDir: String

    @Before
    fun setUp() {
        savedWorkingDir = FileResolver.WORKING_DIR_BASE
        workDir = File(context.filesDir, "filetools-test").apply {
            deleteRecursively()
            mkdirs()
        }
        FileResolver.WORKING_DIR_BASE = workDir.absolutePath
    }

    @After
    fun tearDown() {
        FileResolver.WORKING_DIR_BASE = savedWorkingDir
        workDir.deleteRecursively()
        ShadowExternalStorageManager.resetGranted()
    }

    private fun write(relative: String, content: String): File =
        File(workDir, relative).apply {
            parentFile?.mkdirs()
            writeText(content)
        }

    // ---- read_file ----

    @Test
    fun `read_file returns the file contents`() {
        write("hello.txt", "line one\nline two\nline three\n")

        val result = fileTool.readFile(File(workDir, "hello.txt").absolutePath)

        assertTrue(result.message, result.success)
        assertTrue(result.message, result.message.contains("line one"))
        assertTrue(result.message, result.message.contains("line three"))
    }

    @Test
    fun `read_file honours offset and limit`() {
        write("many.txt", (1..50).joinToString("\n") { "line $it" })

        val result = fileTool.readFile(File(workDir, "many.txt").absolutePath, offset = 10, limit = 3)

        assertTrue(result.message, result.success)
        assertTrue("expected the offset line, got:\n${result.message}", result.message.contains("line 11"))
        assertFalse("limit was not applied:\n${result.message}", result.message.contains("line 20"))
    }

    @Test
    fun `read_file on a missing path fails with a usable message`() {
        val result = fileTool.readFile(File(workDir, "nope.txt").absolutePath)

        assertFalse(result.success)
        assertTrue(result.message, result.message.contains("nope.txt"))
    }

    // ---- write_file ----

    @Test
    fun `write_file creates the file and any missing parent directories`() {
        val target = File(workDir, "nested/deeper/out.txt")

        val result = fileTool.writeFile(target.absolutePath, "written")

        assertTrue(result.message, result.success)
        assertEquals("written", target.readText())
    }

    @Test
    fun `write_file append adds to the existing contents`() {
        val target = write("log.txt", "first\n")

        assertTrue(fileTool.writeFile(target.absolutePath, "second\n", append = true).success)

        assertEquals("first\nsecond\n", target.readText())
    }

    @Test
    fun `write_file without append overwrites`() {
        val target = write("log.txt", "first\n")

        fileTool.writeFile(target.absolutePath, "replaced")

        assertEquals("replaced", target.readText())
    }

    // ---- list_files ----

    @Test
    fun `list_files lists the immediate children`() {
        write("a.txt", "a")
        write("b.txt", "b")
        write("sub/c.txt", "c")

        val result = fileTool.listFiles(workDir.absolutePath)

        assertTrue(result.message, result.success)
        assertTrue(result.message, result.message.contains("a.txt"))
        assertTrue(result.message, result.message.contains("b.txt"))
        assertFalse("non-recursive listing descended:\n${result.message}", result.message.contains("c.txt"))
    }

    @Test
    fun `list_files recursive descends into subdirectories`() {
        write("sub/deep/c.txt", "c")

        val result = fileTool.listFiles(workDir.absolutePath, recursive = true)

        assertTrue(result.message, result.success)
        assertTrue(result.message, result.message.contains("c.txt"))
    }

    // ---- edit ----

    @Test
    fun `edit replaces a unique occurrence`() {
        val target = write("code.txt", "val timeout = 30\nval retries = 3\n")

        val result = editTool.edit(target.absolutePath, "timeout = 30", "timeout = 60", replaceAll = false)

        assertTrue(result.message, result.success)
        assertEquals("val timeout = 60\nval retries = 3\n", target.readText())
    }

    /**
     * Pins the *documented* behaviour: the `edit` schema says replaceAll replaces "all
     * occurrences instead of just the first", so a non-unique oldString replaces the first
     * match rather than failing.
     *
     * Note this leaves `EditTool`'s `count > 1 && !replaceAll` ambiguity guard unreachable:
     * on that path `count` is computed as `if (content.contains(oldString)) 1 else 0`, so it
     * can never exceed 1. Whether to make that guard live (safer, but a behaviour change for
     * every caller) or delete it (matching the schema) is a product decision, deliberately
     * not taken here.
     */
    @Test
    fun `edit without replaceAll replaces only the first occurrence`() {
        val target = write("code.txt", "x = 1\nx = 1\n")

        val result = editTool.edit(target.absolutePath, "x = 1", "x = 2", replaceAll = false)

        assertTrue(result.message, result.success)
        assertEquals("x = 2\nx = 1\n", target.readText())
    }

    @Test
    fun `edit with replaceAll replaces every occurrence`() {
        val target = write("code.txt", "x = 1\nx = 1\n")

        val result = editTool.edit(target.absolutePath, "x = 1", "x = 2", replaceAll = true)

        assertTrue(result.message, result.success)
        assertEquals("x = 2\nx = 2\n", target.readText())
    }

    @Test
    fun `edit fails when the text is not present`() {
        val target = write("code.txt", "nothing to see")

        val result = editTool.edit(target.absolutePath, "absent", "present", replaceAll = false)

        assertFalse(result.success)
        assertEquals("nothing to see", target.readText())
    }

    @Test
    fun `edit rejects a blank oldString`() {
        val target = write("code.txt", "content")

        assertFalse(editTool.edit(target.absolutePath, "   ", "x", replaceAll = false).success)
    }

    @Test
    fun `edit rejects a no-op replacement`() {
        val target = write("code.txt", "content")

        assertFalse(editTool.edit(target.absolutePath, "content", "content", replaceAll = false).success)
    }

    // ---- glob ----

    @Test
    fun `glob matches a suffix pattern recursively`() {
        write("a.kt", "")
        write("b.txt", "")
        write("sub/c.kt", "")

        val result = globTool.glob(workDir.absolutePath, "**/*.kt")

        assertTrue(result.message, result.success)
        assertTrue(result.message, result.message.contains("a.kt"))
        assertTrue(result.message, result.message.contains("c.kt"))
        assertFalse("matched a non-.kt file:\n${result.message}", result.message.contains("b.txt"))
    }

    @Test
    fun `glob single star does not cross directory boundaries`() {
        write("top.kt", "")
        write("sub/nested.kt", "")

        val result = globTool.glob(workDir.absolutePath, "*.kt")

        assertTrue(result.message, result.success)
        assertTrue(result.message, result.message.contains("top.kt"))
        assertFalse("'*' crossed a directory boundary:\n${result.message}", result.message.contains("nested.kt"))
    }

    @Test
    fun `glob supports brace alternation`() {
        write("a.kt", "")
        write("b.java", "")
        write("c.md", "")

        val result = globTool.glob(workDir.absolutePath, "*.{kt,java}")

        assertTrue(result.message, result.success)
        assertTrue(result.message, result.message.contains("a.kt"))
        assertTrue(result.message, result.message.contains("b.java"))
        assertFalse(result.message, result.message.contains("c.md"))
    }

    @Test
    fun `glob reports no matches rather than failing`() {
        write("a.kt", "")

        val result = globTool.glob(workDir.absolutePath, "*.rs")

        assertTrue("an empty result set is not an error: ${result.message}", result.success)
        assertTrue(result.message, result.message.contains("No files matching"))
    }

    @Test
    fun `glob rejects a blank pattern`() {
        assertFalse(globTool.glob(workDir.absolutePath, "  ").success)
    }

    // ---- grep ----

    @Test
    fun `grep finds matching lines and names the file`() {
        write("notes.txt", "alpha\nbeta\ngamma\n")

        val result = grepTool.grep(workDir.absolutePath, "beta", include = null)

        assertTrue(result.message, result.success)
        assertTrue(result.message, result.message.contains("beta"))
        assertTrue(result.message, result.message.contains("notes.txt"))
    }

    @Test
    fun `grep is case-insensitive`() {
        write("notes.txt", "Alpha\n")

        val result = grepTool.grep(workDir.absolutePath, "alpha", include = null)

        assertTrue(result.message, result.success)
        assertTrue(result.message, result.message.contains("Alpha"))
    }

    @Test
    fun `grep honours the include filter`() {
        write("keep.kt", "needle\n")
        write("skip.txt", "needle\n")

        val result = grepTool.grep(workDir.absolutePath, "needle", include = "*.kt")

        assertTrue(result.message, result.success)
        assertTrue(result.message, result.message.contains("keep.kt"))
        assertFalse("include filter was ignored:\n${result.message}", result.message.contains("skip.txt"))
    }

    @Test
    fun `grep supports regex`() {
        write("nums.txt", "id=42\nid=abc\n")

        val result = grepTool.grep(workDir.absolutePath, "id=[0-9]+", include = null)

        assertTrue(result.message, result.success)
        assertTrue(result.message, result.message.contains("id=42"))
        assertFalse(result.message, result.message.contains("id=abc"))
    }

    @Test
    fun `grep rejects an invalid regex instead of throwing`() {
        write("a.txt", "x")

        val result = grepTool.grep(workDir.absolutePath, "([unclosed", include = null)

        assertFalse(result.success)
        assertTrue(result.message, result.message.contains("Invalid regex"))
    }

    @Test
    fun `grep rejects a blank pattern`() {
        assertFalse(grepTool.grep(workDir.absolutePath, "", include = null).success)
    }

    // ---- permission gating applies to the tools, not just the resolver ----

    @Test
    fun `file tools refuse shared storage without all-files-access`() {
        val shared = File(android.os.Environment.getExternalStorageDirectory(), "Download/x.txt")

        val read = fileTool.readFile(shared.absolutePath)

        assertFalse("shared storage must be gated: ${read.message}", read.success)
        assertEquals(ToolResult.ALL_FILES_ACCESS, read.needsPermission)
    }
}
