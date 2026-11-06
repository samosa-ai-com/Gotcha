package com.gotcha.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.gotcha.scheduled.Repeat
import com.gotcha.scheduled.RepeatKind
import com.gotcha.scheduled.ScheduledTask
import com.gotcha.scheduled.ScheduledTaskStore
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Whether the home screen should still call a feature "new": only while the
 * user has not actually used it, whatever the chip flags say.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FeatureUsageTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var tasks: ScheduledTaskStore

    @Before
    fun setUp() {
        GotchaStorage.memoryRoot().deleteRecursively()
        MemoryStore.ensureSkeleton()
        tasks = ScheduledTaskStore(context)
        tasks.all().forEach { tasks.delete(it.id) }
    }

    private fun usage() = loadFeatureUsage(context) { tasks.all() }

    private fun task(presetId: String? = null, enabled: Boolean = false) = ScheduledTask(
        id = "t",
        name = "Mine",
        prompt = "p",
        startLocal = "2026-11-02T08:00",
        repeat = Repeat(RepeatKind.DAILY),
        enabled = enabled,
        presetId = presetId
    )

    @Test
    fun `a fresh install has used neither feature`() {
        assertFalse(usage().memory)
        assertFalse(usage().schedule)
    }

    @Test
    fun `a saved note counts as memory use`() {
        File(GotchaStorage.memoryNotesDir(), "tea.md").writeText("# Tea\n\nMasala chai.")

        assertTrue(usage().memory)
    }

    @Test
    fun `an edited identity counts as memory use`() {
        GotchaStorage.soulFile().writeText("# SOUL\n\n- Tone: dry")

        assertTrue(usage().memory)
    }

    @Test
    fun `an edited map counts as memory use`() {
        GotchaStorage.memoryIndexFile().writeText("# MEMORY\n\n- Gym: notes/gym.md")

        assertTrue(usage().memory)
    }

    @Test
    fun `a person counts as memory use`() {
        File(GotchaStorage.memoryPeopleDir(), "ana.md").writeText("# Ana\n\nSister.")

        assertTrue(usage().memory)
    }

    @Test
    fun `a raw file counts as memory use`() {
        File(GotchaStorage.memoryRawDir(), "receipt.pdf").writeBytes(byteArrayOf(1, 2, 3))

        assertTrue(usage().memory)
    }

    @Test
    fun `a hidden raw file is the media scanner, not use`() {
        File(GotchaStorage.memoryRawDir(), ".nomedia").writeText("")

        assertFalse(usage().memory)
    }

    @Test
    fun `an empty note is not use`() {
        File(GotchaStorage.memoryNotesDir(), "empty.md").writeText("")

        assertFalse(usage().memory)
    }

    @Test
    fun `a markdown note counts even with the long extension`() {
        File(GotchaStorage.memoryNotesDir(), "tea.markdown").writeText("# Tea\n\nMasala chai.")

        assertTrue(usage().memory)
    }

    @Test
    fun `an edited area hub counts as memory use`() {
        val work = File(GotchaStorage.memoryAreasDir(), "work.md")
        work.writeText(work.readText() + "\n- Alpha ships Friday")

        assertTrue(usage().memory)
    }

    @Test
    fun `a fifth area counts as memory use`() {
        File(GotchaStorage.memoryAreasDir(), "finance.md").writeText(
            "---\ntitle: Finance\ntype: area\n---\n\n# Finance\n\nBudget notes."
        )

        assertTrue(usage().memory)
    }

    @Test
    fun `CRLF in a generated file is still the skeleton`() {
        val soul = GotchaStorage.soulFile()
        soul.writeText(soul.readText().replace("\n", "\r\n"))

        assertFalse(usage().memory)
    }

    @Test
    fun `a failing task read does not fail the check`() {
        val usage = loadFeatureUsage(context) { throw IllegalStateException("boom") }

        assertFalse(usage.schedule)
    }

    @Test
    fun `a task the user made counts as scheduling use`() {
        tasks.put(task())

        assertTrue(usage().schedule)
    }

    @Test
    fun `the paused examples alone are not use`() {
        tasks.seedPresets(
            GotchaStorage.memoryRoot().absolutePath,
            LocalDateTime.parse("2026-11-01T09:00").atZone(ZoneId.of("UTC")).toInstant().toEpochMilli(),
            ZoneId.of("UTC")
        )

        assertFalse(usage().schedule)
    }

    @Test
    fun `an example switched on counts as use`() {
        tasks.put(task(presetId = "evening_wrap_up", enabled = true))

        assertTrue(usage().schedule)
    }
}
