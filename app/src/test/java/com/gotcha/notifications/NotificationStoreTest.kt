package com.gotcha.notifications

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NotificationStoreTest {

    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val store get() = NotificationStore(ctx)

    private val pruneAfterMs = 30L * 24L * 60L * 60L * 1000L

    @Test
    fun `recordDelivery writes id, count and timestamp`() {
        store.recordDelivery("m1")
        assertTrue(store.deliveredIds().contains("m1"))
        assertEquals(1, store.deliveryCount("m1"))
        // The store doesn't expose lastDelivered publicly, but the prune
        // test below exercises it indirectly.
        val now = System.currentTimeMillis() + pruneAfterMs + 1L
        store.prune(now)
        assertFalse(store.deliveredIds().contains("m1"))
        assertEquals(0, store.deliveryCount("m1"))
    }

    @Test
    fun `prune removes only ids whose last delivery is older than the cutoff`() {
        store.recordDelivery("old", at = 1_000L)
        store.recordDelivery("recent", at = 1_000_000_000_000L)

        // Cutoff older than `old` but newer than `recent`'s timestamp.
        val now = 1_000L + pruneAfterMs + 1L
        store.prune(now)

        assertFalse("old should be pruned", store.deliveredIds().contains("old"))
        assertEquals(0, store.deliveryCount("old"))
        assertTrue("recent should survive", store.deliveredIds().contains("recent"))
        assertEquals(1, store.deliveryCount("recent"))
    }

    @Test
    fun `prune preserves the entire deliveredIds set when nothing is stale`() {
        store.recordDelivery("a")
        store.recordDelivery("b")
        store.recordDelivery("c")
        val now = System.currentTimeMillis() // everything is fresh
        store.prune(now)
        assertEquals(setOf("a", "b", "c"), store.deliveredIds())
    }

    @Test
    fun `prune handles multiple stale ids without corrupting the set`() {
        // Regression: the old code wrote KEY_DELIVERED_IDS inside the loop, so
        // the last-written set won and earlier survivors were dropped. Pruning
        // several at once should leave the survivors in place.
        store.recordDelivery("old-1", at = 1_000L)
        store.recordDelivery("keep", at = 1_000_000_000_000L)
        store.recordDelivery("old-2", at = 1_000L)
        store.recordDelivery("keep-2", at = 1_000_000_000_000L)

        val now = 1_000L + pruneAfterMs + 1L
        store.prune(now)

        assertEquals(setOf("keep", "keep-2"), store.deliveredIds())
        assertEquals(0, store.deliveryCount("old-1"))
        assertEquals(0, store.deliveryCount("old-2"))
        assertEquals(1, store.deliveryCount("keep"))
        assertEquals(1, store.deliveryCount("keep-2"))
    }
}
