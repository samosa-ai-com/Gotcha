package com.gotcha.ui.tour

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned

/**
 * Controls the feature tour is able to point at.
 *
 * A tour step names one of these rather than a screen coordinate, so moving a
 * row up the settings list — or restyling it entirely — cannot leave the
 * spotlight highlighting empty space. An anchor that is not on screen simply has
 * no bounds, and [TourStep.requiresAnchor] decides what that means for the step.
 */
enum class TourAnchor {
    DRAWER_SETTINGS,
    SETTINGS_PERSONAL_INFO,
    SETTINGS_AI_CONFIG,
    SETTINGS_PERMISSIONS,
    AI_PROVIDER,
    AI_SAMOSA_SIGN_IN,
    AI_SAVE
}

/**
 * Where each registered [TourAnchor] currently is, in root coordinates.
 *
 * Written by [tourAnchor] as controls are laid out and read by [TourOverlay] one
 * frame later. Backed by a snapshot map so the overlay recomposes when a row
 * moves — a settings list that scrolls under the scrim keeps its cut-out aligned.
 */
@Stable
class TourAnchors {
    private val bounds = mutableStateMapOf<TourAnchor, Rect>()

    operator fun get(anchor: TourAnchor): Rect? = bounds[anchor]

    internal fun record(anchor: TourAnchor, rect: Rect) {
        bounds[anchor] = rect
    }

    internal fun forget(anchor: TourAnchor) {
        bounds.remove(anchor)
    }
}

/**
 * The anchor registry for the current composition. Defaults to a throwaway
 * instance so a screen previewed or tested in isolation still composes — its
 * anchors are simply recorded where nothing reads them.
 */
val LocalTourAnchors = staticCompositionLocalOf { TourAnchors() }

/**
 * Registers this control as [anchor] for as long as it is composed.
 *
 * One line per control and no behaviour change: the tour is the only thing that
 * reads the result, and a build with the tour disabled writes into the default
 * registry above and is otherwise unaffected.
 */
@Composable
fun Modifier.tourAnchor(anchor: TourAnchor): Modifier {
    val anchors = LocalTourAnchors.current
    // Rows scroll away and pages are left; a stale rect would spotlight whatever
    // has since been laid out in the same place.
    DisposableEffect(anchors, anchor) {
        onDispose { anchors.forget(anchor) }
    }
    return onGloballyPositioned { anchors.record(anchor, it.boundsInRoot()) }
}
