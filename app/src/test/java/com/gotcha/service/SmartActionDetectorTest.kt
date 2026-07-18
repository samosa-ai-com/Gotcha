package com.gotcha.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartActionDetectorTest {

    // ---- Proactive detect() ----

    @Test
    fun `detects punctuated phone number and encodes a dial action`() {
        val action = SmartActionDetector.detect("Call me at (415) 555-2671 tomorrow")
        assertNotNull(action)
        assertTrue(action!!.label.contains("Dial"))
        assertTrue(SmartActionDetector.isNativeAction(action.prompt))
        val (type, payload) = SmartActionDetector.decode(action.prompt)!!
        assertEquals(SmartActionDetector.TYPE_DIAL, type)
        assertTrue(payload.contains("555"))
    }

    @Test
    fun `detects dashed phone number`() {
        val action = SmartActionDetector.detect("Reach us on 415-555-2671")
        assertNotNull(action)
        assertEquals(SmartActionDetector.TYPE_DIAL, SmartActionDetector.decode(action!!.prompt)!!.first)
    }

    @Test
    fun `detects international phone number`() {
        val action = SmartActionDetector.detect("Ring +44 20 7946 0958 for support")
        assertNotNull(action)
        assertEquals(SmartActionDetector.TYPE_DIAL, SmartActionDetector.decode(action!!.prompt)!!.first)
    }

    @Test
    fun `does not treat a bare digit run as a phone number`() {
        // Order id / tracking number — no separators, no parens, no +.
        assertNull(SmartActionDetector.detect("Your order 4155552671 has shipped"))
    }

    @Test
    fun `does not match digits embedded in a longer number`() {
        assertNull(SmartActionDetector.detect("SKU 004155552671003 in stock"))
    }

    @Test
    fun `detects street address and encodes a navigate action`() {
        val action = SmartActionDetector.detect("Meet at 1600 Amphitheatre Parkway Way for lunch")
        assertNotNull(action)
        assertTrue(action!!.label.contains("Navigate"))
        val (type, _) = SmartActionDetector.decode(action.prompt)!!
        assertEquals(SmartActionDetector.TYPE_NAVIGATE, type)
    }

    @Test
    fun `proactive detect never offers currency`() {
        assertNull(SmartActionDetector.detect("The jacket costs €89.99 in Berlin"))
    }

    @Test
    fun `proactive detect never offers calendar`() {
        assertNull(SmartActionDetector.detect("Let's schedule a meeting on Monday"))
    }

    @Test
    fun `chat reply only fires when allowChat is set`() {
        val text = "Hey, are you free later?"
        assertNull(SmartActionDetector.detect(text, allowChat = false))
        val action = SmartActionDetector.detect(text, allowChat = true)
        assertNotNull(action)
        assertTrue(action!!.label.contains("Message"))
    }

    @Test
    fun `plain prose returns no action`() {
        assertNull(SmartActionDetector.detect("The quick brown fox jumps over the lazy dog."))
    }

    @Test
    fun `blank input returns no action`() {
        assertNull(SmartActionDetector.detect("   "))
    }

    // ---- Lens-mode detectContextual() ----

    @Test
    fun `contextual detection finds currency`() {
        val actions = SmartActionDetector.detectContextual("The jacket costs €89.99")
        assertTrue(actions.any { it.label.contains("Convert") })
        // Currency conversion is an LLM query, not a native intent.
        val currency = actions.first { it.label.contains("Convert") }
        assertFalse(SmartActionDetector.isNativeAction(currency.prompt))
    }

    @Test
    fun `contextual detection finds calendar event`() {
        val actions = SmartActionDetector.detectContextual("Team meeting on Monday")
        val cal = actions.firstOrNull { it.label.contains("calendar") }
        assertNotNull(cal)
        assertEquals(SmartActionDetector.TYPE_CALENDAR, SmartActionDetector.decode(cal!!.prompt)!!.first)
    }

    @Test
    fun `contextual detection returns empty for plain prose`() {
        assertTrue(SmartActionDetector.detectContextual("just some words here").isEmpty())
    }

    @Test
    fun `decode returns null for non-native prompts`() {
        assertNull(SmartActionDetector.decode("Just a normal prompt"))
    }
}
