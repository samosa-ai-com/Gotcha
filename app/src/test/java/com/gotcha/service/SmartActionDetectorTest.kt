package com.gotcha.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartActionDetectorTest {

    @Test
    fun `detects phone number and encodes a dial action`() {
        val action = SmartActionDetector.detect("Call me at (415) 555-2671 tomorrow")
        assertNotNull(action)
        assertTrue(action!!.label.contains("Phone"))
        assertTrue(SmartActionDetector.isNativeAction(action.prompt))
        val (type, payload) = SmartActionDetector.decode(action.prompt)!!
        assertEquals(SmartActionDetector.TYPE_DIAL, type)
        assertTrue(payload.contains("555"))
    }

    @Test
    fun `detects street address and encodes a navigate action`() {
        val action = SmartActionDetector.detect("Meet at 1600 Amphitheatre Parkway Way for lunch")
        assertNotNull(action)
        assertTrue(action!!.label.contains("Address"))
        val (type, _) = SmartActionDetector.decode(action.prompt)!!
        assertEquals(SmartActionDetector.TYPE_NAVIGATE, type)
    }

    @Test
    fun `detects foreign currency and returns a plain LLM prompt`() {
        val action = SmartActionDetector.detect("The jacket costs €89.99 in Berlin")
        assertNotNull(action)
        assertTrue(action!!.label.contains("Currency"))
        // Currency conversion is an LLM query, not a native intent.
        assertTrue(!SmartActionDetector.isNativeAction(action.prompt))
    }

    @Test
    fun `detects calendar event and encodes a calendar action`() {
        val action = SmartActionDetector.detect("Let's schedule a meeting on Monday")
        assertNotNull(action)
        assertTrue(action!!.label.contains("Event"))
        val (type, _) = SmartActionDetector.decode(action.prompt)!!
        assertEquals(SmartActionDetector.TYPE_CALENDAR, type)
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

    @Test
    fun `decode returns null for non-native prompts`() {
        assertNull(SmartActionDetector.decode("Just a normal prompt"))
    }
}
