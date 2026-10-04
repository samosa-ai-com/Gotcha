package com.gotcha.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The matching behind the settings search field. The cases named in issue #86
 * are the point of the feature: each one is a control with no row of its own on
 * the home list, found by the word someone would actually type for it.
 * Titles and labels are string resources, so this runs on Robolectric's (English) ones.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsSearchEntryTest {

    private val text = ApplicationProvider.getApplicationContext<Context>().stringLookup()

    private fun filterSettings(query: String) = filterSettings(query, text)

    private fun pagesFor(query: String) = filterSettings(query).map { it.page }

    private val SettingsPage.titleText: String get() = text(title)

    @Test
    fun keywordsFindThePageOwningTheControl() {
        assertTrue(SettingsPage.SPEECH in pagesFor("voice"))
        assertTrue(SettingsPage.ASSISTIVE_BALL in pagesFor("wake word"))
        assertTrue(SettingsPage.AI_CONFIG in pagesFor("api key"))
        assertTrue(SettingsPage.SPEECH in pagesFor("read aloud"))
        assertTrue(SettingsPage.PERMISSIONS in pagesFor("accessibility"))
    }

    @Test
    fun nightLightFindsTheScreenshotSwitch() {
        for (query in listOf("night light", "night mode", "yellow tint")) {
            val result = filterSettings(query).single { it.page == SettingsPage.ASSISTIVE_BALL }
            assertEquals(query, "settings_pause_night_light", result.field?.testTag)
        }
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
                "${page.titleText} cannot be found by its own title",
                page in pagesFor(page.titleText)
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
        assertEquals("AI › ${SettingsPage.SPEECH.titleText}", speech.breadcrumb(text))

        val termux = settingsSearchIndex.first { it.page == SettingsPage.TERMUX }
        assertEquals(SettingsPage.TERMUX.titleText, termux.breadcrumb(text))
    }

    private fun fieldFor(query: String, page: SettingsPage) =
        filterSettings(query).first { it.page == page }.field

    @Test
    fun aQueryNamingAControlPointsAtThatControl() {
        val maxRounds = fieldFor("max tool rounds", SettingsPage.AI_CONFIG)
        assertEquals("settings_max_tool_rounds", maxRounds?.testTag)
        // It sits in the collapsed Advanced block, which has to open for it.
        assertEquals(AI_ADVANCED_SECTION, maxRounds?.section)

        assertEquals("settings_proactive_otp", fieldFor("otp", SettingsPage.PROACTIVE)?.testTag)
        assertEquals("settings_wake_word", fieldFor("wake word", SettingsPage.ASSISTIVE_BALL)?.testTag)
        assertEquals("settings_auto_read_replies", fieldFor("read aloud", SettingsPage.SPEECH)?.testTag)
    }

    @Test
    fun aPagesOwnTitleHighlightsNothing() {
        SettingsPage.entries.forEach { page ->
            assertNull("${page.titleText} highlights a field", fieldFor(page.titleText, page))
        }
    }

    @Test
    fun aPageLevelAliasHighlightsNothing() {
        assertNull(fieldFor("privacy", SettingsPage.PROACTIVE))
        assertNull(fieldFor("accessibility", SettingsPage.PERMISSIONS))
    }

    @Test
    fun aFieldResultNamesTheFieldInItsLabel() {
        val result = filterSettings("max tool rounds").first { it.page == SettingsPage.AI_CONFIG }
        assertEquals("AI › AI Configuration › Max tool rounds", result.label(text))
    }

    @Test
    fun fieldTagsAreUniqueAcrossTheIndex() {
        val tags = settingsSearchIndex.flatMap { entry -> entry.fields.map { it.testTag } }
        assertEquals(tags.size, tags.toSet().size)
    }

    @Test
    fun fieldsAreFoundByTheirPageAndTag() {
        assertEquals(
            "Max tool rounds",
            settingsFieldFor(SettingsPage.AI_CONFIG, "settings_max_tool_rounds")?.label?.let { text(it) }
        )
        assertNull(settingsFieldFor(SettingsPage.SPEECH, "settings_max_tool_rounds"))
    }
}
