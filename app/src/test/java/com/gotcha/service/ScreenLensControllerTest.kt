package com.gotcha.service

import android.content.Context
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The Lens crop canvas must never take window focus (issue #149): a focusable
 * full-screen overlay becomes the active window, and on some phones the app
 * underneath drops out of the accessibility window list, so hostRoot() finds
 * nothing and Lens reads an empty screen. The view-level half is pinned in
 * ScreenCropOverlayViewTest; this pins the load-bearing window flags.
 */
@RunWith(RobolectricTestRunner::class)
class ScreenLensControllerTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun controller() = ScreenLensController(
        context,
        CoroutineScope(Dispatchers.Unconfined),
        {},
        {},
        { _, _ -> },
        {},
        {}
    )

    @Test
    fun `the crop canvas never takes window focus`() {
        val flags = controller().cropOverlayParams().flags
        assertTrue(
            "crop overlay must carry FLAG_NOT_FOCUSABLE",
            flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0
        )
    }
}
