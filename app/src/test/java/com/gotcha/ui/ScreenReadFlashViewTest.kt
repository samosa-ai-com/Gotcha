package com.gotcha.ui

import android.app.AppOpsManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import android.view.View
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import com.gotcha.ui.theme.Skins
import com.gotcha.ui.theme.overlaySkin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowWindowManagerImpl

/**
 * The screen-read pulse must follow the active skin, or it silently wears a
 * stale accent after a theme change — the same class of bug the Lens overlay
 * caught with hardcoded colours.
 */
@RunWith(RobolectricTestRunner::class)
class ScreenReadFlashViewTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun viewFor(skinId: String) = ScreenReadFlashView(
        context,
        colors = overlaySkin(context, skinId)
    )

    @Test
    fun `border and glow follow the skin accent`() {
        val orchid = viewFor(Skins.Orchid.id)
        val vellum = viewFor(Skins.Vellum.id)

        assertNotEquals("border is hardcoded across skins", orchid.borderColor(), vellum.borderColor())
        assertNotEquals("glow is hardcoded across skins", orchid.glowColor(), vellum.glowColor())
        // Both derive from the accent, at their fixed alphas.
        assertEquals(0xB0, Color.alpha(orchid.borderColor()))
        assertEquals(0x66, Color.alpha(orchid.glowColor()))
    }

    @Test
    fun `drawing at any pulse progress never throws`() {
        val view = viewFor(Skins.DEFAULT_ID)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, WIDTH, HEIGHT)
        val canvas = Canvas(Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888))
        // The endpoints are invisible by contract; the mid-pulse peak draws.
        for (progress in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
            view.pulse = progress
            view.draw(canvas)
        }
    }

    @Test
    fun `pulse is a no-op without draw-overlays permission`() {
        // OP_SYSTEM_ALERT_WINDOW is hidden framework API (value 24); AppOpsManager
        // in Robolectric defaults to allowed, so pin it to MODE_ERRORED first.
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        shadowOf(appOps).setMode(
            OP_SYSTEM_ALERT_WINDOW,
            context.applicationInfo.uid,
            context.packageName,
            AppOpsManager.MODE_ERRORED
        )
        val overlay = ScreenReadFlashOverlay(context)
        overlay.pulse()
        shadowOf(Looper.getMainLooper()).idle()

        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val addedViews = (shadowOf(wm) as ShadowWindowManagerImpl).getViews()
        assertTrue("pulse must not add a window without draw-overlays", addedViews.isEmpty())
    }

    private companion object {
        const val WIDTH = 1080
        const val HEIGHT = 1920
        const val OP_SYSTEM_ALERT_WINDOW = 24
    }
}
