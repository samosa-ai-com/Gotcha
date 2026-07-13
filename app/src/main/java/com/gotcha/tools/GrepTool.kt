package com.gotcha.tools

import android.content.Context
import java.io.File

/**
 * Content-search tool — searches file contents by regex within any directory.
 * Uses absolute paths. The [FileResolver] handles permission checks.
 */
class GrepTool(private val context: Context) {

    private val resolver = FileResolver(context)

    companion object {
        private const val MAX_DEPTH = 10
        private const val MAX_FILES = 500
        private const val MAX_MATCHES = 100
        private const val MAX_FILE_BYTES = 1024 * 1024
    }

    @Suppress("CyclomaticComplexMethod", "NestedBlockDepth", "LoopWithTooManyJumpStatements")
    fun grep(path: String, pattern: String, include: String?): ToolResult {
        if (pattern.isBlank()) return ToolResult.error("Search pattern cannot be empty.")
        val regex = try {
            Regex(pattern, setOf(RegexOption.IGNORE_CASE))
        } catch (e: Exception) {
            return ToolResult.error("Invalid regex pattern: ${e.message}")
        }

        val resolved = resolver.resolveForRead(path)
        return when (resolved) {
            is FileResolver.ResolveResult.PermissionNeeded -> resolved.result
            is FileResolver.ResolveResult.Error -> ToolResult.error(resolved.message)
            is FileResolver.ResolveResult.Ok -> {
                val root = resolved.file
                val perm = resolver.checkReadPermission(root)
                if (perm != null) return perm
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
                        if (filesSearched >= MAX_FILES) {
                            aborted = true
                            return
                        }
                        if (file.isDirectory) {
                            walk(file, depth + 1)
                            continue
                        }
                        if (!file.isFile) continue
                        if (includeGlob != null && !includeGlob.matches(file.name)) continue
                        if (file.length() > MAX_FILE_BYTES) continue
                        filesSearched++

                        try {
                            val text = file.readText(Charsets.UTF_8)
                            val lines = text.split("\n")
                            for ((idx, line) in lines.withIndex()) {
                                if (regex.containsMatchIn(line)) {
                                    matchCount++
                                    val display = line.take(200).replace("\t", " ").trim()
                                    results.add("${file.absolutePath}:${idx + 1}: $display")
                                    if (matchCount >= MAX_MATCHES) {
                                        aborted = true
                                        return
                                    }
                                }
                            }
                        } catch (_: Exception) { }
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
                        "\n…(truncated, max $MAX_MATCHES matches)"
                    } else {
                        ""
                    }
                    val summary = if (filesSearched > 1) " (searched $filesSearched files)" else ""
                    ToolResult.ok(
                        "Found ${results.size} match(es) for '$pattern' in '$path'$summary:\n" +
                            results.joinToString("\n") + truncated
                    )
                }
            }
        }
    }
}
