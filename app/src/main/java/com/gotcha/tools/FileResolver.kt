package com.gotcha.tools

import android.content.Context
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat
import java.io.File

/**
 * Shared path resolution and permission checking for all file-related tools.
 *
 * Accepts any absolute or relative path. Relative paths are resolved against a
 * configurable [defaultWorkingDir]. Permission checks are based on the actual
 * canonical path — not on artificial "named roots".
 *
 * Permission model by API level:
 * - API 23-29: READ_EXTERNAL_STORAGE / WRITE_EXTERNAL_STORAGE (runtime)
 * - API 30-32: MANAGE_EXTERNAL_STORAGE (special access) for broad; READ/WRITE per media dir
 * - API 33+:   READ_MEDIA_IMAGES + MANAGE_EXTERNAL_STORAGE for broad; per-media-dir per type
 * - App sandbox: always readable/writable
 */
class FileResolver(private val context: Context) {

    companion object {
        private const val ALL_FILES_ACCESS_GUIDE =
            "This operation needs \"All files access\" to read or write outside the app sandbox. " +
            "Go to Settings → Permissions → All Files Access and enable it, then ask again."

        private const val READ_STORAGE_GUIDE =
            "This operation needs storage read permission. " +
            "Go to Settings → Permissions → Storage Read and grant it, then ask again."

        private const val WRITE_STORAGE_GUIDE =
            "This operation needs storage write permission. " +
            "Go to Settings → Permissions → Storage Write and grant it, then ask again."

        var WORKING_DIR_BASE: String = "/storage/emulated/0/Gotcha"

        fun formatSizeStatic(bytes: Long): String = when {
            bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(bytes.toDouble() / (1024 * 1024 * 1024))
            bytes >= 1024L * 1024 -> "%.1f MB".format(bytes.toDouble() / (1024 * 1024))
            bytes >= 1024L -> "${bytes / 1024} KB"
            else -> "$bytes B"
        }
    }

    private val appSandboxPaths: Set<String> by lazy {
        setOf(
            context.filesDir.absolutePath,
            context.cacheDir.absolutePath,
            context.getExternalFilesDir(null)?.absolutePath ?: ""
        ).filter { it.isNotEmpty() }.toSet()
    }

    fun canonicalPath(input: String, cwd: String = WORKING_DIR_BASE): String {
        val path = if (input.startsWith("/")) input else File(cwd, input).absolutePath
        return File(path).canonicalPath
    }

    sealed class ResolveResult {
        data class Ok(val file: File) : ResolveResult()
        data class PermissionNeeded(val result: ToolResult) : ResolveResult()
        data class Error(val message: String) : ResolveResult()
    }

    fun resolveForRead(path: String, cwd: String = WORKING_DIR_BASE): ResolveResult {
        val canonical = canonicalPath(path, cwd)
        if (isInAppSandbox(canonical)) return ResolveResult.Ok(File(canonical))
        return when {
            // API 30+: need MANAGE_EXTERNAL_STORAGE for broad shared-storage read
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager() -> {
                if (isBelowExternalStorage(canonical))
                    ResolveResult.PermissionNeeded(
                        ToolResult.permissionNeeded(ToolResult.ALL_FILES_ACCESS, ALL_FILES_ACCESS_GUIDE)
                    )
                else ResolveResult.Ok(File(canonical))
            }
            // API 23-29: need READ_EXTERNAL_STORAGE for shared-storage read
            Build.VERSION.SDK_INT in 23..29 -> {
                val has = hasPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                if (!has && isBelowExternalStorage(canonical))
                    ResolveResult.PermissionNeeded(
                        ToolResult.permissionNeeded(
                            android.Manifest.permission.READ_EXTERNAL_STORAGE, READ_STORAGE_GUIDE
                        )
                    )
                else ResolveResult.Ok(File(canonical))
            }
            else -> ResolveResult.Ok(File(canonical))
        }
    }

    fun resolveForWrite(path: String, cwd: String = WORKING_DIR_BASE): ResolveResult {
        val canonical = canonicalPath(path, cwd)
        if (isInAppSandbox(canonical)) return ResolveResult.Ok(File(canonical))
        return when {
            // API 30+: need MANAGE_EXTERNAL_STORAGE for shared-storage write
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager() -> {
                ResolveResult.PermissionNeeded(
                    ToolResult.permissionNeeded(ToolResult.ALL_FILES_ACCESS, ALL_FILES_ACCESS_GUIDE)
                )
            }
            // API 23-29: need WRITE_EXTERNAL_STORAGE for shared-storage write
            Build.VERSION.SDK_INT in 23..29 -> {
                val has = hasPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                if (!has && isBelowExternalStorage(canonical))
                    ResolveResult.PermissionNeeded(
                        ToolResult.permissionNeeded(
                            android.Manifest.permission.WRITE_EXTERNAL_STORAGE, WRITE_STORAGE_GUIDE
                        )
                    )
                else ResolveResult.Ok(File(canonical))
            }
            else -> ResolveResult.Ok(File(canonical))
        }
    }

    fun checkReadPermission(file: File): ToolResult? {
        val canonical = file.canonicalPath
        if (isInAppSandbox(canonical)) return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            if (isBelowExternalStorage(canonical))
                return ToolResult.permissionNeeded(ToolResult.ALL_FILES_ACCESS, ALL_FILES_ACCESS_GUIDE)
        }
        if (Build.VERSION.SDK_INT in 23..29 && isBelowExternalStorage(canonical)) {
            val perm = android.Manifest.permission.READ_EXTERNAL_STORAGE
            if (!hasPermission(perm)) return ToolResult.permissionNeeded(perm, READ_STORAGE_GUIDE)
        }
        return null
    }

    fun checkWritePermission(file: File): ToolResult? {
        val canonical = file.canonicalPath
        if (isInAppSandbox(canonical)) return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            return ToolResult.permissionNeeded(ToolResult.ALL_FILES_ACCESS, ALL_FILES_ACCESS_GUIDE)
        }
        if (Build.VERSION.SDK_INT in 23..29 && isBelowExternalStorage(canonical)) {
            val perm = android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            if (!hasPermission(perm)) return ToolResult.permissionNeeded(perm, WRITE_STORAGE_GUIDE)
        }
        return null
    }

    fun formatSize(bytes: Long): String = formatSizeStatic(bytes)

    fun detectMime(bytes: ByteArray): String? {
        if (bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() && bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()) return "image/png"
        if (bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()) return "image/jpeg"
        if (bytes.size >= 3 && bytes[0] == 0x47.toByte() && bytes[1] == 0x49.toByte() && bytes[2] == 0x46.toByte()) return "image/gif"
        if (bytes.size >= 2 && bytes[0] == 0x42.toByte() && bytes[1] == 0x4D.toByte()) return "image/bmp"
        if (bytes.size >= 12 && startsWith(bytes.copyOfRange(0,4), byteArrayOf(0x52,0x49,0x46,0x46)) &&
            startsWith(bytes.copyOfRange(8,12), byteArrayOf(0x57,0x45,0x42,0x50))) return "image/webp"
        if (bytes.size >= 5 && bytes[0] == 0x25.toByte() && bytes[1] == 0x50.toByte() && bytes[2] == 0x44.toByte() && bytes[3] == 0x46.toByte() && bytes[4] == 0x2D.toByte()) return "application/pdf"
        if (bytes.size >= 4 && startsWith(bytes.copyOfRange(0,4), byteArrayOf(0x50,0x4B,0x03,0x04))) return "application/zip"
        if (bytes.size >= 28 && startsWith(bytes.copyOfRange(0,5), byteArrayOf(0x52,0x61,0x72,0x21,0x1A))) return "application/x-rar-compressed"
        if (bytes.size >= 3 && startsWith(bytes.copyOfRange(0,3), byteArrayOf(0x1F.toByte(), 0x8B.toByte(), 0x08.toByte()))) return "application/gzip"
        if (bytes.size >= 4 && startsWith(bytes.copyOfRange(0,4), byteArrayOf(0x2E,0x73,0x6E,0x64))) return "audio/basic"
        if (bytes.size >= 4 && bytes[0] == 0x4D.toByte() && bytes[1] == 0x54.toByte() && bytes[2] == 0x68.toByte() && bytes[3] == 0x64.toByte()) return "audio/midi"
        if (bytes.size >= 4 && startsWith(bytes.copyOfRange(0,4), byteArrayOf(0x66,0x74,0x79,0x70))) return "video/mp4"
        return null
    }

    fun isImageMime(mime: String): Boolean = mime.startsWith("image/")
    fun isArchiveMime(mime: String): Boolean = mime == "application/zip" || mime == "application/x-rar-compressed" || mime == "application/gzip"
    fun isDocumentMime(mime: String): Boolean = mime == "application/pdf"

    val IMAGE_DATA_PREFIX = "IMAGE_DATA:"

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun isInAppSandbox(canonical: String): Boolean {
        return appSandboxPaths.any { canonical.startsWith(it) }
    }

    private fun isBelowExternalStorage(canonical: String): Boolean {
        val ext = Environment.getExternalStorageDirectory().absolutePath
        return canonical.startsWith(ext)
    }

    private fun startsWith(data: ByteArray, prefix: ByteArray): Boolean {
        if (data.size < prefix.size) return false
        for (i in prefix.indices) {
            if (data[i] != prefix[i]) return false
        }
        return true
    }
}
