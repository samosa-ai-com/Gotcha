package com.gotcha.service

import android.graphics.Rect

/**
 * An item stored in the active proactive session list.
 */
data class ProactiveSessionItem(
    val entity: DetectedEntity,
    val firstSeenTimestamp: Long = System.currentTimeMillis(),
    var lastUpdatedTimestamp: Long = System.currentTimeMillis(),
    var boundsOnScreen: Rect? = null
)

/**
 * Manages the stable, deduplicated, non-flickering accumulator of detected entities
 * across screen scans, clipboard updates, and notifications.
 */
class ProactiveSessionManager(
    private val ttlMs: Long = 45_000L
) {
    private val itemsMap = LinkedHashMap<String, ProactiveSessionItem>()
    private var lastPackageName: String? = null

    /**
     * Merge new detected entities into the session.
     * Re-detected entities update their timestamp without resetting order;
     * new entities append. Expired items beyond [ttlMs] are purged.
     */
    @Synchronized
    fun mergeEntities(
        newEntities: List<DetectedEntity>,
        packageName: String? = null
    ): List<ProactiveSessionItem> {
        val now = System.currentTimeMillis()

        // If package changed to a different foreground app, clear old screen entities
        if (packageName != null && lastPackageName != null && packageName != lastPackageName) {
            itemsMap.entries.removeIf { (_, item) ->
                item.entity.type != EntityType.OTP && item.entity.type != EntityType.CHAT_REPLY
            }
        }
        if (packageName != null) {
            lastPackageName = packageName
        }

        // Purge expired items
        itemsMap.entries.removeIf { (_, item) -> now - item.lastUpdatedTimestamp > ttlMs }

        // When new QR_CODE or BARCODE entities are discovered, clear older ones
        val hasNewQrOrBarcode = newEntities.any { it.type == EntityType.QR_CODE || it.type == EntityType.BARCODE }
        if (hasNewQrOrBarcode) {
            itemsMap.entries.removeIf { (_, item) ->
                item.entity.type == EntityType.QR_CODE || item.entity.type == EntityType.BARCODE
            }
        }

        // Merge new entities
        for (entity in newEntities) {
            val key = "${entity.type}:${entity.normalizedValue}"
            val existing = itemsMap[key]
            if (existing != null) {
                itemsMap[key] = existing.copy(
                    entity = entity,
                    lastUpdatedTimestamp = now,
                    boundsOnScreen = existing.boundsOnScreen
                )
            } else {
                itemsMap[key] = ProactiveSessionItem(
                    entity = entity,
                    firstSeenTimestamp = now,
                    lastUpdatedTimestamp = now
                )
            }
        }

        return getActiveSessionItems()
    }

    /**
     * Get all active session items ordered by entity priority and timestamp.
     */
    @Synchronized
    fun getActiveSessionItems(): List<ProactiveSessionItem> {
        val now = System.currentTimeMillis()
        itemsMap.entries.removeIf { (_, item) -> now - item.lastUpdatedTimestamp > ttlMs }
        return itemsMap.values.sortedWith(
            compareByDescending<ProactiveSessionItem> { it.entity.type.basePriority }
                .thenByDescending { it.lastUpdatedTimestamp }
        )
    }

    /**
     * Get entity counts grouped by [EntityType] for discoverability badges.
     */
    @Synchronized
    fun getCategoryCounts(): Map<EntityType, Int> {
        val active = getActiveSessionItems()
        return active.groupingBy { it.entity.type }.eachCount()
    }

    /**
     * Clear all session state.
     */
    @Synchronized
    fun clear() {
        itemsMap.clear()
        lastPackageName = null
    }
}
