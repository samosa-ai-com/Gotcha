package com.gotcha.tools

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `pull_from_termux`'s `TermuxFileBridge`. The live transfer (a real `cp` through `/sdcard`, or a
 * loopback `python3` send) needs a real Termux install and is verified by hand — what is covered
 * here is everything that decides the outcome without Termux: `~`/`$HOME` expansion, the exact
 * `python3` sender command and program, and the shared-storage-vs-loopback selection.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30, 34])
class TermuxFileBridgeTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val bridge = TermuxFileBridge(context)

    @Test
    fun `a leading tilde expands to termux home`() {
        val home = bridge.termuxHomeForTest()
        assertEquals(home, bridge.expandHome("~"))
        assertEquals("$home/gotcha_guide.mp4", bridge.expandHome("~/gotcha_guide.mp4"))
    }

    @Test
    fun `a dollar HOME expands to termux home`() {
        val home = bridge.termuxHomeForTest()
        assertEquals("$home/out.mp4", bridge.expandHome("\$HOME/out.mp4"))
    }

    @Test
    fun `an absolute termux path is left alone`() {
        val absolute = "/data/data/com.termux/files/home/gotcha_guide.mp4"
        assertEquals(absolute, bridge.expandHome(absolute))
    }

    @Test
    fun `the sender command passes port nonce and path as argv and wake-locks`() {
        val command = bridge.senderCommand(8192, "deadbeef", "/data/.../home/gotcha_guide.mp4")

        assertTrue("must wake-lock the sender", command.contains("termux-wake-lock"))
        assertTrue("must stream python from stdin", command.contains("python3 - 8192 deadbeef"))
        assertTrue(
            "the path must be shell-quoted, not interpolated",
            command.contains("'/data/.../home/gotcha_guide.mp4'")
        )
    }

    @Test
    fun `a hostile path with quotes is shell-quoted rather than breaking the command`() {
        val command = bridge.senderCommand(1, "a", "/data/.../home/it's.mp4")
        assertTrue(command.contains("'/data/.../home/it'\\''s.mp4'"))
    }

    @Test
    fun `the sender program reads its inputs from argv and streams in chunks`() {
        val script = bridge.senderScript()

        assertTrue("must take the port from argv", script.contains("port = int(sys.argv[1])"))
        assertTrue("must take the nonce from argv", script.contains("nonce = sys.argv[2]"))
        assertTrue("must take the path from argv", script.contains("path = sys.argv[3]"))
        assertTrue("must connect to the loopback port", script.contains("s.connect(('127.0.0.1', port))"))
        assertTrue("must present the single-use nonce", script.contains("s.sendall(nonce.encode())"))
        assertTrue("must wait for the READY ack before sending", script.contains("ack != b'READY\\n'"))
        assertTrue("must stream in chunks rather than read the whole file", script.contains("f.read(65536)"))
        assertTrue("must close on a refused handshake", script.contains("sys.exit(1)"))
    }

    @Test
    fun `shared storage is used only when linked and the destination is reachable from termux`() {
        val reachable = "/storage/emulated/0/Download/out.mp4"
        val private = "/data/data/com.gotcha/files/chats/x/out.mp4"
        assertTrue(bridge.usesSharedStorage(true, reachable))
        assertFalse(
            "an app-sandbox path is not reachable from Termux",
            bridge.usesSharedStorage(true, private)
        )
        assertFalse(
            "an unlinked bridge must not be used even for reachable destinations",
            bridge.usesSharedStorage(false, reachable)
        )
        assertFalse(bridge.usesSharedStorage(false, private))
    }

    @Test
    fun `the storage probe only reports linked on an explicit marker`() {
        assertTrue(bridge.linksStorage("exit code: 0\nstdout:\nSTORAGE_LINKED"))
        assertFalse(bridge.linksStorage("exit code: 0\nstdout:\nSTORAGE_UNLINKED"))
        assertFalse("a probe that never ran must not read as linked", bridge.linksStorage("Termux is not installed"))
    }
}
