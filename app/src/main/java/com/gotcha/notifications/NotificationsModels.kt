package com.gotcha.notifications

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire DTOs for `GET /v1/gotcha/notifications` on `api.samosa-ai.example`.
 *
 * The server is stateless w.r.t. per-device delivery: all dedupe is enforced
 * on the client against [NotificationStore.deliveredIds] and the per-id
 * counter. [NotificationMessage.maxDeliveries] is a server hint, not a
 * server-side counter.
 */
@Serializable
data class NotificationsEnvelope(
    val messages: List<NotificationMessage> = emptyList(),
    val etag: String = ""
)

@Serializable
data class NotificationMessage(
    val id: String,
    val title: String,
    val body: String,
    val url: String? = null,
    val category: String? = null,
    @SerialName("max_deliveries")
    val maxDeliveries: Int = 1,
    @SerialName("ttl_seconds")
    val ttlSeconds: Long? = null,
    @SerialName("min_app_version")
    val minAppVersion: String? = null,
    @SerialName("expires_at")
    val expiresAt: String? = null,
    val priority: String? = null
) {
    /** Lower-case priority mapped to Android importance; defaults to default-importance. */
    val androidPriority: Int
        get() = when (priority?.lowercase()?.trim()) {
            "high" -> 4 // NotificationCompat.PRIORITY_HIGH
            "low" -> 1 // NotificationCompat.PRIORITY_LOW
            else -> 0 // NotificationCompat.PRIORITY_DEFAULT
        }
}

/**
 * Payload model for presenting a full notification in a UI dialog.
 */
data class NotificationPayload(
    val id: Int,
    val title: String,
    val body: String,
    val url: String? = null
)
