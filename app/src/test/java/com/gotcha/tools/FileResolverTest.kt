package com.gotcha.tools

import android.content.Context
import android.os.Environment
import androidx.test.core.app.ApplicationProvider
import com.gotcha.testsupport.ShadowExternalStorageManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * [FileResolver] is the permission gate every file tool goes through, so a mistake here
 * is either a crash-on-read or a silent bypass of "All files access".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30, 34], shadows = [ShadowExternalStorageManager::class])
class FileResolverTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val resolver = FileResolver(context)

    // Read lazily in @Before, not in a field initialiser: the getter falls back to
    // GotchaStorage.root(), which needs the Robolectric environment to be up.
    private lateinit var savedWorkingDir: String

    @Before
    fun captureWorkingDir() {
        savedWorkingDir = FileResolver.WORKING_DIR_BASE
    }

    @After
    fun restoreGlobals() {
        FileResolver.WORKING_DIR_BASE = savedWorkingDir
        ShadowExternalStorageManager.resetGranted()
    }

    // ---- sandbox paths need no permission ----

    @Test
    fun `paths inside the app sandbox resolve for read`() {
        val file = File(context.filesDir, "note.txt")
        val result = resolver.resolveForRead(file.absolutePath)

        assertTrue("expected Ok, got $result", result is FileResolver.ResolveResult.Ok)
        assertEquals(file.canonicalPath, (result as FileResolver.ResolveResult.Ok).file.path)
    }

    @Test
    fun `paths inside the app sandbox resolve for write`() {
        val result = resolver.resolveForWrite(File(context.cacheDir, "scratch.bin").absolutePath)
        assertTrue("expected Ok, got $result", result is FileResolver.ResolveResult.Ok)
    }

    @Test
    fun `sandbox paths need no read permission`() {
        assertEquals(null, resolver.checkReadPermission(File(context.filesDir, "x")))
    }

    @Test
    fun `sandbox paths need no write permission`() {
        assertEquals(null, resolver.checkWritePermission(File(context.cacheDir, "x")))
    }

    // ---- shared storage needs All Files Access ----

    @Test
    fun `shared storage read without all-files-access asks for the permission`() {
        val shared = File(Environment.getExternalStorageDirectory(), "Download/report.pdf")
        val result = resolver.resolveForRead(shared.absolutePath)

        assertTrue("expected PermissionNeeded, got $result", result is FileResolver.ResolveResult.PermissionNeeded)
        val toolResult = (result as FileResolver.ResolveResult.PermissionNeeded).result
        assertEquals(ToolResult.ALL_FILES_ACCESS, toolResult.needsPermission)
    }

    @Test
    fun `shared storage write without all-files-access asks for the permission`() {
        val shared = File(Environment.getExternalStorageDirectory(), "Download/out.txt")
        val result = resolver.resolveForWrite(shared.absolutePath)

        assertTrue("expected PermissionNeeded, got $result", result is FileResolver.ResolveResult.PermissionNeeded)
    }

    @Test
    fun `checkReadPermission flags shared storage`() {
        val shared = File(Environment.getExternalStorageDirectory(), "Music/song.mp3")
        assertEquals(ToolResult.ALL_FILES_ACCESS, resolver.checkReadPermission(shared)?.needsPermission)
    }

    @Test
    fun `shared storage read is allowed once all-files-access is granted`() {
        ShadowExternalStorageManager.granted = true

        val shared = File(Environment.getExternalStorageDirectory(), "Download/report.pdf")
        val result = resolver.resolveForRead(shared.absolutePath)

        assertTrue("expected Ok once granted, got $result", result is FileResolver.ResolveResult.Ok)
        assertEquals(null, resolver.checkReadPermission(shared))
    }

    @Test
    fun `shared storage write is allowed once all-files-access is granted`() {
        ShadowExternalStorageManager.granted = true

        val shared = File(Environment.getExternalStorageDirectory(), "Download/out.txt")

        assertTrue(resolver.resolveForWrite(shared.absolutePath) is FileResolver.ResolveResult.Ok)
        assertEquals(null, resolver.checkWritePermission(shared))
    }

    // ---- relative paths and traversal ----

    @Test
    fun `relative paths resolve against the working directory`() {
        FileResolver.WORKING_DIR_BASE = context.filesDir.absolutePath

        val result = resolver.resolveForRead("notes/today.md")

        assertTrue(result is FileResolver.ResolveResult.Ok)
        assertEquals(
            File(context.filesDir, "notes/today.md").canonicalPath,
            (result as FileResolver.ResolveResult.Ok).file.path
        )
    }

    @Test
    fun `dot-dot traversal is canonicalised, not trusted`() {
        FileResolver.WORKING_DIR_BASE = context.filesDir.absolutePath

        // Escaping the sandbox via ".." must be resolved to its real target, so the
        // permission check below sees the actual location rather than the sandbox prefix.
        val canonical = resolver.canonicalPath("../../../../etc/passwd")

        assertTrue("path was not canonicalised: $canonical", !canonical.contains(".."))
    }

    @Test
    fun `absolute paths ignore the working directory`() {
        FileResolver.WORKING_DIR_BASE = context.filesDir.absolutePath
        assertEquals("/tmp/thing", resolver.canonicalPath("/tmp/thing"))
    }

    // ---- formatting ----

    @Test
    fun `formatSize uses the largest fitting unit`() {
        assertEquals("512 B", FileResolver.formatSizeStatic(512))
        assertEquals("2 KB", FileResolver.formatSizeStatic(2048))
        assertEquals("1.5 MB", FileResolver.formatSizeStatic(1024L * 1024 * 3 / 2))
        assertEquals("2.0 GB", FileResolver.formatSizeStatic(1024L * 1024 * 1024 * 2))
    }
}
