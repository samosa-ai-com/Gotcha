package com.gotcha.tools

import android.os.StatFs

class StorageTool {

    fun getStorageInfo(): ToolResult {
        return try {
            val internal = readPartition("/data")
            val external = try {
                val ext = readPartition("/storage/emulated/0")
                if (ext.total > 0 && ext.total != internal.total) ext else null
            } catch (_: Exception) { null }

            val message = buildString {
                append(
                    "Internal storage: ${format(
                        internal.used
                    )} used of ${format(internal.total)} total, ${format(internal.free)} free"
                )
                if (external != null) {
                    append(
                        "\nExternal / SD card: ${format(
                            external.used
                        )} used of ${format(external.total)} total, ${format(external.free)} free"
                    )
                }
                val pct = if (internal.total > 0) (internal.free * 100 / internal.total) else 100L
                if (pct < 10) {
                    append("\n⚠ Internal storage is critically low ($pct% free) — consider freeing up space.")
                } else if (pct < 20) {
                    append("\nInternal storage is running low ($pct% free).")
                }
            }
            ToolResult.ok(message)
        } catch (e: Exception) {
            ToolResult.error("Could not read storage info: ${e.message}")
        }
    }

    private data class Partition(val total: Long, val free: Long, val used: Long)

    private fun readPartition(path: String): Partition {
        val stat = StatFs(path)
        val total = stat.blockCountLong * stat.blockSizeLong
        val free = stat.availableBlocksLong * stat.blockSizeLong
        return Partition(total = total, free = free, used = total - free)
    }

    companion object {
        fun format(bytes: Long): String {
            val units = arrayOf("B", "KB", "MB", "GB", "TB")
            var value = bytes.coerceAtLeast(0).toDouble()
            var unit = 0
            while (value >= 1024 && unit < units.lastIndex) {
                value /= 1024
                unit++
            }
            return "%.1f %s".format(value, units[unit])
        }
    }
}
