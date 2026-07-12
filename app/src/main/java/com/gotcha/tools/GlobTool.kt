package com.gotcha.tools

import android.content.Context
import java.io.File

/**
 * Pattern-based file discovery tool — finds files matching a glob pattern
 * within any directory. Uses absolute paths. The [FileResolver] handles
 * permission checks.
 *
 * Supports: `*` (single segment), `**` (recursive), `?` (single char),
 * `{a,b}` (alternation).
 */
class GlobTool(private val context: Context) {

    private val resolver = FileResolver(context)

    companion object {
        private const val MAX_RESULTS = 500
        private const val MAX_DEPTH = 10
    }

    fun glob(path: String, pattern: String): ToolResult {
        if (pattern.isBlank()) return ToolResult.error("Glob pattern cannot be empty.")

        val resolved = resolver.resolveForRead(path)
        return when (resolved) {
            is FileResolver.ResolveResult.PermissionNeeded -> resolved.result
            is FileResolver.ResolveResult.Error -> ToolResult.error(resolved.message)
            is FileResolver.ResolveResult.Ok -> {
                val root = resolved.file
                val perm = resolver.checkReadPermission(root)
                if (perm != null) return perm
                if (!root.exists()) return ToolResult.error("Path '$path' does not exist.")

                val regex = globToRegex(pattern)
                val results = mutableListOf<String>()
                var aborted = false

                fun walk(dir: File, depth: Int) {
                    if (aborted || depth > MAX_DEPTH) return
                    val entries = dir.listFiles() ?: return
                    for (file in entries.sortedBy { it.name.lowercase() }) {
                        if (aborted) return
                        if (results.size >= MAX_RESULTS) {
                            aborted = true
                            return
                        }

                        val relPath = file.absolutePath.removePrefix(root.absolutePath).trimStart('/')
                        if (regex.matches(relPath)) {
                            results.add(if (file.isDirectory) "$relPath/" else relPath)
                        }

                        if (file.isDirectory) walk(file, depth + 1)
                    }
                }

                if (root.isDirectory) {
                    walk(root, 0)
                } else {
                    if (regex.matches(root.name)) results.add(root.absolutePath)
                }

                if (results.isEmpty()) {
                    return ToolResult.ok("No files matching '$pattern' found in '$path'.")
                }
                val truncated = if (aborted) "\n…(truncated, max $MAX_RESULTS results)" else ""
                return ToolResult.ok(
                    "${results.size} file(s) matching '$pattern' in '$path':\n" +
                        results.joinToString("\n") + truncated
                )
            }
        }
    }

    private fun globToRegex(glob: String): Regex {
        val sb = StringBuilder()
        var i = 0
        while (i < glob.length) {
            val c = glob[i]
            when (c) {
                '*' -> {
                    if (i + 1 < glob.length && glob[i + 1] == '*') {
                        sb.append(".*")
                        i += 2
                        if (i < glob.length && glob[i] == '/') i++
                    } else {
                        sb.append("[^/]*")
                        i++
                    }
                }
                '?' -> {
                    sb.append('.')
                    i++
                }
                '.' -> {
                    sb.append("\\.")
                    i++
                }
                '{' -> {
                    val end = glob.indexOf('}', i)
                    if (end > i) {
                        val parts = glob.substring(i + 1, end).split(",")
                        sb.append("(?:")
                        sb.append(parts.joinToString("|") { Regex.escape(it) })
                        sb.append(')')
                        i = end + 1
                    } else {
                        sb.append(c)
                        i++
                    }
                }
                else -> {
                    sb.append(Regex.escape(c.toString()))
                    i++
                }
            }
        }
        return try {
            Regex(sb.toString(), RegexOption.IGNORE_CASE)
        } catch (_: Exception) {
            Regex(".*")
        }
    }
}
