package com.gotcha.terminal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.termux.view.TerminalView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Ctrl and Alt latches of the extra-keys bar, and pinch-to-zoom. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TerminalViewClientTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val view = TerminalView(context, null)
    private val sizes = mutableListOf<Int>()
    private val client = GotchaTerminalViewClient(view, 40, 20..80) { sizes += it }

    @Test
    fun `Ctrl is armed by the button and used up by the next key`() {
        assertFalse(client.readControlKey())
        client.toggleCtrl()
        assertTrue(client.ctrlArmed)
        assertTrue(client.readControlKey())
        assertFalse("one key only", client.readControlKey())
        assertFalse(client.ctrlArmed)
    }

    @Test
    fun `Alt behaves the same and independently`() {
        client.toggleAlt()
        assertFalse(client.readControlKey())
        assertTrue(client.readAltKey())
        assertFalse(client.readAltKey())
    }

    @Test
    fun `tapping an armed button disarms it`() {
        client.toggleCtrl()
        client.toggleCtrl()
        assertFalse(client.readControlKey())
    }

    @Test
    fun `a small pinch changes nothing and keeps accumulating`() {
        assertEquals(1.05f, client.onScale(1.05f), 0f)
        assertTrue(sizes.isEmpty())
    }

    @Test
    fun `pinching out grows the text and pinching in shrinks it, within the limits`() {
        assertEquals(1.0f, client.onScale(1.5f), 0f)
        assertEquals(listOf(44), sizes)
        client.onScale(0.5f)
        assertEquals(listOf(44, 40), sizes)
        repeat(20) { client.onScale(1.5f) }
        assertEquals(80, sizes.last())
        repeat(40) { client.onScale(0.5f) }
        assertEquals(20, sizes.last())
    }

    @Test
    fun `back is not turned into Escape and input is character based`() {
        assertFalse(client.shouldBackButtonBeMappedToEscape())
        assertTrue(client.shouldEnforceCharBasedInput())
    }
}
