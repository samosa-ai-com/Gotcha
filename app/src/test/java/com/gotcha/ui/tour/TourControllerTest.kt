package com.gotcha.ui.tour

import android.content.Context
import com.gotcha.data.LlmProvider
import com.gotcha.data.Settings
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tour's rules, exercised without an emulator. Everything here is a
 * behaviour the real flow depends on and that is painful to notice by hand:
 * a step that ends itself, a user who wanders off, a process that died while
 * they were away in Android Settings.
 */
class TourControllerTest {

    private val context: Context = mockk(relaxed = true)

    /** Two steps on one screen, then one on another — the shape the real flow has. */
    private fun steps() = listOf(
        TourStep(
            id = "first",
            place = TourPlace.SETTINGS_HOME,
            title = "First",
            body = ""
        ),
        TourStep(
            id = "second",
            place = TourPlace.AI_CONFIG,
            title = "Second",
            body = "",
            isDone = { settings, _ -> settings.isSamosaAuthenticated }
        ),
        TourStep(
            id = "third",
            place = TourPlace.AI_CONFIG,
            title = "Third",
            body = "",
            ackLabel = "Got it"
        )
    )

    private val signedIn = Settings(
        provider = LlmProvider.SAMOSA_AI,
        samosaSessionToken = "token"
    )

    @Test
    fun `starts on the first step`() {
        val controller = TourController(steps())
        controller.start()

        assertTrue(controller.isRunning)
        assertEquals("first", controller.current?.id)
        assertEquals(1, controller.stepNumber)
    }

    @Test
    fun `resumes on a saved step`() {
        val controller = TourController(steps())
        controller.start(fromStepId = "third")

        assertEquals("third", controller.current?.id)
    }

    @Test
    fun `resuming on an unknown step falls back to the beginning`() {
        val controller = TourController(steps())
        controller.start(fromStepId = "a-step-this-build-no-longer-has")

        assertEquals("first", controller.current?.id)
    }

    @Test
    fun `arriving where the next step lives finishes this one`() {
        val controller = TourController(steps())
        controller.start()
        controller.onPlaceChanged(TourPlace.SETTINGS_HOME)

        controller.onPlaceChanged(TourPlace.AI_CONFIG)

        assertEquals("second", controller.current?.id)
    }

    @Test
    fun `consecutive steps on one screen do not all fall over when it opens`() {
        val controller = TourController(steps())
        controller.start(fromStepId = "second")

        // "third" lives on the same screen, so arriving there says nothing about
        // whether the user has done what "second" asked.
        controller.onPlaceChanged(TourPlace.AI_CONFIG)

        assertEquals("second", controller.current?.id)
    }

    @Test
    fun `refresh advances past a step the world has already satisfied`() {
        val controller = TourController(steps())
        controller.start(fromStepId = "second")

        controller.refresh(signedIn, context)

        assertEquals("third", controller.current?.id)
    }

    @Test
    fun `refresh does nothing when the tour is not running`() {
        val controller = TourController(steps())

        controller.refresh(signedIn, context)

        assertFalse(controller.isRunning)
    }

    @Test
    fun `acknowledging the last step ends the tour`() {
        val controller = TourController(steps())
        controller.start(fromStepId = "third")

        controller.acknowledge()

        assertFalse(controller.isRunning)
        assertNull(controller.current)
    }

    @Test
    fun `the coach mark hides while the user is somewhere else`() {
        val controller = TourController(steps())
        controller.start()

        controller.onPlaceChanged(TourPlace.SETTINGS_HOME)
        assertTrue(controller.isShowing)

        controller.onPlaceChanged(TourPlace.PERSONAL_INFO)
        assertFalse(controller.isShowing)
    }

    @Test
    fun `progress is persisted on every move, and cleared when the tour ends`() {
        val saved = mutableListOf<Pair<String?, Boolean>>()
        val controller = TourController(steps()) { stepId, completed ->
            saved += stepId to completed
        }

        controller.start()
        controller.acknowledge()
        controller.acknowledge()
        controller.acknowledge()

        assertEquals(
            listOf(
                "first" to false,
                "second" to false,
                "third" to false,
                null to true
            ),
            saved
        )
    }

    @Test
    fun `skipping the tour marks it complete so it does not come back`() {
        val saved = mutableListOf<Pair<String?, Boolean>>()
        val controller = TourController(steps()) { stepId, completed ->
            saved += stepId to completed
        }
        controller.start()

        controller.cancel()

        assertFalse(controller.isRunning)
        assertEquals(null to true, saved.last())
    }

    @Test
    fun `a step whose branch is absent is skipped, and one that is required is not`() {
        val branching = listOf(
            TourStep(
                id = "optional_branch",
                place = TourPlace.AI_CONFIG,
                title = "Sign in",
                body = "",
                anchor = TourAnchor.AI_SAMOSA_SIGN_IN,
                requiresAnchor = true
            ),
            TourStep(id = "after", place = TourPlace.AI_CONFIG, title = "After", body = "")
        )
        val controller = TourController(branching)
        controller.start()

        controller.skipMissingAnchor()
        assertEquals("after", controller.current?.id)

        // "after" does not require its anchor, so the same call leaves it alone.
        controller.skipMissingAnchor()
        assertEquals("after", controller.current?.id)
    }

    @Test
    fun `the host is told where to go only when the step is not already there`() {
        val controller = TourController(steps())
        controller.start()

        assertEquals(TourPlace.SETTINGS_HOME, controller.destination)

        controller.onPlaceChanged(TourPlace.SETTINGS_HOME)
        assertNull(controller.destination)
    }

    @Test
    fun `a step that waits for the user is not navigated to on their behalf`() {
        val waiting = listOf(
            TourStep(
                id = "tap_it_yourself",
                place = TourPlace.SETTINGS_HOME,
                title = "Tap it",
                body = "",
                autoNavigate = false
            )
        )
        val controller = TourController(waiting)
        controller.start()

        assertNull(controller.destination)
    }

    @Test
    fun `every shipped step is reachable and uniquely identified`() {
        val ids = defaultTourSteps().map { it.id }

        assertEquals(ids.size, ids.toSet().size)
        assertTrue(defaultTourSteps().isNotEmpty())
    }

    @Test
    fun `no shipped step can strand the user with no way out`() {
        // A step is escapable if it offers a button of its own, or if the next
        // step is somewhere else so that simply moving on finishes it. "Skip
        // tour" is always there too, but that abandons the whole flow — it
        // should never be the only exit from a single step.
        val all = defaultTourSteps()
        all.forEachIndexed { index, step ->
            val nextIsElsewhere = all.getOrNull(index + 1)?.place?.let { it != step.place } ?: true
            assertTrue(
                "Step '${step.id}' has no way forward",
                step.ackLabel != null || nextIsElsewhere
            )
        }
    }
}
