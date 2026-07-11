package com.gotcha.tools

import android.content.Context
import android.os.Build
import android.os.Environment
import java.io.File

/**
 * Surgical text replacement tool — replaces exact text in a file.
 * Same path-resolution rules as [FileTool].
 *
 * Only available to Operator (write operation).
 */
class EditTool(private val context: Context) {

    companion object {
        private const val MAX_FILE_BYTES = 1024 * 1024
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

    private fun resolveFile(path: String, writable: Boolean): Pair<File, ToolResult?>? {
        val normalized = path.trim().trimStart('/')
        val rootName = normalized.substringBefore('/').lowercase()
        val rest = normalized.substringAfter('/', "")

        val rootInfo: Pair<File, ToolResult?>? = when {
            sandboxRoots.containsKey(rootName) -> {
                val f = sandboxRoots[rootName] ?: return null
                Pair(f, null)
            }
            writable && manageRoots.containsKey(rootName) -> {
                val perm = checkAllFilesAccess()
                if (perm != null) return Pair(File(""), perm)
                Pair(manageRoots.getValue(rootName), null)
            }
            !writable && publicRoots.containsKey(rootName) -> {
                val perm = checkMediaPermission()
                if (perm != null) return Pair(File(""), perm)
                Pair(publicRoots.getValue(rootName), null)
            }
            else -> null
        }
        if (rootInfo == null) return null
        val (root, permErr) = rootInfo
        if (permErr != null) return rootInfo

        val target = if (rest.isEmpty()) root else File(root, rest)
        val canonicalRoot = root.canonicalPath
        val canonicalTarget = target.canonicalPath
        if (canonicalTarget != canonicalRoot && !canonicalTarget.startsWith("$canonicalRoot/")) {
            return null
        }
        return Pair(target, null)
    }

    fun edit(path: String, oldString: String, newString: String, replaceAll: Boolean): ToolResult {
        if (oldString.isBlank()) return ToolResult.error("oldString cannot be blank.")
        if (newString == oldString) return ToolResult.error("newString must differ from oldString.")

        if (!isSandboxPath(path)) {
            return ToolResult.error("Editing is only allowed under the app sandbox roots (files, cache, external) " +
                "or the 'storage' root (needs All-files access). Path '$path' is outside them.")
        }

        val resolved = resolveFile(path, writable = true) ?: return ToolResult.error(
            "Path '$path' is not allowed. Use sandbox roots (files, cache, external) or 'storage' with All-files access."
        )
        val (file, permError) = resolved
        if (permError != null) return permError
        if (!file.exists()) return ToolResult.error("File '$path' does not exist.")
        if (!file.isFile) return ToolResult.error("'$path' is not a regular file.")
        if (file.length() > MAX_FILE_BYTES) return ToolResult.error("File too large to edit (max 1 MB).")

        return try {
            val content = file.readText(Charsets.UTF_8)

            val count = if (replaceAll) {
                content.split(oldString).size - 1
            } else {
                if (content.contains(oldString)) 1 else 0
            }

            if (count == 0) {
                return ToolResult.error("No match found for the specified text in '$path'. " +
                    "The text must match exactly, including whitespace and indentation.")
            }

            if (count > 1 && !replaceAll) {
                return ToolResult.error("Found $count matches in '$path'. " +
                    "Provide more surrounding context or use replaceAll=true.")
            }

            val newContent = if (replaceAll) {
                content.replace(oldString, newString)
            } else {
                content.replaceFirst(oldString, newString)
            }

            file.writeText(newContent, Charsets.UTF_8)

            // Build a simple diff snippet
            val diff = buildDiffSnippet(oldString, newString)
            val verb = if (replaceAll) "Replaced $count occurrences" else "Replaced 1 occurrence"
            val sizeInfo = "(${file.length() / 1024} KB)"

            ToolResult.ok("$verb in '$path' $sizeInfo.\n$diff")
        } catch (e: Exception) {
            ToolResult.error("Could not edit '$path': ${e.message}")
        }
    }

    /** Quick check: is this path under a writable root? */
    private fun isSandboxPath(path: String): Boolean {
        val rootName = path.trim().trimStart('/').substringBefore('/').lowercase()
        return rootName in sandboxRoots || rootName in manageRoots
    }

    private fun buildDiffSnippet(oldStr: String, newStr: String): String {
        val oldLines = oldStr.split("\n")
        val newLines = newStr.split("\n")
        val max = minOf(5, oldLines.size, newLines.size)
        val sb = StringBuilder()
        for (i in 0 until max) {
            if (oldLines[i] != newLines[i]) {
                sb.appendLine("- ${oldLines[i].take(80)}")
                sb.appendLine("+ ${newLines[i].take(80)}")
            }
        }
        if (oldLines.size > max || newLines.size > max) sb.appendLine("…(${oldLines.size}→${newLines.size} lines)")
        return sb.toString().trimEnd()
    }

    private fun checkMediaPermission(): ToolResult? {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            android.Manifest.permission.READ_MEDIA_IMAGES
        } else {
            android.Manifest.permission.READ_EXTERNAL_STORAGE
        }
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(context, permission) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        return if (granted) null else ToolResult.permissionNeeded(permission, "")
    }

    private fun checkAllFilesAccess(): ToolResult? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return if (Environment.isExternalStorageManager()) null
            else ToolResult.permissionNeeded(ToolResult.ALL_FILES_ACCESS, "")
        }
        return checkMediaPermission()
    }
}
