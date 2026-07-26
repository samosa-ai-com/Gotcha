package com.gotcha.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProactiveSessionManagerTest {

    @Test
    fun `mergeEntities adds new entities and updates existing without dupes`() {
        val manager = ProactiveSessionManager(ttlMs = 45_000L)

        val entity1 = DetectedEntity(
            type = EntityType.PHONE,
            rawValue = "+1 415 555 2671",
            normalizedValue = "+14155552671",
            span = 0..14,
            confidence = 0.9f,
            actions = emptyList()
        )

        val itemsInitial = manager.mergeEntities(listOf(entity1), packageName = "com.app.one")
        assertEquals(1, itemsInitial.size)
        assertEquals("+14155552671", itemsInitial.first().entity.normalizedValue)

        // Merge same entity again -> size remains 1, updated timestamp
        val itemsUpdated = manager.mergeEntities(listOf(entity1), packageName = "com.app.one")
        assertEquals(1, itemsUpdated.size)
    }

    @Test
    fun `category counts map groups active entities correctly`() {
        val manager = ProactiveSessionManager()

        val phone = DetectedEntity(
            type = EntityType.PHONE,
            rawValue = "+1 415 555 2671",
            normalizedValue = "+14155552671",
            span = 0..14,
            confidence = 0.9f,
            actions = emptyList()
        )
        val url1 = DetectedEntity(
            type = EntityType.URL,
            rawValue = "https://example.com",
            normalizedValue = "https://example.com",
            span = 20..39,
            confidence = 0.9f,
            actions = emptyList()
        )
        val url2 = DetectedEntity(
            type = EntityType.URL,
            rawValue = "https://google.com",
            normalizedValue = "https://google.com",
            span = 45..63,
            confidence = 0.9f,
            actions = emptyList()
        )

        manager.mergeEntities(listOf(phone, url1, url2))
        val counts = manager.getCategoryCounts()

        assertEquals(2, counts[EntityType.URL])
        assertEquals(1, counts[EntityType.PHONE])
    }

    @Test
    fun `ttl expiration purges stale items`() {
        val manager = ProactiveSessionManager(ttlMs = 50L) // 50ms TTL

        val entity = DetectedEntity(
            type = EntityType.EMAIL,
            rawValue = "test@example.com",
            normalizedValue = "test@example.com",
            span = 0..16,
            confidence = 0.9f,
            actions = emptyList()
        )

        manager.mergeEntities(listOf(entity))
        assertEquals(1, manager.getActiveSessionItems().size)

        Thread.sleep(100L) // Sleep longer than 50ms TTL

        assertEquals(0, manager.getActiveSessionItems().size)
    }

    @Test
    fun `package change invalidates screen items`() {
        val manager = ProactiveSessionManager()

        val phone = DetectedEntity(
            type = EntityType.PHONE,
            rawValue = "+1 415 555 2671",
            normalizedValue = "+14155552671",
            span = 0..14,
            confidence = 0.9f,
            actions = emptyList()
        )

        manager.mergeEntities(listOf(phone), packageName = "com.app.first")
        assertEquals(1, manager.getActiveSessionItems().size)

        // Switch package to com.app.second
        val updated = manager.mergeEntities(emptyList(), packageName = "com.app.second")
        assertTrue(updated.isEmpty())
    }
}
