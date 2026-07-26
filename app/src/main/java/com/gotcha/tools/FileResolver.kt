package com.gotcha.tools

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * Shared path resolution and permission checking for all file-related tools.
 *
 * Accepts any absolute or relative path. Relative paths are resolved against a
 * configurable [defaultWorkingDir]. Permission checks are based on the actual
 * canonical path — not on artificial "named roots".
 *
 * Permission model by API level (as implemented here):
 * - API 23-29: READ_EXTERNAL_STORAGE / WRITE_EXTERNAL_STORAGE (runtime) for any path
 *              below external storage.
 * - API 30+:   MANAGE_EXTERNAL_STORAGE ("All files access") for any path below external
 *              storage. READ_MEDIA_* / scoped per-media-dir access is not used.
 * - App sandbox (filesDir, cacheDir, getExternalFilesDir): always readable/writable,
 *   no permission required on any API level.
 */
class FileResolver(private val context: Context) {

    companion object {
        const val IMAGE_DATA_PREFIX = "IMAGE_DATA:"

        private const val ALL_FILES_ACCESS_GUIDE =
            "This operation needs \"All files access\" to read or write outside the app sandbox. " +
                "Go to Settings → Permissions → All Files Access and enable it, then ask again."

        private var workingDirOverride: String? = null

        /**
         * Working directory for relative paths. Resolved lazily rather than at
         * class-load time: touching [com.gotcha.data.GotchaStorage.root] pulls in
         * `Environment.getExternalStorageDirectory()`, which throws under the
         * android.jar stubs used by JVM unit tests, and would otherwise pin a
         * value before [com.gotcha.agent.AgentEngine] sets the per-session dir.
         */
        var WORKING_DIR_BASE: String
            get() = workingDirOverride ?: com.gotcha.data.GotchaStorage.root().absolutePath
            set(value) { workingDirOverride = value }

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
            // Need MANAGE_EXTERNAL_STORAGE for broad shared-storage read
            !Environment.isExternalStorageManager() -> {
                if (isBelowExternalStorage(canonical)) {
                    ResolveResult.PermissionNeeded(
                        ToolResult.permissionNeeded(ToolResult.ALL_FILES_ACCESS, ALL_FILES_ACCESS_GUIDE)
                    )
                } else {
                    ResolveResult.Ok(File(canonical))
                }
            }
            else -> ResolveResult.Ok(File(canonical))
        }
    }

    fun resolveForWrite(path: String, cwd: String = WORKING_DIR_BASE): ResolveResult {
        val canonical = canonicalPath(path, cwd)
        if (isInAppSandbox(canonical)) return ResolveResult.Ok(File(canonical))
        return when {
            // Need MANAGE_EXTERNAL_STORAGE for shared-storage write
            !Environment.isExternalStorageManager() -> {
                ResolveResult.PermissionNeeded(
                    ToolResult.permissionNeeded(ToolResult.ALL_FILES_ACCESS, ALL_FILES_ACCESS_GUIDE)
                )
            }
            else -> ResolveResult.Ok(File(canonical))
        }
    }

    fun checkReadPermission(file: File): ToolResult? {
        val canonical = file.canonicalPath
        if (isInAppSandbox(canonical)) return null
        if (!Environment.isExternalStorageManager() && isBelowExternalStorage(canonical)) {
            return ToolResult.permissionNeeded(ToolResult.ALL_FILES_ACCESS, ALL_FILES_ACCESS_GUIDE)
        }
        return null
    }

    fun checkWritePermission(file: File): ToolResult? {
        val canonical = file.canonicalPath
        if (isInAppSandbox(canonical)) return null
        if (!Environment.isExternalStorageManager()) {
            return ToolResult.permissionNeeded(ToolResult.ALL_FILES_ACCESS, ALL_FILES_ACCESS_GUIDE)
        }
        return null
    }

    fun formatSize(bytes: Long): String = formatSizeStatic(bytes)

    /** A magic-byte signature: minimum file size, required prefix bytes, resulting MIME type. */
    private class MagicSignature(val minSize: Int, val prefix: ByteArray, val mime: String)

    private val magicSignatures = listOf(
        MagicSignature(8, byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47), "image/png"),
        MagicSignature(3, byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()), "image/jpeg"),
        MagicSignature(3, byteArrayOf(0x47, 0x49, 0x46), "image/gif"),
        MagicSignature(2, byteArrayOf(0x42, 0x4D), "image/bmp"),
        MagicSignature(5, byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2D), "application/pdf"),
        MagicSignature(4, byteArrayOf(0x50, 0x4B, 0x03, 0x04), "application/zip"),
        MagicSignature(28, byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A), "application/x-rar-compressed"),
        MagicSignature(3, byteArrayOf(0x1F, 0x8B.toByte(), 0x08), "application/gzip"),
        MagicSignature(4, byteArrayOf(0x2E, 0x73, 0x6E, 0x64), "audio/basic"),
        MagicSignature(4, byteArrayOf(0x4D, 0x54, 0x68, 0x64), "audio/midi"),
        MagicSignature(4, byteArrayOf(0x66, 0x74, 0x79, 0x70), "video/mp4")
    )

    fun detectMime(bytes: ByteArray): String? {
        // webp needs a two-part check: RIFF header plus WEBP tag at offset 8.
        if (bytes.size >= 12 && startsWith(bytes, byteArrayOf(0x52, 0x49, 0x46, 0x46)) &&
            startsWith(bytes.copyOfRange(8, 12), byteArrayOf(0x57, 0x45, 0x42, 0x50))
        ) {
            return "image/webp"
        }
        return magicSignatures
            .firstOrNull { bytes.size >= it.minSize && startsWith(bytes, it.prefix) }
            ?.mime
    }

    fun isImageMime(mime: String): Boolean = mime.startsWith("image/")
    fun isArchiveMime(
        mime: String
    ): Boolean = mime == "application/zip" || mime == "application/x-rar-compressed" || mime == "application/gzip"
    fun isDocumentMime(mime: String): Boolean = mime == "application/pdf"

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
