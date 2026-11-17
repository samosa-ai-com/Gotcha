package com.gotcha.terminal

import android.content.Context
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.PipedInputStream
import java.io.PipedOutputStream

/**
 * The view as the screen uses it: built, given a session, then laid out. The first layout is where
 * Termux's view needs everything in place, and it crashed the app on a phone before it was built
 * by [newTerminalView].
 */
@RunWith(RobolectricTestRunner::class)
// Native graphics: the view divides by the font's line height, which is 0 with Robolectric's fake text.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class TerminalViewLayoutTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val shellOut = PipedOutputStream()
    private val resizes = mutableListOf<Pair<Int, Int>>()

    private class Client : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) = Unit
        override fun onTitleChanged(changedSession: TerminalSession) = Unit
        override fun onSessionFinished(finishedSession: TerminalSession) = Unit
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

    private val session = TerminalSession(
        PipedInputStream(shellOut),
        object : TerminalSession.Host {
            override fun write(data: ByteArray, offset: Int, count: Int) = Unit
            override fun resize(rows: Int, columns: Int) {
                synchronized(resizes) { resizes += rows to columns }
            }
            override fun hangUp() = Unit
        },
        Client(),
        100
    )

    @After
    fun tearDown() {
        session.finishIfRunning()
        runCatching { shellOut.close() }
    }

    private fun layOut(view: View, width: Int, height: Int) {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, width, height)
    }

    @Test
    fun `a view built by the factory survives its first layout and starts the emulator`() {
        val parts = newTerminalView(context, 40, 20..80) { }
        parts.view.attachSession(session)

        layOut(parts.view, 1080, 1600)

        assertNotNull("the emulator should exist once the view has a size", session.emulator)
        assertTrue(session.emulator.mColumns >= 4 && session.emulator.mRows >= 4)
    }

    @Test
    fun `the first layout tells the host the real size`() {
        val parts = newTerminalView(context, 40, 20..80) { }
        parts.view.attachSession(session)
        layOut(parts.view, 1080, 1600)

        val expected = session.emulator.mRows to session.emulator.mColumns
        val deadline = System.nanoTime() + 5_000_000_000L
        while (synchronized(resizes) { expected !in resizes }) {
            check(System.nanoTime() < deadline) { "host was never told $expected, got $resizes" }
            Thread.sleep(5)
        }
    }

    @Test
    fun `a second layout at another size, as after rotation, resizes the emulator`() {
        val parts = newTerminalView(context, 40, 20..80) { }
        parts.view.attachSession(session)
        layOut(parts.view, 1080, 1600)
        val portraitColumns = session.emulator.mColumns

        layOut(parts.view, 1600, 900)

        assertTrue("more columns when wider", session.emulator.mColumns > portraitColumns)
        assertEquals(session.emulator, parts.view.currentSession.emulator)
    }

    @Test
    fun `a new view can take over a session that is already running, as after rotation`() {
        val first = newTerminalView(context, 40, 20..80) { }
        first.view.attachSession(session)
        layOut(first.view, 1080, 1600)

        val second = newTerminalView(context, 40, 20..80) { }
        second.view.attachSession(session)
        layOut(second.view, 1600, 900)

        assertTrue(session.emulator.mColumns > 0)
    }
}
