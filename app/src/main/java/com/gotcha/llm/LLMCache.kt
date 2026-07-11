package com.gotcha.llm

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

class LLMCache(
    context: Context? = null,
    private val maxSize: Int = 100,
    private val defaultTtlMs: Long = 5 * 60 * 1000
) {
    @Serializable
    private data class DiskEntry(
        val response: ChatResponse,
        val timestamp: Long,
        val ttlMs: Long
    )

    private data class CacheEntry(
        val response: ChatResponse,
        val timestamp: Long,
        val ttlMs: Long
    )

    private val json = Json { ignoreUnknownKeys = true }

    private val diskCacheDir: File? = context?.cacheDir
        ?.resolve("llm_cache")
        ?.apply { mkdirs() }

    private val cache = object : LinkedHashMap<String, CacheEntry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, CacheEntry>): Boolean =
            size > maxSize
    }

    @Synchronized
    fun get(key: String): ChatResponse? {
        cache[key]?.let { entry ->
            if (isExpired(entry.timestamp, entry.ttlMs)) {
                cache.remove(key)
                deleteDiskEntry(key)
                return null
            }
            return entry.response
        }

        val diskEntry = readDiskEntry(key) ?: return null
        if (isExpired(diskEntry.timestamp, diskEntry.ttlMs)) {
            deleteDiskEntry(key)
            return null
        }
        cache[key] = CacheEntry(diskEntry.response, diskEntry.timestamp, diskEntry.ttlMs)
        return diskEntry.response
    }

    @Synchronized
    fun put(key: String, response: ChatResponse, ttlMs: Long = defaultTtlMs) {
        val timestamp = System.currentTimeMillis()
        cache[key] = CacheEntry(response, timestamp, ttlMs)
        writeDiskEntry(key, DiskEntry(response, timestamp, ttlMs))
    }

    @Synchronized
    fun clear() {
        cache.clear()
        diskCacheDir?.listFiles()?.forEach { it.delete() }
    }

    private fun isExpired(timestamp: Long, ttlMs: Long): Boolean =
        System.currentTimeMillis() - timestamp > ttlMs

    private fun diskFile(key: String): File? = diskCacheDir?.resolve("$key.json")

    private fun readDiskEntry(key: String): DiskEntry? {
        val file = diskFile(key) ?: return null
        if (!file.exists()) return null
        return try {
            json.decodeFromString(DiskEntry.serializer(), file.readText())
        } catch (e: Exception) {
            file.delete()
            null
        }
    }

    private fun writeDiskEntry(key: String, entry: DiskEntry) {
        val file = diskFile(key) ?: return
        try {
            file.writeText(json.encodeToString(DiskEntry.serializer(), entry))
        } catch (e: Exception) {
            // Disk cache is best-effort; in-memory cache still holds the entry.
        }
    }

    private fun deleteDiskEntry(key: String) {
        diskFile(key)?.delete()
    }
}
