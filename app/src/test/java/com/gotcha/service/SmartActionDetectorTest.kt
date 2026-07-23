package com.gotcha.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartActionDetectorTest {

    // ---- Multi-Entity detectAll() ----

    @Test
    fun `detectAll detects multiple entities in composite text`() {
        val text = """
            Here is the info:
            Call us at (415) 555-2671 or email support@example.com.
            Visit https://example.com/help or 1600 Amphitheatre Pkwy, Mountain View, CA 94043.
            Your verification OTP code is 849201.
        """.trimIndent()

        val entities = SmartActionDetector.detectAll(text)
        assertTrue(entities.isNotEmpty())

        val types = entities.map { it.type }
        assertTrue(types.contains(EntityType.OTP))
        assertTrue(types.contains(EntityType.PHONE))
        assertTrue(types.contains(EntityType.EMAIL))
        assertTrue(types.contains(EntityType.URL))
        assertTrue(types.contains(EntityType.ADDRESS))

        // OTP has highest priority (100) and should be ranked first
        assertEquals(EntityType.OTP, entities.first().type)
        assertEquals("849201", entities.first().normalizedValue)
    }

    @Test
    fun `detectAll handles multi-line addresses accurately`() {
        val text = """
            Shipping Address:
            1600 Amphitheatre Pkwy
            Building 43
            Mountain View, CA 94043
        """.trimIndent()

        val entities = SmartActionDetector.detectAll(text)
        val address = entities.firstOrNull { it.type == EntityType.ADDRESS }
        assertNotNull(address)
        assertTrue(address!!.normalizedValue.contains("Amphitheatre Pkwy"))
        assertTrue(address.normalizedValue.contains("Mountain View"))
    }

    @Test
    fun `extractUrl cleans trailing punctuation and handles query params correctly`() {
        val url1 = SmartActionDetector.extractUrl("Check this link: (https://example.com/page?id=123&ref=test).")
        assertEquals("https://example.com/page?id=123&ref=test", url1)

        val url2 = SmartActionDetector.extractUrl("Visit www.github.com/repo!")
        assertEquals("https://www.github.com/repo", url2)
    }

    @Test
    fun `detectAll finds INR and dollar currency prices`() {
        val text = "Item price is ₹1250 ($15.00 USD)"
        val entities = SmartActionDetector.detectAll(text)
        val currencies = entities.filter { it.type == EntityType.CURRENCY }
        assertTrue(currencies.isNotEmpty())
    }

    @Test
    fun `detectCurrencies omits conversion when price is already in target currency`() {
        val entitiesUsd = SmartActionDetector.detectAll("Price is $50.00 USD", targetCurrency = "USD")
        val currencyUsd = entitiesUsd.firstOrNull { it.type == EntityType.CURRENCY }
        assertNotNull(currencyUsd)
        assertFalse(
            "Should not offer Convert when currency is already target USD",
            currencyUsd!!.actions.any { it.label.contains("Convert to USD") }
        )

        val entitiesEur = SmartActionDetector.detectAll("Price is €50.00", targetCurrency = "USD")
        val currencyEur = entitiesEur.firstOrNull { it.type == EntityType.CURRENCY }
        assertNotNull(currencyEur)
        assertTrue(
            "Should offer Convert to USD when currency is EUR",
            currencyEur!!.actions.any { it.label.contains("Convert to USD") }
        )
    }

    @Test
    fun `detectAll finds Indian-format comma-grouped currency INR`() {
        val entities = SmartActionDetector.detectAll("Total is INR1,23,456")
        val currency = entities.firstOrNull { it.type == EntityType.CURRENCY }
        assertNotNull("Should detect INR1,23,456 as a currency", currency)
        assertTrue("rawValue should contain the full number", currency!!.rawValue.contains("1,23,456"))
        assertTrue(currency.actions.any { it.label.contains("Convert") })
    }

    @Test
    fun `detectAll finds rupee symbol with Indian comma grouping and decimal`() {
        val entities = SmartActionDetector.detectAll("Price is ₹12,34,567.89")
        val currency = entities.firstOrNull { it.type == EntityType.CURRENCY }
        assertNotNull("Should detect ₹12,34,567.89 as a currency", currency)
        assertTrue(currency!!.rawValue.contains("12,34,567.89"))
        assertTrue(currency.actions.any { it.label.contains("Convert") })
    }

    @Test
    fun `detectAll finds western comma-formatted dollar amount`() {
        val entities = SmartActionDetector.detectAll("Total is $1,234,567.89 USD")
        val currency = entities.firstOrNull { it.type == EntityType.CURRENCY }
        assertNotNull("Should detect $1,234,567.89 USD as a currency", currency)
    }

    @Test
    fun `detectAll finds Rs format with Indian comma grouping`() {
        val entities = SmartActionDetector.detectAll("Cost Rs. 12,34,567")
        val currency = entities.firstOrNull { it.type == EntityType.CURRENCY }
        assertNotNull("Should detect Rs. 12,34,567 as a currency", currency)
        assertTrue(currency!!.rawValue.contains("12,34,567"))
    }

    @Test
    fun `detectAll finds euro symbol with comma-grouped thousands`() {
        val entities = SmartActionDetector.detectAll("Price is €1,234.56", targetCurrency = "USD")
        val currency = entities.firstOrNull { it.type == EntityType.CURRENCY }
        assertNotNull("Should detect €1,234.56 as a currency", currency)
        assertTrue(currency!!.actions.any { it.label.contains("Convert") })
    }

    @Test
    fun `detectAll still finds plain digit currency amounts`() {
        val entities = SmartActionDetector.detectAll("Price is ₹1250")
        val currency = entities.firstOrNull { it.type == EntityType.CURRENCY }
        assertNotNull("Should still detect plain ₹1250", currency)
    }

    @Test
    fun `isTextInLanguage detects whether text matches target language`() {
        assertTrue(SmartActionDetector.isTextInLanguage("Call us at (415) 555-0199 for assistance", "English"))
        assertFalse(SmartActionDetector.isTextInLanguage("Hola amigo ¿cómo estás?", "English"))
        assertTrue(SmartActionDetector.isTextInLanguage("Hola amigo ¿cómo estás?", "Spanish"))
        assertTrue(SmartActionDetector.isTextInLanguage("आप कैसे हैं", "Hindi"))
    }

    @Test
    fun `detectAll finds email addresses and generates compose mail actions`() {
        val entities = SmartActionDetector.detectAll("Contact john.doe@company.org for details")
        val email = entities.firstOrNull { it.type == EntityType.EMAIL }
        assertNotNull(email)
        assertEquals("john.doe@company.org", email!!.normalizedValue)
        assertTrue(email.actions.any { it.actionType == ActionType.NATIVE_COMPOSE_MAIL })
    }

    @Test
    fun `detectAll suppresses URL detection when domain is part of an email address`() {
        val entities = SmartActionDetector.detectAll("Reach out at contact@mywebsite.com")
        val emails = entities.filter { it.type == EntityType.EMAIL }
        val urls = entities.filter { it.type == EntityType.URL }
        assertEquals(1, emails.size)
        assertEquals("contact@mywebsite.com", emails.first().normalizedValue)
        assertTrue("URL list should be empty when domain is inside email", urls.isEmpty())
    }

    @Test
    fun `detectAll finds tracking numbers`() {
        val entities = SmartActionDetector.detectAll("Your package 1Z9999999999999999 is out for delivery")
        val tracking = entities.firstOrNull { it.type == EntityType.TRACKING_NUMBER }
        assertNotNull(tracking)
        assertEquals("1Z9999999999999999", tracking!!.normalizedValue)
        assertTrue(tracking.actions.any { it.label.contains("UPS") })
    }

    // ---- Legacy / Backward Compatibility detect() ----

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
        assertTrue(action!!.label.contains("Draft reply"))
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
        val currency = actions.first { it.label.contains("Convert") }
        // Currency conversion now uses the native TYPE_CONVERT action (not an LLM prompt)
        assertTrue(SmartActionDetector.isNativeAction(currency.prompt))
        assertEquals(SmartActionDetector.TYPE_CONVERT, SmartActionDetector.decode(currency.prompt)!!.first)
    }

    @Test
    fun `contextual detection finds calendar event`() {
        val actions = SmartActionDetector.detectContextual("Team meeting on Monday")
        val cal = actions.firstOrNull { it.label.contains("calendar") }
        assertNotNull(cal)
        assertEquals(SmartActionDetector.TYPE_CALENDAR, SmartActionDetector.decode(cal!!.prompt)!!.first)
    }

    @Test
    fun `contextual detection finds month-day time event`() {
        val actions = SmartActionDetector.detectContextual("Flight on July 20 at 9:10 a.m.")
        val cal = actions.firstOrNull { it.label.contains("calendar") }
        assertNotNull(cal)
        assertEquals(SmartActionDetector.TYPE_CALENDAR, SmartActionDetector.decode(cal!!.prompt)!!.first)
    }

    @Test
    fun `contextual detection finds address with city and zip`() {
        val actions = SmartActionDetector.detectContextual("Ship to 350 5th Ave, New York, NY 10118")
        val addr = actions.firstOrNull { it.label.contains("Navigate") }
        assertNotNull(addr)
        assertEquals(SmartActionDetector.TYPE_NAVIGATE, SmartActionDetector.decode(addr!!.prompt)!!.first)
    }

    @Test
    fun `contextual detection returns empty for plain prose`() {
        assertTrue(SmartActionDetector.detectContextual("just some words here").isEmpty())
    }

    // ---- Snippets in labels ----

    @Test
    fun `snippet truncates long values with an ellipsis`() {
        assertEquals("hello", SmartActionDetector.snippet("hello", 10))
        assertEquals("hello…", SmartActionDetector.snippet("hello world", 5))
        assertEquals("a b c", SmartActionDetector.snippet("a   b\nc", 10))
    }

    @Test
    fun `fetch action label shows a url snippet`() {
        val action = SmartActionDetector.fetchAction("https://www.example.com/very/long/path/here")
        assertTrue(action.label.contains("example.com"))
        assertEquals(
            "https://www.example.com/very/long/path/here",
            SmartActionDetector.decode(action.prompt)!!.second
        )
    }

    @Test
    fun `decode returns null for non-native prompts`() {
        assertNull(SmartActionDetector.decode("Just a normal prompt"))
    }
}
