package com.gotcha.terminal

import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CopyOnWriteArrayList

/** Gotcha's [TerminalSession]: output into the emulator, keystrokes and sizes out, and the end. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TerminalSessionTest {

    private class Host : TerminalSession.Host {
        val events = CopyOnWriteArrayList<String>()
        override fun write(data: ByteArray, offset: Int, count: Int) {
            events += "write:" + String(data, offset, count, Charsets.ISO_8859_1)
        }
        override fun resize(rows: Int, columns: Int) {
            events += "resize:$rows,$columns"
        }
        override fun hangUp() {
            events += "hangUp"
        }
    }

    private class Client : TerminalSessionClient {
        var changed = 0
        var finished = 0
        var titles = 0
        override fun onTextChanged(changedSession: TerminalSession) { changed++ }
        override fun onTitleChanged(changedSession: TerminalSession) { titles++ }
        override fun onSessionFinished(finishedSession: TerminalSession) { finished++ }
        override fun onCopyTextToClipboard(session: TerminalSession, text: String?) = Unit
        override fun onPasteTextFromClipboard(session: TerminalSession?) = Unit
        override fun onBell(session: TerminalSession) = Unit
        override fun onColorsChanged(session: TerminalSession) = Unit
        override fun onTerminalCursorStateChange(state: Boolean) = Unit
        override fun getTerminalCursorStyle(): Int? = null
        override fun logError(tag: String?, message: String?) = Unit
        override fun logWarn(tag: String?, message: String?) = Unit
        override fun logInfo(tag: String?, message: String?) = Unit
        override fun logDebug(tag: String?, message: String?) = Unit
        override fun logVerbose(tag: String?, message: String?) = Unit
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) = Unit
        override fun logStackTrace(tag: String?, e: Exception?) = Unit
    }

    private val host = Host()
    private val client = Client()
    private val shellOut = PipedOutputStream()
    private val session = TerminalSession(PipedInputStream(shellOut, 1 shl 20), host, client, 2000)

    @After
    fun tearDown() {
        session.finishIfRunning()
        runCatching { shellOut.close() }
    }

    /** Runs the main looper until [done] holds, since the reader thread posts to it. */
    private fun await(what: String, done: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!done()) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            check(System.nanoTime() < deadline) { "timed out waiting for $what" }
            Thread.sleep(5)
        }
    }

    private fun screen(): String = session.emulator.screen.transcriptText

    private fun start(columns: Int = 80, rows: Int = 24) = session.updateSize(columns, rows, 10, 20)

    @Test
    fun `what the shell prints reaches the screen`() {
        start()
        shellOut.write("hello world\r\n".toByteArray())
        await("output") { "hello world" in screen() }
        assertTrue(client.changed > 0)
    }

    @Test
    fun `the first size is passed on, because the session was opened at a guess`() {
        start(columns = 100, rows = 30)
        await("the resize") { "resize:30,100" in host.events }
    }

    @Test
    fun `keystrokes and resizes reach the host in the order they were made`() {
        start()
        session.write("ls\r".toByteArray(), 0, 3)
        session.updateSize(132, 40, 10, 20)
        session.write("q".toByteArray(), 0, 1)
        await("all events") { host.events.size >= 4 }
        assertEquals(listOf("resize:24,80", "write:ls\r", "resize:40,132", "write:q"), host.events.toList())
    }

    @Test
    fun `bytes passed to write are copied, so the caller can reuse its buffer`() {
        start()
        val buffer = "a".toByteArray()
        session.write(buffer, 0, 1)
        buffer[0] = 'b'.code.toByte()
        session.write(buffer, 0, 1)
        await("writes") { host.events.count { it.startsWith("write:") } == 2 }
        assertEquals(listOf("write:a", "write:b"), host.events.filter { it.startsWith("write:") })
    }

    @Test
    fun `output printed just before the shell ends is not lost`() {
        start()
        shellOut.write("last words\r\n".toByteArray())
        shellOut.close()
        await("the end") { client.finished > 0 }
        assertTrue(screen(), "last words" in screen())
        assertFalse(session.isRunning)
        assertEquals(1, client.finished)
    }

    @Test
    fun `a flood of output is all shown and does not block the reader`() {
        start()
        val line = "0123456789abcdefghijklmnopqrstuvwxyz0123456789abcdefghijklmnopqrstuvwxyz\r\n"
        val writer = Thread {
            repeat(3000) { shellOut.write(line.toByteArray()) }
            shellOut.write("THE END\r\n".toByteArray())
        }
        writer.start()
        await("the last line") { "THE END" in screen() }
        writer.join(5000)
    }

    @Test
    fun `a code point is written as UTF-8, with an escape in front when asked`() {
        start()
        session.writeCodePoint(false, 0x20AC)
        session.writeCodePoint(true, 'x'.code)
        await("writes") { host.events.count { it.startsWith("write:") } == 2 }
        assertEquals("write:â\u0082¬", host.events.filter { it.startsWith("write:") }[0])
        assertEquals("write:\u001bx", host.events.filter { it.startsWith("write:") }[1])
    }

    @Test
    fun `an impossible code point is refused`() {
        assertThrows(IllegalArgumentException::class.java) { session.writeCodePoint(false, 0xD800) }
        assertThrows(IllegalArgumentException::class.java) { session.writeCodePoint(false, 0x110000) }
    }

    @Test
    fun `finishing hangs the host up once, and later calls neither fail nor reach it`() {
        start()
        session.finishIfRunning()
        session.finishIfRunning()
        session.write("late".toByteArray(), 0, 4)
        session.updateSize(90, 30, 10, 20)
        await("hang up") { "hangUp" in host.events }
        assertEquals(1, host.events.count { it == "hangUp" })
        assertTrue(host.events.none { it == "write:late" })
    }
}
