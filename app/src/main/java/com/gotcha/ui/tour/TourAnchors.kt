package com.gotcha.ui.tour

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
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
    AI_SAVE,
    PERSONAL_NAME,
    PERSONAL_SAVE
}

/** Where one anchored control is, and how to scroll it back into view. */
@OptIn(ExperimentalFoundationApi::class)
private class AnchorHandle(
    val bounds: Rect,
    val requester: BringIntoViewRequester
)

/**
 * Where each registered [TourAnchor] currently is, in root coordinates.
 *
 * Written by [tourAnchor] as controls are laid out and read by [TourOverlay] one
 * frame later. Backed by a snapshot map so the overlay recomposes when a row
 * moves — a settings list that scrolls under the scrim keeps its cut-out aligned.
 */
@Stable
class TourAnchors {
    private val handles = mutableStateMapOf<TourAnchor, AnchorHandle>()

    /**
     * The bounds of [anchor] in root coordinates, or null when nothing is registered.
     *
     * These are [androidx.compose.ui.layout.boundsInRoot] — unclipped. A control
     * scrolled out of its list still exists and still reports a position here,
     * so on its own this would let a spotlight punch a hole somewhere the user
     * cannot see while blocking every part of the screen they can — precisely
     * the trap the tour must never spring. [TourOverlay] avoids that by calling
     * [bringIntoView] before it trusts these bounds, scrolling the anchor into
     * view rather than drawing a hole into nothing.
     */
    operator fun get(anchor: TourAnchor): Rect? =
        handles[anchor]?.bounds?.takeIf { !it.isEmpty }

    /** Scrolls [anchor] back into view, if it is registered and scrollable. */
    @OptIn(ExperimentalFoundationApi::class)
    suspend fun bringIntoView(anchor: TourAnchor) {
        handles[anchor]?.requester?.bringIntoView()
    }

    @OptIn(ExperimentalFoundationApi::class)
    internal fun record(anchor: TourAnchor, bounds: Rect, requester: BringIntoViewRequester) {
        handles[anchor] = AnchorHandle(bounds, requester)
    }

    internal fun forget(anchor: TourAnchor) {
        handles.remove(anchor)
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
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.tourAnchor(anchor: TourAnchor): Modifier {
    val anchors = LocalTourAnchors.current
    // Half the controls worth pointing at — a Save button at the foot of a long
    // settings page — are off screen when their step opens. Registering a
    // requester here is what lets the tour scroll them back to the user.
    val requester = remember { BringIntoViewRequester() }
    // Rows scroll away and pages are left; a stale rect would spotlight whatever
    // has since been laid out in the same place.
    DisposableEffect(anchors, anchor) {
        onDispose { anchors.forget(anchor) }
    }
    return this
        .bringIntoViewRequester(requester)
        .onGloballyPositioned { anchors.record(anchor, it.boundsInRoot(), requester) }
}
