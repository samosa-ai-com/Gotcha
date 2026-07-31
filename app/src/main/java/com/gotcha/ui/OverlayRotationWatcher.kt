package com.gotcha.ui

import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.util.Size

/**
 * Tells an overlay when the screen it is pinned to changes shape.
 *
 * An overlay window is positioned in raw pixels against the display and
 * nothing re-lays it out when the device rotates. A control docked to the
 * right edge in landscape is sitting at roughly x=2300, and in portrait that
 * is several hundred pixels past the side of a 1080px screen; with
 * FLAG_LAYOUT_NO_LIMITS there is nothing to clamp it either. The window is
 * still there — just parked off the display, which is indistinguishable from
 * it having vanished.
 *
 * Every overlay that pins itself to an edge, carries a dragged position, or
 * sizes itself against the screen owns one of these and puts itself back.
 * Overlays laid out purely by gravity (a centred card, say) do not need one:
 * WindowManager re-centres those on its own.
 */
class OverlayRotationWatcher(
    context: Context,
    private val onScreenChanged: (previous: Size, current: Size) -> Unit
) {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private var registered = false
    private var lastSize = Size(0, 0)

    private val callbacks = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) {
            // Posted rather than handled inline: the callback can arrive on the
            // same pass that updates the app's resources, and everything the
            // overlays measure against reads displayMetrics.
            mainHandler.post { dispatch() }
        }

        override fun onLowMemory() { }
    }

    /** The display's size right now, in pixels. */
    fun screenSize(): Size {
        val metrics = appContext.resources.displayMetrics
        return Size(metrics.widthPixels, metrics.heightPixels)
    }

    /**
     * Start watching, taking the current screen as the baseline. Call once the
     * overlay's window is actually up; calling again while running only
     * re-baselines, which is what a window being re-added wants.
     */
    fun start() {
        lastSize = screenSize()
        if (registered) return
        appContext.registerComponentCallbacks(callbacks)
        registered = true
    }

    /** Stop watching. Safe to call when never started. */
    fun stop() {
        if (!registered) return
        runCatching { appContext.unregisterComponentCallbacks(callbacks) }
        registered = false
    }

    private fun dispatch() {
        if (!registered) return
        val current = screenSize()
        // Configuration changes fire for font scale, locale, dark mode and a
        // dozen other things. Only a screen that actually changed size moved
        // anybody's window.
        if (current == lastSize) return
        val previous = lastSize
        lastSize = current
        onScreenChanged(previous, current)
    }
}

/**
 * Where [value] sat along a [from]-long axis, moved to a [to]-long one.
 *
 * The closest thing to "where I left it" that survives the screen changing
 * shape, for positions the user chose rather than ones we compute.
 */
fun remapAcrossScreen(value: Int, from: Int, to: Int): Int =
    if (from <= 0) value else (value.toFloat() / from * to).toInt()
