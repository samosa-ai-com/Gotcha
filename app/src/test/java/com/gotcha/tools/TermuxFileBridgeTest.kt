package com.gotcha.tools

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files

/**
 * `pull_from_termux`'s `TermuxFileBridge`. The live transfer (a real `cp` through `/sdcard`, or a
 * loopback `python3` send) needs a real Termux install and is verified by hand — what is covered
 * here is everything that decides the outcome without Termux: `~`/`$HOME` expansion, the exact
 * `python3` sender command and program, the shared-storage-vs-loopback selection, and the socket
 * machinery of the loopback receiver driven over a real `127.0.0.1` `ServerSocket`/`Socket` pair
 * (nonce handshake, wrong-nonce refusal, size verification, and the no-partial-file guarantee).
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
    fun `files at or above the shared-storage size threshold route to loopback`() {
        val reachable = "/storage/emulated/0/Download/out.mp4"
        assertTrue(
            "a small file uses the linked bridge",
            bridge.usesSharedStorage(true, reachable, sourceBytes = 64L * 1024 * 1024)
        )
        assertFalse(
            "a file at the 100MB FUSE threshold routes to loopback",
            bridge.usesSharedStorage(true, reachable, sourceBytes = TermuxFileBridge.SHARED_STORAGE_MAX_BYTES)
        )
        assertFalse(
            "a file above the 100MB FUSE threshold routes to loopback",
            bridge.usesSharedStorage(true, reachable, sourceBytes = 1024L * 1024 * 1024)
        )
        assertFalse(
            "an unknown size still refuses an unlinked bridge",
            bridge.usesSharedStorage(false, reachable, sourceBytes = null)
        )
    }

    @Test
    fun `the storage probe only reports linked on an explicit marker`() {
        assertTrue(bridge.linksStorage("exit code: 0\nstdout:\nSTORAGE_LINKED"))
        assertFalse(bridge.linksStorage("exit code: 0\nstdout:\nSTORAGE_UNLINKED"))
        assertFalse("a probe that never ran must not read as linked", bridge.linksStorage("Termux is not installed"))
    }

    // ---- the loopback receiver, driven over a real 127.0.0.1 socket pair ----

    @Test
    fun `a matching sender streams into the temp and the destination stays untouched`() = runBlocking {
        val nonce = "deadbeef"
        val destination = File(tempDir(), "out.bin")
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val receiver = async(Dispatchers.IO) { bridge.receiveInto(server, nonce, destination) }
        val payload = "streamed body with spaces".toByteArray()

        Socket("127.0.0.1", server.localPort).use { socket ->
            socket.getOutputStream().write(nonce.toByteArray())
            socket.getOutputStream().flush()
            assertEquals("READY\n", readExactly(socket.getInputStream(), 6).decodeToString())
            socket.getOutputStream().write("${payload.size}\n".toByteArray())
            socket.getOutputStream().write(payload)
            socket.getOutputStream().flush()
            socket.shutdownOutput()
        }

        val tmp = receiver.await()
        try {
            assertTrue("receiveInto must return a file, not finalise the destination", tmp.exists())
            assertArrayEquals(payload, tmp.readBytes())
            assertFalse("the destination must not exist until copyViaLoopback renames", destination.exists())
        } finally {
            tmp.delete()
            server.close()
        }
    }

    @Test
    fun `a wrong nonce is refused and the receiver keeps listening for the real sender`() = runBlocking {
        val nonce = "deadbeef"
        val destination = File(tempDir(), "out.bin")
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val receiver = async(Dispatchers.IO) { bridge.receiveInto(server, nonce, destination) }
        val port = server.localPort

        Socket("127.0.0.1", port).use { socket ->
            socket.getOutputStream().write("wrongone".toByteArray())
            socket.getOutputStream().flush()
            assertEquals("DENIED\n", readExactly(socket.getInputStream(), 7).decodeToString())
        }

        val payload = "real".toByteArray()
        Socket("127.0.0.1", port).use { socket ->
            socket.getOutputStream().write(nonce.toByteArray())
            socket.getOutputStream().flush()
            assertEquals("READY\n", readExactly(socket.getInputStream(), 6).decodeToString())
            socket.getOutputStream().write("${payload.size}\n".toByteArray())
            socket.getOutputStream().write(payload)
            socket.getOutputStream().flush()
            socket.shutdownOutput()
        }

        val tmp = receiver.await()
        try {
            assertArrayEquals(payload, tmp.readBytes())
        } finally {
            tmp.delete()
            server.close()
        }
    }

    @Test
    fun `a sender that closes early after declaring a larger size fails and leaves nothing behind`() = runBlocking {
        // The regression behind the no-partial-file guarantee: a sender killed mid-transfer (lmkd,
        // force-stop, battery manager) closes the socket early; the receiver must reject the body
        // rather than treat EOF as success, and leave neither a destination nor a stray temp.
        val nonce = "deadbeef"
        val dir = tempDir()
        val destination = File(dir, "out.bin")
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val receiver = async(Dispatchers.IO) { runCatching { bridge.receiveInto(server, nonce, destination) } }

        Socket("127.0.0.1", server.localPort).use { socket ->
            socket.getOutputStream().write(nonce.toByteArray())
            socket.getOutputStream().flush()
            assertEquals("READY\n", readExactly(socket.getInputStream(), 6).decodeToString())
            socket.getOutputStream().write("100000\n".toByteArray())
            socket.getOutputStream().write("partial".toByteArray())
            socket.getOutputStream().flush()
            socket.shutdownOutput()
        }

        val outcome = receiver.await()
        assertTrue("a truncated transfer must fail, not silently succeed: $outcome", outcome.isFailure)
        assertFalse("no corrupt file may land at the destination", destination.exists())
        assertFalse("no stray temp file may linger", dir.listFiles()!!.any { it.name.endsWith(".tmp") })
        server.close()
    }

    @Test
    fun `an empty file with a matching size header transfers cleanly`() = runBlocking {
        val nonce = "deadbeef"
        val destination = File(tempDir(), "empty.bin")
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val receiver = async(Dispatchers.IO) { bridge.receiveInto(server, nonce, destination) }

        Socket("127.0.0.1", server.localPort).use { socket ->
            socket.getOutputStream().write(nonce.toByteArray())
            socket.getOutputStream().flush()
            assertEquals("READY\n", readExactly(socket.getInputStream(), 6).decodeToString())
            socket.getOutputStream().write("0\n".toByteArray())
            socket.getOutputStream().flush()
            socket.shutdownOutput()
        }

        val tmp = receiver.await()
        try {
            assertEquals(0, tmp.length())
            assertFalse(destination.exists())
        } finally {
            tmp.delete()
            server.close()
        }
    }

    private fun tempDir(): File = Files.createTempDirectory("termux-bridge-test").toFile()

    private fun readExactly(input: java.io.InputStream, n: Int): ByteArray {
        val out = ByteArray(n)
        var offset = 0
        while (offset < n) {
            val read = input.read(out, offset, n - offset)
            if (read < 0) throw IOException("connection closed after $offset of $n bytes")
            offset += read
        }
        return out
    }
}
