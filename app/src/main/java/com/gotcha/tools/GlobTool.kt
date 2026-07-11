package com.gotcha.tools

import android.content.Context
import android.os.Build
import android.os.Environment
import java.io.File

/**
 * Pattern-based file discovery tool — finds files matching a glob pattern
 * within named-root paths. Same path-resolution as [FileTool].
 *
 * Supports: `*` (single segment), `**` (recursive), `?` (single char).
 */
class GlobTool(private val context: Context) {

    companion object {
        private const val MAX_RESULTS = 500
        private const val MAX_DEPTH = 10
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

    private fun resolveRoot(path: String): Pair<File, ToolResult?>? {
        val normalized = path.trim().trimStart('/')
        val rootName = normalized.substringBefore('/').lowercase()
        val rest = normalized.substringAfter('/', "")

        val root: File = when {
            sandboxRoots.containsKey(rootName) -> sandboxRoots[rootName] ?: return null
            manageRoots.containsKey(rootName) -> {
                val perm = checkAllFilesAccess()
                if (perm != null) return Pair(File(""), perm)
                manageRoots.getValue(rootName)
            }
            publicRoots.containsKey(rootName) -> {
                val perm = checkMediaPermission()
                if (perm != null) return Pair(File(""), perm)
                publicRoots.getValue(rootName)
            }
            else -> return null
        }

        val target = if (rest.isEmpty()) root else File(root, rest)
        val canonicalRoot = root.canonicalPath
        val canonicalTarget = target.canonicalPath
        if (canonicalTarget != canonicalRoot && !canonicalTarget.startsWith("$canonicalRoot/")) {
            return null
        }
        return Pair(target, null)
    }

    /**
     * Find files matching [pattern] within [path].
     * Pattern uses glob syntax: `*` matches any non-/ chars, `**` matches
     * any path, `?` matches a single non-/ char.
     */
    fun glob(path: String, pattern: String): ToolResult {
        if (pattern.isBlank()) return ToolResult.error("Glob pattern cannot be empty.")

        val resolved = resolveRoot(path) ?: return ToolResult.error(
            "Path '$path' is not allowed. Use one of: files, cache, external, " +
                "downloads, pictures, dcim, documents, storage."
        )
        val (root, permError) = resolved
        if (permError != null) return permError
        if (!root.exists()) return ToolResult.error("Path '$path' does not exist.")

        val regex = globToRegex(pattern)
        val results = mutableListOf<String>()
        var aborted = false

        fun walk(dir: File, depth: Int) {
            if (aborted || depth > MAX_DEPTH) return
            val entries = dir.listFiles() ?: return
            for (file in entries.sortedBy { it.name.lowercase() }) {
                if (aborted) return
                if (results.size >= MAX_RESULTS) { aborted = true; return }

                val relPath = relativePath(file)
                if (regex.matches(relPath)) {
                    results.add(if (file.isDirectory) "$relPath/" else relPath)
                }

                if (file.isDirectory) {
                    walk(file, depth + 1)
                }
            }
        }

        if (root.isDirectory) {
            walk(root, 0)
        } else {
            if (regex.matches(root.name)) {
                results.add(relativePath(root))
            }
        }

        if (results.isEmpty()) {
            return ToolResult.ok("No files matching '$pattern' found in '$path'.")
        }
        val truncated = if (aborted) "\n…(truncated, max $MAX_RESULTS results)" else ""
        return ToolResult.ok("${results.size} file(s) matching '$pattern' in '$path':\n" +
            results.joinToString("\n") + truncated)
    }

    /** Convert a glob pattern to a regex. */
    private fun globToRegex(glob: String): Regex {
        val sb = StringBuilder()
        var i = 0
        while (i < glob.length) {
            val c = glob[i]
            when (c) {
                '*' -> {
                    if (i + 1 < glob.length && glob[i + 1] == '*') {
                        // ** matches everything
                        sb.append(".*")
                        i += 2
                        if (i < glob.length && glob[i] == '/') i++ // skip trailing /
                    } else {
                        // * matches non-/ chars
                        sb.append("[^/]*")
                        i++
                    }
                }
                '?' -> { sb.append('.'); i++ }
                '.' -> { sb.append("\\."); i++ }
                '{' -> {
                    val end = glob.indexOf('}', i)
                    if (end > i) {
                        val parts = glob.substring(i + 1, end).split(",")
                        sb.append("(?:")
                        sb.append(parts.joinToString("|") { Regex.escape(it) })
                        sb.append(')')
                        i = end + 1
                    } else { sb.append(c); i++ }
                }
                else -> { sb.append(Regex.escape(c.toString())); i++ }
            }
        }
        return try {
            Regex(sb.toString(), RegexOption.IGNORE_CASE)
        } catch (_: Exception) {
            Regex(".*")
        }
    }

    private fun relativePath(file: File): String {
        val canonical = file.absolutePath
        for (root in sandboxRoots.values + publicRoots.values + manageRoots.values) {
            val rootPath = root?.absolutePath ?: continue
            if (canonical.startsWith(rootPath + "/")) {
                return canonical.substring(rootPath.length + 1)
            }
        }
        return file.name
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
