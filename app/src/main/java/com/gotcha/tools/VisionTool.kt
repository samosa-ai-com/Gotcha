package com.gotcha.tools

import android.content.Context
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Base64
import java.io.File

/**
 * Tool for reading image files and making their contents visible to the LLM.
 *
 * Returns a structured result with the [IMAGE_DATA_PREFIX] marker that the tool
 * loop in ChatViewModel detects and injects as vision content (user role with
 * image_url) so the model can "see" the image.
 */
class VisionTool(private val context: Context) {

    companion object {
        const val IMAGE_DATA_PREFIX = "IMAGE_DATA:"
        private const val MAX_IMAGE_BYTES = 20 * 1024 * 1024

        private val SUPPORTED_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")

        private val MAGIC_PNG = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
        private val MAGIC_JPEG = byteArrayOf(-1, -40, -1)
        private val MAGIC_GIF = byteArrayOf(71, 73, 70, 56)
        private val MAGIC_WEBP_RIFF = byteArrayOf(82, 73, 70, 70)
        private val MAGIC_WEBP_WEBP = byteArrayOf(87, 69, 66, 80)
        private val MAGIC_BMP = byteArrayOf(66, 77)
    }

    private val sandboxRoots: Map<String, File?> by lazy {
        mapOf(
            "files" to context.filesDir,
            "cache" to context.cacheDir,
            "external" to context.getExternalFilesDir(null)
        )
    }

    private val publicRoots: Map<String, File> by lazy {
        mapOf(
            "downloads" to Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "pictures" to Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            "dcim" to Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
            "documents" to Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        )
    }

    private val manageRoots: Map<String, File> by lazy {
        mapOf("storage" to Environment.getExternalStorageDirectory())
    }

    private val allowedRootNames: String
        get() = (sandboxRoots.keys + publicRoots.keys + manageRoots.keys).joinToString(", ")

    fun readImage(path: String): ToolResult {
        val normalized = path.trim().trimStart('/')
        val rootName = normalized.substringBefore('/').lowercase()
        val rest = normalized.substringAfter('/', "")

        val root: File = when {
            sandboxRoots.containsKey(rootName) -> sandboxRoots[rootName] ?: return badPath(path)
            manageRoots.containsKey(rootName) -> {
                val perm = checkAllFilesAccess()
                if (perm != null) return perm
                manageRoots.getValue(rootName)
            }
            publicRoots.containsKey(rootName) -> {
                val perm = checkMediaPermission()
                if (perm != null) return perm
                publicRoots.getValue(rootName)
            }
            else -> return badPath(path)
        }

        val target = if (rest.isEmpty()) root else File(root, rest)
        val canonicalRoot = root.canonicalPath
        val canonicalTarget = target.canonicalPath
        if (canonicalTarget != canonicalRoot && !canonicalTarget.startsWith("$canonicalRoot/")) {
            return ToolResult.error("Path '$path' is outside its allowed root.")
        }

        if (!target.exists()) return ToolResult.error("File '$path' does not exist.")
        if (!target.isFile) return ToolResult.error("'$path' is not a regular file.")

        val ext = target.extension.lowercase()
        if (ext !in SUPPORTED_EXTENSIONS) {
            return ToolResult.error(
                "Unsupported image format '.$ext'. Supported: ${SUPPORTED_EXTENSIONS.joinToString(", ")}"
            )
        }

        if (target.length() > MAX_IMAGE_BYTES) {
            return ToolResult.error(
                "Image too large (${target.length() / 1024 / 1024} MB). Max: ${MAX_IMAGE_BYTES / 1024 / 1024} MB."
            )
        }

        return try {
            val bytes = target.readBytes()

            val mime = detectMime(bytes)
            if (mime == null) {
                return ToolResult.error("File '$path' is not a recognized image format.")
            }

            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            if (opts.outWidth <= 0 || opts.outHeight <= 0) {
                return ToolResult.error("File '$path' could not be decoded as an image.")
            }

            val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            val displayName = target.name

            ToolResult.ok(
                "$IMAGE_DATA_PREFIX$mime:${opts.outWidth}x${opts.outHeight}:${bytes.size}:$displayName:$base64"
            )
        } catch (e: Exception) {
            ToolResult.error("Could not read image '$path': ${e.message}")
        }
    }

    private fun detectMime(bytes: ByteArray): String? {
        return when {
            startsWith(bytes, MAGIC_PNG) -> "image/png"
            startsWith(bytes, MAGIC_JPEG) -> "image/jpeg"
            startsWith(bytes, MAGIC_GIF) -> "image/gif"
            startsWith(bytes, MAGIC_BMP) -> "image/bmp"
            startsWith(bytes, MAGIC_WEBP_RIFF) && bytes.size >= 12 &&
                startsWith(bytes.copyOfRange(8, 12), MAGIC_WEBP_WEBP) -> "image/webp"
            else -> null
        }
    }

    private fun startsWith(data: ByteArray, prefix: ByteArray): Boolean {
        if (data.size < prefix.size) return false
        for (i in prefix.indices) {
            if (data[i] != prefix[i]) return false
        }
        return true
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
        bytes >= 1024 -> "${bytes / 1024} KB"
        else -> "$bytes B"
    }

    private fun checkMediaPermission(): ToolResult? {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            android.Manifest.permission.READ_MEDIA_IMAGES
        } else {
            android.Manifest.permission.READ_EXTERNAL_STORAGE
        }
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(context, permission) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        return if (granted) null else ToolResult.permissionNeeded(
            permission,
            "Reading images from public folders needs a storage permission. I have requested it — " +
                "please grant it and ask again."
        )
    }

    private fun checkAllFilesAccess(): ToolResult? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return if (Environment.isExternalStorageManager()) null
            else ToolResult.permissionNeeded(
                ToolResult.ALL_FILES_ACCESS,
                "Reading images from the 'storage' root needs \"All files access\". I have opened " +
                    "that settings page — please enable it for Gotcha and ask again."
            )
        }
        return checkMediaPermission()
    }

    private fun badPath(path: String) = ToolResult.error(
        "Path '$path' is not allowed. Use one of the allowed roots ($allowedRootNames), " +
            "optionally followed by a relative sub-path; '..' and absolute paths are rejected."
    )
}
