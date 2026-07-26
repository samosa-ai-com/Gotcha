package com.gotcha.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `get_storage_info`. [StorageTool] takes no `Context`, so this is a plain JVM test —
 * it simply had no coverage before.
 */
class StorageToolTest {

    // ---- size formatting ----

    @Test
    fun `format uses the largest unit that fits`() {
        assertEquals("0.0 B", StorageTool.format(0))
        assertEquals("512.0 B", StorageTool.format(512))
        assertEquals("1.0 KB", StorageTool.format(1024))
        assertEquals("1.0 MB", StorageTool.format(1024L * 1024))
        assertEquals("1.0 GB", StorageTool.format(1024L * 1024 * 1024))
        assertEquals("1.0 TB", StorageTool.format(1024L * 1024 * 1024 * 1024))
    }

    @Test
    fun `format stops at terabytes rather than inventing a unit`() {
        val petabyte = 1024L * 1024 * 1024 * 1024 * 1024
        assertTrue(StorageTool.format(petabyte), StorageTool.format(petabyte).endsWith(" TB"))
    }

    @Test
    fun `format clamps negative sizes to zero`() {
        assertEquals("0.0 B", StorageTool.format(-1))
    }

    @Test
    fun `format rounds to one decimal place`() {
        assertEquals("1.5 KB", StorageTool.format(1536))
    }

    // ---- the tool itself ----

    @Test
    fun `getStorageInfo reports internal storage without throwing`() {
        // Reads the JVM host's /data via StatFs; the point is that the tool degrades to a
        // ToolResult either way rather than propagating an exception to the agent loop.
        val result = StorageTool().getStorageInfo()

        assertTrue(
            "expected a storage report or a clean error, got: ${result.message}",
            result.message.contains("Internal storage") || result.message.contains("Could not read storage info")
        )
    }
}
