package com.gotcha.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The matching behind the settings search field. The cases named in issue #86
 * are the point of the feature: each one is a control with no row of its own on
 * the home list, found by the word someone would actually type for it.
 */
class SettingsSearchEntryTest {

    private fun pagesFor(query: String) = filterSettings(query).map { it.page }

    @Test
    fun keywordsFindThePageOwningTheControl() {
        assertTrue(SettingsPage.SPEECH in pagesFor("voice"))
        assertTrue(SettingsPage.ASSISTIVE_BALL in pagesFor("wake word"))
        assertTrue(SettingsPage.AI_CONFIG in pagesFor("api key"))
        assertTrue(SettingsPage.SPEECH in pagesFor("read aloud"))
        assertTrue(SettingsPage.PERMISSIONS in pagesFor("accessibility"))
    }

    @Test
    fun matchingIsCaseInsensitive() {
        assertEquals(pagesFor("api key"), pagesFor("API KEY"))
        assertEquals(pagesFor("termux"), pagesFor("TeRmUx"))
    }

    @Test
    fun wordOrderDoesNotMatter() {
        assertEquals(pagesFor("wake word"), pagesFor("word wake"))
    }

    @Test
    fun titlesAndSummariesMatchToo() {
        assertTrue(SettingsPage.NOTIFICATIONS in pagesFor("Notifications"))
        // "currency" is only in the Personal Info summary, not its keywords.
        assertTrue(SettingsPage.PERSONAL_INFO in pagesFor("currency"))
    }

    @Test
    fun eachExtraWordNarrowsTheResults() {
        val voice = pagesFor("voice")
        val voiceLanguage = pagesFor("voice language")
        assertTrue(voiceLanguage.size <= voice.size)
        assertTrue(SettingsPage.LANGUAGE in voiceLanguage)
    }

    @Test
    fun blankQueryMatchesNothing() {
        assertTrue(filterSettings("").isEmpty())
        assertTrue(filterSettings("   ").isEmpty())
    }

    @Test
    fun nonsenseQueryMatchesNothing() {
        assertTrue(filterSettings("qwertyuiop").isEmpty())
    }

    @Test
    fun everyPageIsSearchableByItsOwnTitle() {
        SettingsPage.entries.forEach { page ->
            assertTrue(
                "${page.title} cannot be found by its own title",
                page in pagesFor(page.title)
            )
        }
    }

    @Test
    fun indexCoversEveryPageExactlyOnce() {
        assertEquals(SettingsPage.entries.toList(), settingsSearchIndex.map { it.page })
    }

    @Test
    fun nestedPagesNameTheirHub() {
        val speech = settingsSearchIndex.first { it.page == SettingsPage.SPEECH }
        assertEquals("AI › ${SettingsPage.SPEECH.title}", speech.breadcrumb)

        val termux = settingsSearchIndex.first { it.page == SettingsPage.TERMUX }
        assertEquals(SettingsPage.TERMUX.title, termux.breadcrumb)
    }
}
