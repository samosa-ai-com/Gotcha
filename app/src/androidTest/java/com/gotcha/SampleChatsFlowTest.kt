package com.gotcha

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gotcha.data.ChatHistoryRepository
import com.gotcha.data.SampleChatSeeder
import com.gotcha.data.SampleChats
import com.gotcha.data.SettingsRepository
import com.gotcha.testutil.MockLlm
import com.gotcha.testutil.TestSeed
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The seeded sample chats, end to end: they appear in the drawer of a fresh
 * install, they say they are samples both there and inside, and deleting one
 * is final.
 *
 * Every test starts by putting the app back into its first-run state — an empty
 * chats directory and an unset seeding flag — because the flag is exactly what
 * stops this from happening twice, and an earlier test in the same run will
 * already have tripped it.
 */
@RunWith(AndroidJUnit4::class)
class SampleChatsFlowTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    private var scenario: ActivityScenario<MainActivity>? = null
    private val mockLlm = MockLlm()
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun setup() {
        resetToFirstRun()
        mockLlm.start()
        TestSeed.seedConfigured(context, baseUrl = mockLlm.baseUrl, model = "test-model")
    }

    @After
    fun tearDown() {
        scenario?.close()
        mockLlm.shutdown()
        resetToFirstRun()
    }

    private fun resetToFirstRun() {
        File(context.filesDir, "chats").listFiles()?.forEach { it.delete() }
        SettingsRepository(context).prefs.edit()
            .remove(SampleChatSeeder.SEEDED_KEY)
            .commit()
    }

    private fun launch() {
        seedFirstRun()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeRule.waitForIdle()
    }

    /**
     * Seeds the way a real first launch does: the same seeder, against the same
     * chat directory and preferences.
     *
     * The app does this from ChatRunner's startup job, and that job is built in
     * ChatRunner's constructor — which `ChatRunner.of` only ever runs once per
     * *process*. Every test here shares one instrumentation process, so from the
     * second test onward the seeding has already happened somewhere the test
     * cannot reach back to, and clearing the flag on disk does not bring it
     * back: the launch that would seed is not coming. Calling the seeder here
     * puts each test back in the first-run state it is actually asserting about,
     * and on the relaunch in [aDeletedSampleDoesNotComeBack] it is a no-op
     * because the flag is already set — which is the regression that test exists
     * to guard, now exercised for real rather than passed by accident.
     */
    private fun seedFirstRun() {
        runBlocking {
            SampleChatSeeder.seedIfNeeded(ChatHistoryRepository(context), SettingsRepository(context).prefs)
        }
    }

    private fun openDrawer() {
        composeRule.onNodeWithContentDescription("Open menu").performClick()
        composeRule.waitForIdle()
    }

    private fun titles() = SampleChats.all().map { it.title }

    /**
     * A drawer row for the sample called [title].
     *
     * Matching the title alone is not enough to say "the drawer has this chat":
     * the chat screen stays composed behind the open drawer, and one of its
     * starter prompts happens to be called "Turn on Wi-Fi" too — the same words
     * as a sample. Only a row that also says where it came from is the drawer's,
     * which is the thing these tests are actually about.
     */
    private fun sampleRow(title: String) = hasText(title) and hasText(SAMPLE_LABEL)

    private fun sampleRowNodes(title: String) =
        composeRule.onAllNodes(sampleRow(title)).fetchSemanticsNodes()

    private fun awaitSampleRows() {
        composeRule.waitUntil(timeoutMillis = 10_000) {
            titles().all { title -> sampleRowNodes(title).isNotEmpty() }
        }
    }

    @Test
    fun freshInstall_showsBothSamplesInTheDrawer() {
        launch()
        openDrawer()
        awaitSampleRows()

        titles().forEach { composeRule.onNode(sampleRow(it)).assertExists() }
        // Both rows say where they came from, not just one.
        assertTrue(
            "every seeded row must be labelled a sample",
            composeRule.onAllNodes(hasText(SAMPLE_LABEL)).fetchSemanticsNodes().size >= titles().size
        )
    }

    @Test
    fun openingASample_showsTheNoticeAboveTheTranscript() {
        launch()
        openDrawer()
        awaitSampleRows()

        composeRule.onNode(sampleRow(titles().first())).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("sample_chat_notice").assertExists()
        // And it is a real transcript, not an empty chat wearing a label.
        composeRule.onNodeWithTag("message_list").assertExists()
    }

    @Test
    fun aDeletedSampleDoesNotComeBack() {
        launch()
        openDrawer()
        awaitSampleRows()

        // Delete every sample, confirming each time.
        repeat(titles().size) {
            composeRule.onAllNodes(hasContentDescription("Delete chat")).onFirst().performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithText("Delete").performClick()
            composeRule.waitForIdle()
        }
        scenario?.close()
        scenario = null

        // A relaunch is the moment seeding would run again if the flag were not
        // already set — this is the regression the flag exists to prevent.
        launch()
        openDrawer()
        composeRule.waitForIdle()
        titles().forEach { title ->
            assertTrue(
                "deleted sample '$title' came back after a relaunch",
                sampleRowNodes(title).isEmpty()
            )
        }
    }

    private companion object {
        /** What the drawer calls a seeded chat; see `R.string.drawer_sample_chat`. */
        const val SAMPLE_LABEL = "Sample chat"
    }
}
