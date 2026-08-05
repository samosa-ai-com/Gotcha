package com.gotcha.agent

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScreenSnapshotTest {

    @Test
    fun `a solid black frame is mostly black`() {
        assertTrue(ScreenSnapshot.isMostlyBlack(encode(SolidBitmap(Color.BLACK))))
    }

    @Test
    fun `a solid white frame is not mostly black`() {
        assertFalse(ScreenSnapshot.isMostlyBlack(encode(SolidBitmap(Color.WHITE))))
    }

    @Test
    fun `a near-black frame is still mostly black`() {
        assertTrue(ScreenSnapshot.isMostlyBlack(encode(SolidBitmap(Color.rgb(5, 5, 5)))))
    }

    @Test
    fun `a dark frame with visible content is not blank`() {
        val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.BLACK)
        for (y in 40 until 88) {
            for (x in 40 until 88) {
                bitmap.setPixel(x, y, Color.WHITE)
            }
        }
        // Classification runs directly on the bitmap because Robolectric's
        // BitmapFactory does not faithfully reproduce per-pixel content when
        // decoding a JPEG.
        assertFalse("content on a black screen must not be dropped", ScreenSnapshot.classifyBlackness(bitmap))
        bitmap.recycle()
    }

    @Test
    fun `an evenly split black and white frame is not mostly black`() {
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.BLACK)
        for (y in 0 until 64) {
            for (x in 0 until 32) {
                bitmap.setPixel(x, y, Color.WHITE)
            }
        }
        assertFalse(ScreenSnapshot.classifyBlackness(bitmap))
        bitmap.recycle()
    }

    private fun SolidBitmap(color: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        return bitmap
    }

    private fun encode(bitmap: Bitmap): String {
        val stream = java.io.ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream)
        bitmap.recycle()
        return android.util.Base64.encodeToString(stream.toByteArray(), android.util.Base64.DEFAULT)
    }
}
