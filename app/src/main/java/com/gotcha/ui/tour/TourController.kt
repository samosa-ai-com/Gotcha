package com.gotcha.ui.tour

import android.content.Context
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.gotcha.data.Settings

/**
 * Runs the guided setup: which step is showing, when it is finished, and where
 * the app has to be for it to make sense.
 *
 * Deliberately free of Compose UI and of `SettingsRepository`: it takes the
 * settings it needs as arguments and reports what should be persisted through
 * [onPersist]. That keeps the whole flow — including the awkward parts, like
 * resuming after the user has been away in Android Settings — testable without
 * an emulator.
 */
@Stable
class TourController(
    private val steps: List<TourStep> = defaultTourSteps(),
    /**
     * Persists progress after every move. Called with the step to resume on, or
     * null once the tour is over. A tour that only remembered "finished / not
     * finished" would restart from the top every time the user granted a
     * permission on a device that killed the process while they were away.
     */
    private val onPersist: (stepId: String?, completed: Boolean) -> Unit = { _, _ -> }
) {
    /** Index into [steps], or -1 when the tour is not running. */
    private var index by mutableStateOf(-1)

    /**
     * Where the app actually is, as last reported by the host. Null when the user
     * is somewhere the tour has nothing to say about.
     */
    var place by mutableStateOf<TourPlace?>(null)
        private set

    val isRunning: Boolean get() = index in steps.indices

    val current: TourStep? get() = steps.getOrNull(index)

    /** 1-based position for the "3 of 11" counter. Zero when not running. */
    val stepNumber: Int get() = if (isRunning) index + 1 else 0

    val stepCount: Int get() = steps.size

    /**
     * Whether the current step's coach mark should be on screen. False while the
     * user is somewhere else entirely — they are allowed to wander off, and a
     * card pinned over an unrelated screen would be nonsense.
     */
    val isShowing: Boolean get() = isRunning && current?.place == place

    /**
     * Where the host should take the user as this step opens, or null to leave
     * them where they are.
     *
     * Read once per step, never continuously: a tour that dragged the user back
     * every time they looked at something else would be a cage, not a guide.
     */
    val destination: TourPlace?
        get() = current?.takeIf { it.autoNavigate && it.place != place }?.place

    /**
     * Starts (or restarts) the tour. [fromStepId] resumes a saved position;
     * an unknown or null id starts from the beginning.
     */
    fun start(fromStepId: String? = null) {
        index = steps.indexOfFirst { it.id == fromStepId }.takeIf { it >= 0 } ?: 0
        onPersist(current?.id, false)
    }

    /** Abandons the tour — "Skip tour". Marks it complete so it does not return. */
    fun cancel() {
        index = -1
        onPersist(null, true)
    }

    /** The user acted on the card's own button: move past this step. */
    fun acknowledge() {
        if (isRunning) moveTo(index + 1)
    }

    /**
     * Re-reads the world and advances past everything already satisfied. Call on
     * every resume: the user may have granted a permission in Android Settings,
     * and on a device that killed the process while they were away this is the
     * only thing that notices.
     */
    fun refresh(settings: Settings, context: Context) {
        if (!isRunning) return
        var next = index
        while (next < steps.size && steps[next].isDone(settings, context)) next++
        if (next != index) moveTo(next)
    }

    /**
     * The host reports where the user now is.
     *
     * Arriving somewhere finishes the current step only when the next step lives
     * elsewhere — that is precisely the case where making the journey was the
     * instruction. Consecutive steps on one screen (choose a provider, sign in,
     * save) must not all fall over at once because the screen opened.
     */
    fun onPlaceChanged(newPlace: TourPlace?) {
        place = newPlace
        val step = current ?: return
        val next = steps.getOrNull(index + 1) ?: return
        if (next.place != step.place && newPlace == next.place) moveTo(index + 1)
    }

    /**
     * The current step's anchor never appeared, so the branch it belongs to is
     * not the one this user is on (signing in to Samosa, when they have chosen
     * to bring their own key). Move on quietly.
     */
    fun skipMissingAnchor() {
        val step = current ?: return
        if (step.requiresAnchor) moveTo(index + 1)
    }

    private fun moveTo(newIndex: Int) {
        if (newIndex >= steps.size) {
            index = -1
            onPersist(null, true)
            return
        }
        index = newIndex
        onPersist(current?.id, false)
    }
}
