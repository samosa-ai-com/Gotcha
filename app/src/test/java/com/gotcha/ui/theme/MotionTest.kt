package com.gotcha.ui.theme

import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The accessibility "Remove animations" preference reaches the app as an
 * animator duration scale of zero. Compose does not consult it for us, and
 * neither does the View layer, so [animationsEnabled] is the one place that
 * asks — for the chat screen through [rememberAnimationsEnabled], and for the
 * overlays directly, because a floating window that keeps pulsing over every
 * other app is the worst possible place to have missed it.
 */
@RunWith(RobolectricTestRunner::class)
class MotionTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun setScale(scale: Float) {
        Settings.Global.putFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            scale
        )
    }

    @Test
    fun `a zero duration scale means the user asked for no animation`() {
        setScale(0f)
        assertFalse(animationsEnabled(context))
    }

    @Test
    fun `any positive scale means animation is wanted`() {
        setScale(1f)
        assertTrue(animationsEnabled(context))
        setScale(0.5f)
        assertTrue(animationsEnabled(context))
        setScale(10f)
        assertTrue(animationsEnabled(context))
    }

    /**
     * Unset on plenty of devices. Defaulting to *off* would silently strip the
     * motion from every one of them, which is the more expensive way to be
     * wrong than defaulting to on.
     */
    @Test
    fun `an unset scale animates`() {
        assertTrue(animationsEnabled(context))
    }
}
