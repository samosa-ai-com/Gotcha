package com.gotcha.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

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
    fun `isTextInLanguage no longer always reports Italian and Portuguese as not in language`() {
        // Issue #42 P0-7: these previously always fell through to else -> false,
        // so the assistive ball permanently offered a false "Translate" prompt.
        assertTrue(SmartActionDetector.isTextInLanguage("Però sto bene, grazie mille", "Italian"))
        assertTrue(SmartActionDetector.isTextInLanguage("Il gatto è nero", "Italian"))
        assertTrue(SmartActionDetector.isTextInLanguage("Como você está hoje?", "Portuguese"))
        assertTrue(SmartActionDetector.isTextInLanguage("Não sei o que fazer com isso", "Portuguese"))
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

    @Test
    fun `qr code and barcode entities have highest priority over OTP and phone`() {
        val text = "Your OTP is 123456. Connect to WIFI:S:HomeNet;P:secret123;T:WPA;;"
        val entities = SmartActionDetector.detectAll(text)
        assertTrue(entities.isNotEmpty())
        assertEquals(EntityType.QR_CODE, entities.first().type)
        assertTrue(entities.first().normalizedValue.contains("HomeNet"))
    }

    // ---- Tense: a past date is a timestamp, not an event ----

    @Test
    fun `past dates are not calendar events`() {
        val entities = SmartActionDetector.detectAll(
            "Rebase #53 by DevUser2 was merged Jul 26, 2026",
            now = NOON_AUG_1
        )
        assertTrue(
            "A merge timestamp is not something to put on a calendar",
            entities.none { it.type == EntityType.CALENDAR }
        )
    }

    @Test
    fun `future dates are still calendar events`() {
        val entities = SmartActionDetector.detectAll(
            "Design review Aug 14, 2026",
            now = NOON_AUG_1
        )
        assertTrue(entities.any { it.type == EntityType.CALENDAR })
    }

    @Test
    fun `a date without a year resolves to the next occurrence, never the past`() {
        val today = LocalDate.of(2026, 12, 20)
        assertEquals(LocalDate.of(2027, 1, 4), SmartActionDetector.eventDateOf("Jan 4", today))
        assertEquals(LocalDate.of(2026, 12, 25), SmartActionDetector.eventDateOf("Dec 25", today))
    }

    @Test
    fun `weekday and relative names resolve forwards`() {
        val wednesday = LocalDate.of(2026, 8, 5)
        assertEquals(wednesday, SmartActionDetector.eventDateOf("today", wednesday))
        assertEquals(wednesday.plusDays(1), SmartActionDetector.eventDateOf("tomorrow", wednesday))
        // The coming Monday, not the one just gone.
        assertEquals(LocalDate.of(2026, 8, 10), SmartActionDetector.eventDateOf("Monday", wednesday))
    }

    @Test
    fun `an event word outranks a bare time`() {
        val worded = SmartActionDetector.detectAll("Dinner tomorrow", now = NOON_AUG_1)
            .first { it.type == EntityType.CALENDAR }
        val bare = SmartActionDetector.detectAll("Doors open 8 pm", now = NOON_AUG_1)
            .first { it.type == EntityType.CALENDAR }
        assertTrue(bare.confidence < worded.confidence)
    }

    @Test
    fun `a timestamp from earlier today is still a timestamp`() {
        // The gap the first pass left: "merged 10 hours ago" resolves to *today*,
        // which is not before today, so a date-only check let it through.
        val entities = SmartActionDetector.detectAll(
            "rebase #64 by DevUser2 was merged Aug 1, 2026, 9:14 a.m.",
            now = NOON_AUG_1
        )
        assertTrue(
            "A merge from this morning is not an event this afternoon",
            entities.none { it.type == EntityType.CALENDAR }
        )
    }

    @Test
    fun `an event later today survives`() {
        val entities = SmartActionDetector.detectAll("Dinner today at 8 pm", now = NOON_AUG_1)
        assertTrue(entities.any { it.type == EntityType.CALENDAR })
    }

    @Test
    fun `a day with no clock time stays eligible all day`() {
        // "lunch today" is a real event at 9am and at 9pm — with no time given
        // there is nothing to compare against, so the day is the granularity.
        val entities = SmartActionDetector.detectAll(
            "Lunch today",
            now = LocalDate.of(2026, 8, 1).atTime(23, 30)
        )
        assertTrue(entities.any { it.type == EntityType.CALENDAR })
    }

    // ---- Annotation selection ----

    /** The screen from the bug report: a PR list whose every row carries a merge timestamp. */
    private val pullRequestList = """
        Open: github.com/<org>/Gotcha
        1 Open 30 Closed
        Rebase #53 by DevUser2 was merged Jul 26, 2026
        Feature test coverage manifest #52 by DevUser was merged Jul 26, 2026
        Merging dev #51 by DevUser was merged Jul 26, 2026
        Storage overhaul #50 by DevUser was merged Jul 26, 2026
        Add connector framework #35 by DevUser was merged Jul 25, 2026
        feat(audio/vision) #34 by DevUser was merged Jul 25, 2026
        Onboarding #33 by DevUser was merged Jul 23, 2026
    """.trimIndent()

    @Test
    fun `a list of merge timestamps produces no calendar annotations`() {
        val entities = SmartActionDetector.detectAll(pullRequestList, now = NOON_AUG_1)
        val selected = SmartActionDetector.selectForAnnotation(entities)
        assertTrue(
            "Merge timestamps should not be annotated at all",
            selected.none { it.entity.type == EntityType.CALENDAR }
        )
        assertTrue("The repo link is what is worth surfacing here", selected.isNotEmpty())
        assertEquals(EntityType.URL, selected.first().entity.type)
    }

    @Test
    fun `annotations are capped however busy the screen is`() {
        val entities = SmartActionDetector.detectAll(pullRequestList, now = NOON_AUG_1)
        val selected = SmartActionDetector.selectForAnnotation(entities)
        assertTrue(
            "Expected at most ${SmartActionDetector.MAX_ANNOTATIONS}, got ${selected.size}",
            selected.size <= SmartActionDetector.MAX_ANNOTATIONS
        )
    }

    @Test
    fun `a repeated type collapses to a single grouped annotation`() {
        val catalogue = (1..12).joinToString("\n") { "Item $it — €${it * 10}.00" }
        val entities = SmartActionDetector.detectAll(catalogue)
        val prices = entities.filter { it.type == EntityType.CURRENCY }
        assertTrue("Fixture should detect many prices", prices.size >= 10)

        val selected = SmartActionDetector.selectForAnnotation(entities)
        val currency = selected.filter { it.entity.type == EntityType.CURRENCY }
        assertEquals("Twelve prices are a catalogue, not twelve calls to action", 1, currency.size)
        assertEquals(prices.size, currency.first().groupCount)
        assertEquals("💵 ${prices.size} prices", SmartActionDetector.chipLabel(currency.first().entity, prices.size))
    }

    @Test
    fun `no single type may crowd out the others`() {
        val text = """
            Call (415) 555-2671 or (415) 555-9900.
            Mail a@example.com, b@example.com, c@example.com, d@example.com.
            Visit https://example.com/help
        """.trimIndent()
        val selected = SmartActionDetector.selectForAnnotation(SmartActionDetector.detectAll(text))
        val perType = selected.groupBy { it.entity.type }
        for ((type, group) in perType) {
            assertTrue(
                "$type took ${group.size} slots",
                group.size <= SmartActionDetector.MAX_ANNOTATIONS_PER_TYPE
            )
        }
        assertTrue("The lone URL should survive four emails", perType.containsKey(EntityType.URL))
    }

    @Test
    fun `identical values collapse before ranking`() {
        val text = "Ping support@example.com — again, support@example.com — once more, support@example.com"
        val selected = SmartActionDetector.selectForAnnotation(SmartActionDetector.detectAll(text))
        val emails = selected.filter { it.entity.type == EntityType.EMAIL }
        assertEquals(1, emails.size)
        assertEquals(3, emails.first().groupCount)
    }

    @Test
    fun `chip labels drop the verb and keep the value`() {
        val entity = SmartActionDetector.detectAll("Visit https://example.com/help")
            .first { it.type == EntityType.URL }
        val label = SmartActionDetector.chipLabel(entity)
        assertTrue(label.startsWith("🌐"))
        assertFalse("The verb belongs in the menu, not the chip", label.contains("Open:"))
    }

    @Test
    fun `selectForAnnotation on an empty list is empty`() {
        assertTrue(SmartActionDetector.selectForAnnotation(emptyList()).isEmpty())
    }

    @Test
    fun `a grouped candidate carries every member, not just a count`() {
        val catalogue = (1..12).joinToString("\n") { "Item $it — €${it * 10}.00" }
        val entities = SmartActionDetector.detectAll(catalogue)
        val grouped = SmartActionDetector.selectForAnnotation(entities)
            .first { it.entity.type == EntityType.CURRENCY }

        assertEquals(grouped.groupCount, grouped.members.size)
        assertTrue("the representative must be one of its own members", grouped.entity in grouped.members)
        assertTrue(
            "every member needs an action, or the group menu has dead rows",
            grouped.members.all { it.primaryAction != null }
        )
        // Distinct prices, so the menu is twelve real choices rather than one repeated.
        assertEquals(grouped.members.size, grouped.members.map { it.normalizedValue }.distinct().size)
    }

    // ---- Calendar payloads carry a resolved start ----

    private fun calendarPayload(text: String, now: java.time.LocalDateTime): String {
        val entity = SmartActionDetector.detectAll(text, now = now)
            .first { it.type == EntityType.CALENDAR }
        return SmartActionDetector.decode(entity.primaryAction!!.prompt)!!.second
    }

    @Test
    fun `a dated event carries the resolved day and time`() {
        val payload = calendarPayload("Design review Aug 14, 2026 at 9:30 a.m.", NOON_AUG_1)
        val parts = payload.split("|", limit = 3)
        assertEquals("2026-08-14", parts[0])
        assertEquals("09:30", parts[1])
        assertTrue("the title should survive", parts[2].contains("Aug 14"))
    }

    @Test
    fun `an event with no clock time leaves the time field blank`() {
        val payload = calendarPayload("Team meeting on Monday", LocalDate.of(2026, 8, 5).atStartOfDay())
        val parts = payload.split("|", limit = 3)
        // Wednesday Aug 5 → the coming Monday. A calendar app handed the word
        // "Monday" would have dropped this on today instead.
        assertEquals("2026-08-10", parts[0])
        assertEquals("", parts[1])
    }

    @Test
    fun `midday and midnight convert correctly`() {
        assertEquals(java.time.LocalTime.of(12, 0), SmartActionDetector.timeOfDayOf("lunch at 12 pm"))
        assertEquals(java.time.LocalTime.of(0, 30), SmartActionDetector.timeOfDayOf("12:30 am"))
        assertEquals(java.time.LocalTime.of(15, 0), SmartActionDetector.timeOfDayOf("3 p.m."))
        assertNull(SmartActionDetector.timeOfDayOf("sometime Monday"))
    }

    // ---- Settings → detector propagation (call sites in ScreenLensController,
    //      AssistiveBallService, ScreenCompanionController, GotchaAccessibilityService
    //      all read preferredCurrency / preferredLanguage from Settings and pass
    //      them straight into detectAll()). These tests pin the parameter contract
    //      so the call sites can't silently drop the prefs. ----

    @Test
    fun `detectAll respects a French target language for currency labels`() {
        // USD string with EUR target: the conversion action should mention EUR
        // (the EUR label is what a French-user sees in the proactive action).
        val entities = SmartActionDetector.detectAll("Prix: \$50,00", targetCurrency = "EUR")
        val currency = entities.firstOrNull { it.type == EntityType.CURRENCY }
        assertNotNull("USD-string should be detected as CURRENCY", currency)
        assertTrue(
            "Conversion action should mention EUR when target is EUR — was: ${currency!!.actions}",
            currency.actions.any { it.label.contains("EUR") }
        )
    }

    @Test
    fun `detectAll respects a EUR target when source is USD`() {
        val entities = SmartActionDetector.detectAll("Total: \$50.00", targetCurrency = "EUR")
        val currency = entities.firstOrNull { it.type == EntityType.CURRENCY }
        assertNotNull(currency)
        assertTrue(
            "Should offer Convert to EUR when source is USD and target is EUR",
            currency!!.actions.any { it.label.contains("EUR") }
        )
    }

    @Test
    fun `detectAll default target currency is USD when not specified`() {
        val entities = SmartActionDetector.detectAll("Price is €50")
        val currency = entities.firstOrNull { it.type == EntityType.CURRENCY }
        assertNotNull(currency)
        assertTrue(
            "Default target currency should be USD — was: ${currency!!.actions}",
            currency.actions.any { it.label.contains("Convert to USD") }
        )
    }

    @Test
    fun `detectAll GBP target suppresses GBP conversion action`() {
        val entities = SmartActionDetector.detectAll("Price is £25.00", targetCurrency = "GBP")
        val currency = entities.firstOrNull { it.type == EntityType.CURRENCY }
        assertNotNull(currency)
        assertFalse(
            "Should not offer Convert to GBP when source is GBP",
            currency!!.actions.any { it.label.contains("Convert to GBP") }
        )
    }

    @Test
    fun `chat fallback translation label uses targetLanguage`() {
        // Foreign text in a non-English target language should produce a
        // "Translate to <targetLanguage>" action — the label is what the user
        // sees in the Lens proactive card.
        val entities = SmartActionDetector.detectAll(
            "Hola, ¿cómo estás?",
            allowChat = true,
            targetLanguage = "French"
        )
        val chat = entities.firstOrNull { it.type == EntityType.CHAT_REPLY }
        assertNotNull("Should detect Spanish chat text with allowChat=true", chat)
        assertTrue(
            "Translation label should mention targetLanguage — was: ${chat!!.actions}",
            chat.actions.any { it.label.contains("Translate to French") }
        )
    }

    @Test
    fun `chat fallback does not translate when text already matches targetLanguage`() {
        // English text with targetLanguage=English → no translate action.
        val entities = SmartActionDetector.detectAll(
            "How are you doing today?",
            allowChat = true,
            targetLanguage = "English"
        )
        val chat = entities.firstOrNull { it.type == EntityType.CHAT_REPLY }
        assertNotNull(chat)
        assertFalse(
            "Should not offer Translate to English when text is already English",
            chat!!.actions.any { it.label.contains("Translate to") }
        )
    }

    @Test
    fun `call sites propagate the same targetCurrency and targetLanguage`() {
        // Pin the shape of the call signature so any change to detectAll's
        // parameter list surfaces here, not in a runtime ClassCastException.
        val a = SmartActionDetector.detectAll("100 USD", targetCurrency = "USD")
        val b = SmartActionDetector.detectAll(
            "100 EUR",
            targetCurrency = "USD",
            targetLanguage = "English"
        )
        assertNotNull(a.firstOrNull { it.type == EntityType.CURRENCY })
        assertNotNull(b.firstOrNull { it.type == EntityType.CURRENCY })
    }

    private companion object {
        /** Midday on the day the bug-report screenshots were taken. */
        val NOON_AUG_1: java.time.LocalDateTime = LocalDate.of(2026, 8, 1).atTime(12, 0)
    }
}
