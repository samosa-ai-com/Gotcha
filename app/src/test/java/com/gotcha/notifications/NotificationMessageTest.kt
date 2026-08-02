package com.gotcha.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationMessageTest {

    @Test
    fun `androidPriority maps high default low`() {
        assertEquals(4, NotificationMessage(id = "x", title = "t", body = "b", priority = "high").androidPriority)
        assertEquals(4, NotificationMessage(id = "x", title = "t", body = "b", priority = "HIGH").androidPriority)
        assertEquals(1, NotificationMessage(id = "x", title = "t", body = "b", priority = "low").androidPriority)
        assertEquals(0, NotificationMessage(id = "x", title = "t", body = "b", priority = null).androidPriority)
        assertEquals(0, NotificationMessage(id = "x", title = "t", body = "b", priority = "garbage").androidPriority)
    }

    @Test
    fun `deserializes the canonical envelope`() {
        val json = """
            {
              "messages": [
                {
                  "id": "2026-08-01-free-models-on-samosa-ai",
                  "title": "Free  models on Samosa AI",
                  "body": "Enjoy free models from Samosa AI to start your Gotchas journey.",
                  "category": "general",
                  "max_deliveries": 1,
                  "expires_at": "2026-08-26T05:00:00Z",
                  "priority": "default"
                }
              ],
              "etag": "abc123"
            }
        """.trimIndent()
        val envelope = kotlinx.serialization.json.Json {
            ignoreUnknownKeys = true
            isLenient = true
        }.decodeFromString(NotificationsEnvelope.serializer(), json)
        assertEquals(1, envelope.messages.size)
        val m = envelope.messages[0]
        assertEquals("2026-08-01-free-models-on-samosa-ai", m.id)
        assertEquals(1, m.maxDeliveries)
        assertEquals("general", m.category)
        assertEquals(0, m.androidPriority)
        assertTrue(m.url == null)
        assertEquals("abc123", envelope.etag)
    }
}
