package com.gotcha.tools

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat
import java.io.File

/**
 * Sandboxed filesystem access (PRD §4.1). Paths are rooted at named allow-listed
 * roots; canonical-path containment checks reject traversal and absolute escapes.
 */
class FileTool(private val context: Context) {

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

    /**
     * Tier 3 — the whole shared-storage tree, read *and* write, behind
     * "All files access" (MANAGE_EXTERNAL_STORAGE). Only reachable when the user has
     * granted that special access; otherwise resolve() returns a permission hint.
     */
    private val manageRoots: Map<String, File> by lazy {
        mapOf("storage" to Environment.getExternalStorageDirectory())
    }

    private val allowedRootNames: String
        get() = (sandboxRoots.keys + publicRoots.keys + manageRoots.keys).joinToString(", ")

    fun listFiles(path: String): ToolResult {
        val resolved = resolve(path, writable = false) ?: return badPath(path)
        (resolved as? PermissionMissing)?.let { return it.result }
        val dir = (resolved as Resolved).file
        if (!dir.exists()) return ToolResult.error("Directory '$path' does not exist.")
        if (!dir.isDirectory) return ToolResult.error("'$path' is a file, not a directory.")
        val entries = dir.listFiles()
            ?: return ToolResult.error("Cannot read '$path' (no access).")
        if (entries.isEmpty()) return ToolResult.ok("'$path' is empty.")
        val listing = entries.sortedBy { it.name.lowercase() }.take(200).joinToString("\n") {
            if (it.isDirectory) "${it.name}/" else "${it.name} (${StorageTool.format(it.length())})"
        }
        return ToolResult.ok("Contents of '$path' (${entries.size} entries):\n$listing")
    }

    fun readFile(path: String, maxBytes: Int = 64 * 1024): ToolResult {
        val resolved = resolve(path, writable = false) ?: return badPath(path)
        (resolved as? PermissionMissing)?.let { return it.result }
        val file = (resolved as Resolved).file
        if (!file.exists()) return ToolResult.error("File '$path' does not exist.")
        if (!file.isFile) return ToolResult.error("'$path' is not a regular file.")
        return try {
            val truncated = file.length() > maxBytes
            val buffer = ByteArray(maxBytes)
            val read = file.inputStream().use { input ->
                var total = 0
                while (total < maxBytes) {
                    val n = input.read(buffer, total, maxBytes - total)
                    if (n < 0) break
                    total += n
                }
                total
            }
            val text = String(buffer, 0, read)
            ToolResult.ok(if (truncated) "$text\n…(truncated at ${maxBytes / 1024} KB)" else text)
        } catch (e: Exception) {
            ToolResult.error("Could not read '$path': ${e.message}")
        }
    }

    fun writeFile(path: String, content: String, append: Boolean): ToolResult {
        val resolved = resolve(path, writable = true)
            ?: return ToolResult.error(
                "Writing is only allowed under the app sandbox roots (${sandboxRoots.keys.joinToString(", ")}) " +
                    "or the 'storage' root (needs All-files access). Path '$path' is outside them."
            )
        (resolved as? PermissionMissing)?.let { return it.result }
        val file = (resolved as Resolved).file
        return try {
            file.parentFile?.mkdirs()
            if (append) file.appendText(content) else file.writeText(content)
            ToolResult.ok("${if (append) "Appended to" else "Wrote"} '$path' (${content.length} chars).")
        } catch (e: Exception) {
            ToolResult.error("Could not write '$path': ${e.message}")
        }
    }

    private sealed interface Resolution
    private data class Resolved(val file: File) : Resolution
    private data class PermissionMissing(val result: ToolResult) : Resolution

    /**
     * Resolves "root/relative/path" to a File, enforcing that the canonical path
     * stays inside the named root. Returns null for unknown roots or escapes.
     */
    private fun resolve(path: String, writable: Boolean): Resolution? {
        val normalized = path.trim().trimStart('/')
        val rootName = normalized.substringBefore('/').lowercase()
        val rest = normalized.substringAfter('/', "")

        val root: File = when {
            sandboxRoots.containsKey(rootName) -> sandboxRoots[rootName] ?: return null
            manageRoots.containsKey(rootName) -> {
                checkAllFilesAccess()?.let { return PermissionMissing(it) }
                manageRoots.getValue(rootName)
            }
            !writable && publicRoots.containsKey(rootName) -> {
                checkMediaPermission()?.let { return PermissionMissing(it) }
                publicRoots.getValue(rootName)
            }
            else -> return null
        }

        val target = if (rest.isEmpty()) root else File(root, rest)
        val canonicalRoot = root.canonicalPath
        val canonicalTarget = target.canonicalPath
        if (canonicalTarget != canonicalRoot && !canonicalTarget.startsWith("$canonicalRoot/")) {
            return null // path traversal attempt
        }
        return Resolved(target)
    }

    private fun checkMediaPermission(): ToolResult? {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        val granted = ContextCompat.checkSelfPermission(context, permission) ==
            PackageManager.PERMISSION_GRANTED
        return if (granted) null else ToolResult.permissionNeeded(
            permission,
            "Reading public folders needs a storage permission. I have requested it — " +
                "please grant it and ask again."
        )
    }

    /**
     * Tier 3 gate for the 'storage' root. On API 30+ this is the MANAGE_EXTERNAL_STORAGE
     * "All files access" special grant; on older devices broad read access falls back to
     * READ/WRITE_EXTERNAL_STORAGE (handled here as the media permission).
     */
    private fun checkAllFilesAccess(): ToolResult? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return if (Environment.isExternalStorageManager()) null
            else ToolResult.permissionNeeded(
                ToolResult.ALL_FILES_ACCESS,
                "Reading or writing all of shared storage needs \"All files access\". I have opened " +
                    "that settings page — please enable it for Gotcha and ask again."
            )
        }
        // Pre-R: broad shared-storage access rides on the legacy storage permission.
        return checkMediaPermission()
    }

    private fun badPath(path: String) = ToolResult.error(
        "Path '$path' is not allowed. Use one of the allowed roots ($allowedRootNames), " +
            "optionally followed by a relative sub-path; '..' and absolute paths are rejected."
    )
}
