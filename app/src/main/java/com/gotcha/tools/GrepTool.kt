package com.gotcha.tools

import android.content.Context
import android.os.Build
import android.os.Environment
import java.io.File
import java.io.FileInputStream

/**
 * Content-search tool — searches file contents by regex within named-root paths.
 * Same path-resolution rules as [FileTool] and [VisionTool].
 */
class GrepTool(private val context: Context) {

    companion object {
        private const val MAX_DEPTH = 10
        private const val MAX_FILES = 500
        private const val MAX_MATCHES = 100
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

    /** Search files in [path] (or its subtree) for lines matching [pattern]. */
    fun grep(path: String, pattern: String, include: String?): ToolResult {
        if (pattern.isBlank()) return ToolResult.error("Search pattern cannot be empty.")

        val regex = try {
            Regex(pattern, setOf(RegexOption.IGNORE_CASE))
        } catch (e: Exception) {
            return ToolResult.error("Invalid regex pattern: ${e.message}")
        }

        val resolved = resolveRoot(path) ?: return ToolResult.error(
            "Path '$path' is not allowed. Use one of: files, cache, external, " +
                "downloads, pictures, dcim, documents, storage."
        )
        val (root, permError) = resolved
        if (permError != null) return permError

        if (!root.exists()) return ToolResult.error("Path '$path' does not exist.")

        val includeGlob = include?.let {
            try {
                Regex(it.replace(".", "\\.").replace("*", ".*").replace("?", "."), RegexOption.IGNORE_CASE)
            } catch (_: Exception) { null }
        }

        val results = mutableListOf<String>()
        var filesSearched = 0
        var matchCount = 0
        var aborted = false

        fun walk(dir: File, depth: Int) {
            if (aborted || depth > MAX_DEPTH) return
            val entries = dir.listFiles() ?: return
            for (file in entries.sortedBy { it.name.lowercase() }) {
                if (aborted) return
                if (filesSearched >= MAX_FILES) { aborted = true; return }

                if (file.isDirectory) {
                    walk(file, depth + 1)
                } else if (file.isFile) {
                    if (includeGlob != null && !includeGlob.matches(file.name)) continue
                    if (file.length() > MAX_FILE_BYTES) continue
                    val relPath = relativePath(file)
                    filesSearched++

                    try {
                        val text = file.readText(Charsets.UTF_8)
                        val lines = text.split("\n")
                        for ((idx, line) in lines.withIndex()) {
                            if (regex.containsMatchIn(line)) {
                                matchCount++
                                val display = line.take(200).replace("\t", " ").trim()
                                results.add("${relPath}:${idx + 1}: $display")
                                if (matchCount >= MAX_MATCHES) { aborted = true; return }
                            }
                        }
                    } catch (_: Exception) {
                        // Skip binary or unreadable files silently
                    }
                }
            }
        }

        if (root.isDirectory) {
            walk(root, 0)
        } else if (root.isFile) {
            if (includeGlob == null || includeGlob.matches(root.name)) {
                if (root.length() <= MAX_FILE_BYTES) {
                    filesSearched = 1
                    try {
                        val text = root.readText(Charsets.UTF_8)
                        val lines = text.split("\n")
                        for ((idx, line) in lines.withIndex()) {
                            if (regex.containsMatchIn(line)) {
                                matchCount++
                                results.add("${root.name}:${idx + 1}: ${line.take(200).trim()}")
                                if (matchCount >= MAX_MATCHES) break
                            }
                        }
                    } catch (_: Exception) { }
                }
            }
        }

        return if (results.isEmpty()) {
            ToolResult.ok("No matches found for pattern '$pattern' in '$path'.")
        } else {
            val truncated = if (matchCount >= MAX_MATCHES || filesSearched >= MAX_FILES) {
                "\n…(truncated, max ${MAX_MATCHES} matches)"
            } else ""
            val summary = if (filesSearched > 1) " (searched $filesSearched files)" else ""
            ToolResult.ok("Found ${results.size} match(es) for '$pattern' in '$path'$summary:\n" +
                results.joinToString("\n") + truncated)
        }
    }

    private fun relativePath(file: File): String {
        val canonical = file.absolutePath
        // Try to produce a short relative path by stripping common roots
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
        return if (granted) null else ToolResult.permissionNeeded(
            permission,
            "Searching public folders needs a storage permission."
        )
    }

    private fun checkAllFilesAccess(): ToolResult? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return if (Environment.isExternalStorageManager()) null
            else ToolResult.permissionNeeded(
                ToolResult.ALL_FILES_ACCESS,
                "Searching the 'storage' root needs \"All files access\"."
            )
        }
        return checkMediaPermission()
    }
}
