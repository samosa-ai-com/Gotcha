package com.gotcha.ui.tour

import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.gotcha.data.SettingsRepository
import com.gotcha.data.settingsChangeNotifier

/**
 * Which edition of the tour this build ships. Written when the tour ends, so a
 * later release that adds steps can tell who has seen what.
 */
const val TOUR_VERSION = 1

/**
 * Preference key that stops the tour from ever starting. For the instrumented
 * tests, which drive the real screens and would otherwise be tapping at a scrim.
 */
const val SUPPRESS_TOUR_KEY = "suppress_feature_tour"

/** The tour's two pieces of state, handed to the host as one thing. */
@Stable
class TourHost(
    val controller: TourController,
    val anchors: TourAnchors,
    /**
     * Whether this launch is going to run the tour. Known before it starts, so
     * the host can stand aside — an unconfigured install normally opens straight
     * on the API key page, which would be arguing with a tour that is about to
     * walk the user there itself.
     */
    val willRun: Boolean
)

/**
 * Wires the tour into an activity: creates the controller, starts it when this
 * install has not been through it, and re-reads the world whenever something
 * that could finish a step might have happened.
 *
 * The resume hook is what makes the permission steps work at all. Granting
 * Accessibility means leaving for Android Settings, and coming back is the only
 * moment the app can notice it happened — on a device short of memory, possibly
 * in a brand new process, which is why the step id is persisted rather than held
 * in memory.
 *
 * @param ready false while something else must come first. A first launch has to
 *   get past the legal-consent gate before the tour has a screen it can point
 *   at: that dialog is its own window and covers everything, so a tour started
 *   underneath it would spotlight a control the user cannot reach.
 */
@Composable
fun rememberTourHost(repository: SettingsRepository, ready: Boolean): TourHost {
    val initial = remember { repository.load() }
    val host = remember {
        TourHost(
            controller = TourController(
                onPersist = { stepId, completed ->
                    repository.saveTourProgress(stepId, completed, TOUR_VERSION)
                }
            ),
            anchors = TourAnchors(),
            willRun = !initial.hasCompletedOnboarding &&
                !repository.prefs.getBoolean(SUPPRESS_TOUR_KEY, false)
        )
    }

    // Keyed on readiness rather than Unit: the host may only be able to say
    // "yes, now" some way into the session.
    LaunchedEffect(ready) {
        if (ready && host.willRun) host.controller.start(initial.tourStepId.ifBlank { null })
    }

    val context = LocalContext.current
    val refresh = { host.controller.refresh(repository.load(), context) }

    // Opening a step the user has already satisfied — a name they filled in
    // months ago, a key already saved — should not stop them for an instruction
    // they have nothing to do about.
    LaunchedEffect(host.controller.current?.id) { refresh() }

    // Settings written anywhere in the app: a sign-in landing, a page saved.
    // Without this a step could only notice its own completion on the next
    // resume, so tapping Save would appear to do nothing.
    DisposableEffect(context) {
        val notifier = settingsChangeNotifier(context)
        // Keys arrive encrypted and cannot be matched on, so any write is a
        // reason to look again — see settingsChangeNotifier's own note.
        val listener = OnSharedPreferenceChangeListener { _, _ -> refresh() }
        notifier.registerOnSharedPreferenceChangeListener(listener)
        onDispose { notifier.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    // Permissions are granted in another app entirely, so coming back is the
    // only moment this one can find out.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    return host
}

/**
 * Keeps the tour and the app's navigation in step: tells the controller where
 * the user is, and takes them where a newly-opened step needs them to be.
 */
@Composable
fun TourNavigation(
    host: TourHost,
    place: TourPlace?,
    goToPlace: (TourPlace) -> Unit
) {
    LaunchedEffect(place) { host.controller.onPlaceChanged(place) }

    // Once per step, as it opens — never continuously; see
    // TourController.destination for why that distinction matters.
    LaunchedEffect(host.controller.current?.id) {
        host.controller.destination?.let(goToPlace)
    }
}
