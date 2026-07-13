package com.gotcha.llm

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests the in-memory cache path (context = null → no disk cache).
 * Deterministic expiry is exercised with a negative TTL so no clock control is needed.
 */
class LLMCacheTest {

    private fun response(text: String) = ChatResponse(
        choices = listOf(Choice(message = ChatMessage(role = "assistant", content = JsonPrimitive(text))))
    )

    @Test
    fun `put then get returns the cached response`() {
        val cache = LLMCache()
        cache.put("k1", response("answer"))
        assertEquals("answer", cache.get("k1")!!.choices.single().message.textContent)
    }

    @Test
    fun `get returns null for unknown keys`() {
        assertNull(LLMCache().get("missing"))
    }

    @Test
    fun `expired entries are evicted on get`() {
        val cache = LLMCache()
        cache.put("k1", response("stale"), ttlMs = -1)
        assertNull(cache.get("k1"))
    }

    @Test
    fun `least recently used entry is evicted at maxSize`() {
        val cache = LLMCache(maxSize = 2)
        cache.put("a", response("A"))
        cache.put("b", response("B"))
        // Touch "a" so "b" becomes the least recently used entry.
        assertNotNull(cache.get("a"))
        cache.put("c", response("C"))

        assertNull(cache.get("b"))
        assertNotNull(cache.get("a"))
        assertNotNull(cache.get("c"))
    }

    @Test
    fun `clear empties the cache`() {
        val cache = LLMCache()
        cache.put("k1", response("gone"))
        cache.clear()
        assertNull(cache.get("k1"))
    }

    @Test
    fun `entries within their TTL remain retrievable`() {
        val cache = LLMCache()
        cache.put("k1", response("fresh"), ttlMs = 60_000)
        assertNotNull(cache.get("k1"))
    }
}
