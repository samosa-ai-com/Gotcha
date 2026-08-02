package com.gotcha.service

import android.graphics.Rect
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins the 0.5f [REGION_TEXT_COVERAGE] boundary in
 * [GotchaAccessibilityService.substantiallyInside]. Boundary testing: exactly
 * 50% inclusion is the trip-wire that decides whether a node's text shows up
 * in the Lens "Extracted Text" card.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AccessibilitySubstantiallyInsideTest {

    private fun rectOf(x: Int, y: Int, w: Int, h: Int) = Rect(x, y, x + w, y + h)

    @Test
    fun `empty bounds are rejected`() {
        val bounds = Rect()
        val region = rectOf(0, 0, 100, 100)
        assertFalse(GotchaAccessibilityService.substantiallyInside(bounds, region))
    }

    @Test
    fun `zero-width bounds are rejected`() {
        val bounds = rectOf(50, 50, 0, 100)
        val region = rectOf(0, 0, 200, 200)
        assertFalse(GotchaAccessibilityService.substantiallyInside(bounds, region))
    }

    @Test
    fun `zero-height bounds are rejected`() {
        val bounds = rectOf(50, 50, 100, 0)
        val region = rectOf(0, 0, 200, 200)
        assertFalse(GotchaAccessibilityService.substantiallyInside(bounds, region))
    }

    @Test
    fun `disjoint bounds are rejected`() {
        val bounds = rectOf(0, 0, 50, 50)
        val region = rectOf(100, 100, 50, 50)
        assertFalse(GotchaAccessibilityService.substantiallyInside(bounds, region))
    }

    @Test
    fun `partial overlap below 50 percent is rejected`() {
        val bounds = rectOf(0, 0, 100, 100)
        val region = rectOf(50, 50, 100, 100)
        // Intersection is 50x50 = 2500 of 10000 (25%). Below threshold.
        assertFalse(GotchaAccessibilityService.substantiallyInside(bounds, region))
    }

    @Test
    fun `partial overlap exactly at 50 percent boundary is accepted`() {
        val bounds = rectOf(0, 0, 100, 100)
        val region = rectOf(0, 0, 100, 50)
        // Intersection is 100x50 = 5000 of 10000 (50%). At threshold.
        assertTrue(GotchaAccessibilityService.substantiallyInside(bounds, region))
    }

    @Test
    fun `full containment is accepted`() {
        val bounds = rectOf(10, 10, 20, 20)
        val region = rectOf(0, 0, 100, 100)
        assertTrue(GotchaAccessibilityService.substantiallyInside(bounds, region))
    }

    @Test
    fun `boundary equals region is accepted`() {
        val bounds = rectOf(0, 0, 100, 100)
        val region = rectOf(0, 0, 100, 100)
        assertTrue(GotchaAccessibilityService.substantiallyInside(bounds, region))
    }

    @Test
    fun `41 percent overlap is rejected`() {
        val bounds = rectOf(0, 0, 100, 100)
        val region = rectOf(0, 0, 59, 70)
        // Intersection is 59x70 = 4130 of 10000 (41.3%). Below 50%.
        assertFalse(GotchaAccessibilityService.substantiallyInside(bounds, region))
    }

    @Test
    fun `custom coverage threshold overrides default`() {
        val bounds = rectOf(0, 0, 100, 100)
        val region = rectOf(0, 0, 100, 50)
        // At 50% threshold, exactly the boundary. With a 30% threshold, accepted.
        assertFalse(GotchaAccessibilityService.substantiallyInside(bounds, region, coverage = 0.51f))
        assertTrue(GotchaAccessibilityService.substantiallyInside(bounds, region, coverage = 0.49f))
    }
}
