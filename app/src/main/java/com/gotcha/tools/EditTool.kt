package com.gotcha.tools

import android.content.Context

/**
 * Surgical text replacement tool — replaces exact text in a file.
 * Uses absolute paths with [FileResolver] for resolution and permission checks.
 *
 * Only available to Operator (write operation).
 */
class EditTool(private val context: Context) {

    private val resolver = FileResolver(context)

    companion object {
        private const val MAX_FILE_BYTES = 1024 * 1024
    }

    fun edit(path: String, oldString: String, newString: String, replaceAll: Boolean): ToolResult {
        if (oldString.isBlank()) {
            return ToolResult.error(
                "oldString cannot be blank. Provide the exact text you want to replace."
            )
        }
        if (newString == oldString) {
            return ToolResult.error(
                "newString must differ from oldString. Provide the replacement text."
            )
        }

        val resolved = resolver.resolveForWrite(path)
        return when (resolved) {
            is FileResolver.ResolveResult.PermissionNeeded -> resolved.result
            is FileResolver.ResolveResult.Error -> ToolResult.error(resolved.message)
            is FileResolver.ResolveResult.Ok -> {
                val file = resolved.file
                val perm = resolver.checkWritePermission(file)
                if (perm != null) return perm
                if (!file.exists()) {
                    return ToolResult.error(
                        "File '$path' does not exist (resolved: ${file.canonicalPath}). " +
                            "You may verify the path with list_files or glob first."
                    )
                }
                if (!file.isFile) {
                    return ToolResult.error(
                        "'$path' is not a regular file. You may use list_files to see what is at that path."
                    )
                }
                if (file.length() > MAX_FILE_BYTES) return ToolResult.error("File too large to edit (max 1 MB).")

                try {
                    val content = file.readText(Charsets.UTF_8)
                    val count = if (replaceAll) {
                        content.split(oldString).size - 1
                    } else if (content.contains(oldString)) 1 else 0

                    if (count == 0) {
                        return ToolResult.error(
                            "No match found for the specified text in '$path'. " +
                                "You may use grep or read_file to locate the exact text first — the match must be exact, including " +
                                "whitespace and indentation."
                        )
                    }
                    if (count > 1 && !replaceAll) {
                        return ToolResult.error(
                            "Found $count matches in '$path'. " +
                                "You may provide more surrounding context in oldString, or pass replaceAll=true to replace all occurrences."
                        )
                    }

                    val newContent = if (replaceAll) {
                        content.replace(oldString, newString)
                    } else {
                        content.replaceFirst(oldString, newString)
                    }
                    file.writeText(newContent, Charsets.UTF_8)

                    val verb = if (replaceAll) "Replaced $count occurrences" else "Replaced 1 occurrence"
                    val diff = buildDiffSnippet(oldString, newString)
                    ToolResult.ok("$verb in '${file.canonicalPath}'.\n$diff")
                } catch (e: Exception) {
                    ToolResult.error("Could not edit '$path': ${e.message}")
                }
            }
        }
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
}
