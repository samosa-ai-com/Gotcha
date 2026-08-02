package com.gotcha.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import com.gotcha.data.SettingsRepository
import com.gotcha.ui.theme.Skins
import com.gotcha.ui.theme.overlaySkin

/**
 * Flashes a brief accent border around the screen after an agent screen read.
 * Fire-and-forget: [pulse] adds a non-touchable window, animates ~400 ms, then
 * removes it. No-op without the "Display over other apps" permission.
 */
class ScreenReadFlashOverlay(context: Context) {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val windowManager: WindowManager
        get() = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    @Volatile
    private var view: ScreenReadFlashView? = null
    private var animator: ValueAnimator? = null

    fun pulse() {
        mainHandler.post {
            if (!Settings.canDrawOverlays(appContext)) return@post
            removeView() // restart-safe: replace any stale instance
            val colors = overlaySkin(
                appContext,
                runCatching { SettingsRepository(appContext).load().skinId }
                    .getOrDefault(Skins.DEFAULT_ID)
            )
            val flash = ScreenReadFlashView(appContext, colors)
            try {
                windowManager.addView(flash, windowParams())
                view = flash
                animator = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = PULSE_MS
                    interpolator = DecelerateInterpolator()
                    addUpdateListener { a -> flash.pulse = a.animatedValue as Float }
                    addListener(object : android.animation.AnimatorListenerAdapter() {
                        override fun onAnimationEnd(a: android.animation.Animator?) { removeView() }
                    })
                    start()
                }
            } catch (_: Exception) {
                view = null
            }
        }
    }

    fun dismiss() {
        mainHandler.post {
            animator?.cancel()
            animator = null
            removeView()
        }
    }

    private fun removeView() {
        view?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {
                // Already detached; ignore.
            }
        }
        view = null
    }

    private fun windowParams(): WindowManager.LayoutParams {
        val type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
    }

    private companion object {
        const val PULSE_MS = 400L
    }
}
