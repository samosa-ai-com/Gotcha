package com.gotcha.tools

import android.content.Context
import android.os.Environment
import android.os.StatFs
import java.io.File

class StorageTool(private val context: Context) {

    fun getStorageInfo(): ToolResult {
        return try {
            val stat = StatFs(Environment.getDataDirectory().path)
            val total = stat.blockCountLong * stat.blockSizeLong
            val free = stat.availableBlocksLong * stat.blockSizeLong
            val used = total - free
            ToolResult.ok(
                "Internal storage: ${format(used)} used of ${format(total)} total, ${format(free)} free."
            )
        } catch (e: Exception) {
            ToolResult.error("Could not read storage info: ${e.message}")
        }
    }

    fun clearAppCache(): ToolResult {
        return try {
            val dirs = listOfNotNull(context.cacheDir, context.externalCacheDir)
            val before = dirs.sumOf { dirSize(it) }
            dirs.forEach { dir ->
                dir.listFiles()?.forEach { it.deleteRecursively() }
            }
            val after = dirs.sumOf { dirSize(it) }
            ToolResult.ok("Cleared the app cache, freeing ${format(before - after)}.")
        } catch (e: Exception) {
            ToolResult.error("Could not clear cache: ${e.message}")
        }
    }

    private fun dirSize(dir: File): Long =
        dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }

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
