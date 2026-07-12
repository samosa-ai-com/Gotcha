package com.gotcha.tools

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Base64
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Universal filesystem tool — reads/writes/lists files anywhere on the device
 * using absolute paths. No more named-root sandboxing. Accepts any absolute
 * path directly; relative paths are resolved against the default working
 * directory (see [FileResolver.WORKING_DIR_BASE]).
 *
 * Automatically detects file types by extension and magic bytes:
 * - Text files → line-by-line streaming with offset/limit support
 * - Images → base64 with IMAGE_DATA prefix (for vision models)
 * - Archives (zip, rar, gz) → list contents; supports entry reading via "archive.zip/path"
 * - PDF → base64 document passthrough
 * - Other binary → base64 with MIME type
 * - Unknown → best-effort text read
 */
class FileTool(private val context: Context) {

    private val resolver = FileResolver(context)

    companion object {
        private const val MAX_TEXT_BYTES = 64 * 1024
        private const val MAX_TEXT_LINES = 2000
        private const val MAX_LINE_LENGTH = 2000
        private const val MAX_LIST_ENTRIES = 500
        private const val MAX_LIST_DEPTH = 10
        private const val MAX_IMAGE_BYTES = 20 * 1024 * 1024
        private const val MAX_BINARY_BYTES = 20 * 1024 * 1024

        private val TEXT_EXTENSIONS = setOf(
            "txt", "md", "json", "xml", "html", "htm", "css", "js", "ts", "jsx", "tsx",
            "kt", "java", "py", "rb", "go", "rs", "c", "cpp", "h", "hpp", "swift",
            "sh", "bash", "zsh", "bat", "ps1", "yml", "yaml", "toml", "ini", "cfg",
            "conf", "log", "csv", "tsv", "sql", "r", "m", "gradle", "properties",
            "env", "gitignore", "dockerfile", "cfg", "plist", "tex", "bib",
            "svg", "gradle.kts"
        )
        private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")
        private val ARCHIVE_EXTENSIONS = setOf("zip", "apk", "aar", "jar", "war")
        private val OFFICE_EXTENSIONS = setOf("docx", "xlsx", "pptx")

        private val ARCHIVE_DELIMITERS = setOf(
            ".zip/", ".apk/", ".aar/", ".jar/", ".war/",
            ".docx/", ".xlsx/", ".pptx/"
        )
    }

    fun readFile(
        path: String,
        offset: Int? = null,
        limit: Int? = null,
        encoding: String? = null
    ): ToolResult {
        val resolved = resolver.resolveForRead(path)
        return when (resolved) {
            is FileResolver.ResolveResult.PermissionNeeded -> resolved.result
            is FileResolver.ResolveResult.Error -> ToolResult.error(resolved.message)
            is FileResolver.ResolveResult.Ok -> readResolved(resolved.file, path, offset, limit, encoding)
        }
    }

    private fun readResolved(
        file: File,
        originalPath: String,
        offset: Int?,
        limit: Int?,
        encoding: String?
    ): ToolResult {
        if (!file.exists()) {
            val archiveResult = tryReadArchiveEntry(originalPath)
            if (archiveResult != null) return archiveResult
            return ToolResult.error("Path '$originalPath' does not exist (resolved: ${file.canonicalPath}).")
        }
        if (file.isDirectory) return listResolved(file, originalPath, null, null, null, false, null)
        val ext = file.extension.lowercase()
        val perm = resolver.checkReadPermission(file)
        if (perm != null) return perm

        return when {
            ext in IMAGE_EXTENSIONS -> readImageFile(file)
            ext == "pdf" -> readFileBase64(file, "application/pdf")
            ext in ARCHIVE_EXTENSIONS -> listArchiveContents(file)
            ext in OFFICE_EXTENSIONS -> {
                val list = listArchiveContents(file)
                if (list.success) ToolResult.ok(
                    "Office document detected (${file.name}). It is a ZIP archive containing:\n" +
                    list.message + "\nUse read_file on a specific entry, e.g. '${originalPath}/word/document.xml' for .docx."
                ) else list
            }
            ext in TEXT_EXTENSIONS -> readTextFile(file, offset, limit, encoding)
            else -> readUnknownFile(file, encoding)
        }
    }

    private fun readTextFile(file: File, offset: Int?, limit: Int?, encoding: String?): ToolResult {
        val enc = encoding ?: Charsets.UTF_8.name()
        val maxLines = limit ?: MAX_TEXT_LINES
        val startLine = (offset ?: 1).coerceAtLeast(1)
        return try {
            val lines = file.readLines(java.nio.charset.Charset.forName(enc))
            if (startLine > lines.size) {
                return ToolResult.error(
                    "File '${file.name}' has ${lines.size} lines, but offset $startLine was requested."
                )
            }
            val endLine = minOf(startLine + maxLines - 1, lines.size)
            val selected = lines.subList(startLine - 1, endLine)
            val truncated = endLine < lines.size
            val shownBytes = selected.sumOf { it.length }
            val capReached = shownBytes > MAX_TEXT_BYTES
            val sb = StringBuilder()
            var byteCount = 0
            for ((i, line) in selected.withIndex()) {
                val lineNum = startLine + i
                val display = if (line.length > MAX_LINE_LENGTH)
                    line.take(MAX_LINE_LENGTH) + "…(line truncated)"
                else line
                val next = if (i > 0 || offset != null) "$lineNum: $display\n" else "$display\n"
                if (byteCount + next.length > MAX_TEXT_BYTES && byteCount > 0) {
                    sb.append("…(output capped at ${MAX_TEXT_BYTES / 1024} KB)")
                    break
                }
                sb.append(next)
                byteCount += next.length
                if (capReached && byteCount >= MAX_TEXT_BYTES) break
            }

            val result = sb.toString().trimEnd()
            val summary = buildString {
                append(result)
                if (truncated || capReached) {
                    append("\n")
                    if (truncated) append("(Showing lines $startLine-$endLine of ${lines.size}. Use offset=${endLine + 1} to continue.)")
                    if (capReached) append(" (Output capped at ${MAX_TEXT_BYTES / 1024} KB.)")
                }
            }
            ToolResult.ok(summary.ifEmpty { "(empty file)" })
        } catch (e: java.nio.charset.UnsupportedCharsetException) {
            ToolResult.error("Unsupported encoding '$enc'. Try UTF-8, ISO-8859-1, or UTF-16.")
        } catch (e: Exception) {
            ToolResult.error("Could not read '${file.name}': ${e.message}")
        }
    }

    private fun readImageFile(file: File): ToolResult {
        if (file.length() > MAX_IMAGE_BYTES) {
            return ToolResult.error("Image too large (${resolver.formatSize(file.length())}). Max: ${MAX_IMAGE_BYTES / 1024 / 1024} MB.")
        }
        return try {
            val bytes = file.readBytes()
            val mime = resolver.detectMime(bytes)
            if (mime == null || !resolver.isImageMime(mime))
                return ToolResult.error("File '${file.name}' is not a recognized image format.")
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            if (opts.outWidth <= 0 || opts.outHeight <= 0)
                return ToolResult.error("File '${file.name}' could not be decoded as an image.")
            val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            val displayName = file.name
            ToolResult.ok(
                "${resolver.IMAGE_DATA_PREFIX}$mime:${opts.outWidth}x${opts.outHeight}:${bytes.size}:$displayName:$base64"
            )
        } catch (e: Exception) {
            ToolResult.error("Could not read image '${file.name}': ${e.message}")
        }
    }

    private fun readFileBase64(file: File, mime: String): ToolResult {
        if (file.length() > MAX_BINARY_BYTES) {
            return ToolResult.error("File too large (${resolver.formatSize(file.length())}). Max: ${MAX_BINARY_BYTES / 1024 / 1024} MB.")
        }
        return try {
            val bytes = file.readBytes()
            val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            ToolResult.ok("BINARY_DATA:$mime:${bytes.size}:${file.name}:$base64")
        } catch (e: Exception) {
            ToolResult.error("Could not read '${file.name}': ${e.message}")
        }
    }

    private fun readUnknownFile(file: File, encoding: String?): ToolResult {
        val sample = try { file.inputStream().use { it.readNBytes(512) } } catch (_: Exception) { ByteArray(0) }
        if (sample.isEmpty()) return readTextFile(file, null, 200, encoding)
        val nullRatio = sample.count { it.toInt() == 0 }.toFloat() / sample.size
        val nonprintableRatio = sample.count {
            val v = it.toInt() and 0xFF; v < 32 && v != 9 && v != 10 && v != 13
        }.toFloat() / sample.size
        val mime = resolver.detectMime(sample)
        if (mime != null && resolver.isImageMime(mime)) return readImageFile(file)
        if (mime != null && mime == "application/pdf") return readFileBase64(file, "application/pdf")
        if (mime != null && resolver.isArchiveMime(mime)) return listArchiveContents(file)

        return if (nullRatio > 0.05f || nonprintableRatio > 0.3f) {
            readFileBase64(file, "application/octet-stream")
        } else {
            readTextFile(file, null, 200, encoding ?: Charsets.UTF_8.name())
        }
    }

    private fun listArchiveContents(file: File): ToolResult {
        return try {
            when (file.extension.lowercase()) {
                "zip", "apk", "aar", "jar", "war", "docx", "xlsx", "pptx" -> {
                    val zf = ZipFile(file)
                    val entries = zf.entries().asSequence().toList()
                    zf.close()
                    if (entries.isEmpty()) return ToolResult.ok("Archive '${file.name}' is empty.")
                    val totalSize = entries.sumOf { it.size.coerceAtLeast(0) }
                    val sb = StringBuilder()
                    sb.appendLine("Archive: ${file.name} (${resolver.formatSize(file.length())}, ${entries.size} entries, ${resolver.formatSize(totalSize)} uncompressed)")
                    for (e in entries.take(200)) {
                        val flag = if (e.isDirectory) "/" else ""
                        val size = if (e.size > 0) " ${resolver.formatSize(e.size)}" else ""
                        sb.appendLine("  $flag${e.name}$size")
                    }
                    if (entries.size > 200) sb.appendLine("  …(${entries.size - 200} more)")
                    ToolResult.ok(sb.toString().trimEnd())
                }
                else -> ToolResult.error("Unsupported archive format: .${file.extension}")
            }
        } catch (e: Exception) {
            ToolResult.error("Could not read archive '${file.name}': ${e.message}")
        }
    }

    private fun tryReadArchiveEntry(path: String): ToolResult? {
        for (delim in ARCHIVE_DELIMITERS) {
            val idx = path.indexOf(delim)
            if (idx < 0) continue
            val archivePath = path.substring(0, idx + delim.length - 1)
            val entryPath = path.substring(idx + delim.length)
            val archiveFile = File(archivePath)
            if (!archiveFile.exists()) continue
            try {
                val zf = ZipFile(archiveFile)
                val result = readZipEntry(zf, archiveFile, archivePath, entryPath)
                zf.close()
                return result
            } catch (e: Exception) {
                return ToolResult.error("Could not read entry '$entryPath' from archive: ${e.message}")
            }
        }
        return null
    }

    private fun readZipEntry(zf: ZipFile, archiveFile: File, archivePath: String, entryPath: String): ToolResult {
        val entry = zf.getEntry(entryPath)
        if (entry == null) {
            val siblings = zf.entries().asSequence().map { it?.name ?: "" }.toList()
            val matching = siblings.filter { it.contains(entryPath, ignoreCase = true) }.take(5)
            val hint = if (matching.isNotEmpty())
                " Did you mean one of: ${matching.joinToString(", ")}?"
            else ""
            return ToolResult.error("Entry '$entryPath' not found in '${archiveFile.name}'.$hint")
        }
        if (entry.isDirectory) {
            val children = zf.entries().asSequence()
                .filter { it != null && it.name.startsWith(entryPath) && !it.isDirectory }.take(100).toList()
            if (children.isEmpty()) return ToolResult.ok("(empty directory)")
            val listing = children.joinToString("\n") { "  ${it!!.name} (${resolver.formatSize(it.size)})" }
            return ToolResult.ok("Contents of '$entryPath' (${children.size} files):\n$listing")
        }
        val ext = entryPath.substringAfterLast('.', "").lowercase()
        val bytes = zf.getInputStream(entry).readBytes()
        if (ext in IMAGE_EXTENSIONS) {
            val mime = resolver.detectMime(bytes) ?: "image/$ext"
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            val dims = if (opts.outWidth > 0) "${opts.outWidth}x${opts.outHeight}" else "unknown"
            val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            return ToolResult.ok("${resolver.IMAGE_DATA_PREFIX}$mime:$dims:${bytes.size}:$entryPath:$base64")
        }
        if (ext in TEXT_EXTENSIONS || resolver.detectMime(bytes) == null) {
            val text = String(bytes, Charsets.UTF_8)
            return ToolResult.ok("[$archivePath / $entryPath]\n$text")
        }
        val mime = resolver.detectMime(bytes) ?: "application/octet-stream"
        val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        return ToolResult.ok("BINARY_DATA:$mime:${bytes.size}:$entryPath:$base64")
    }

    fun writeFile(
        path: String,
        content: String,
        append: Boolean = false,
        binary: Boolean = false
    ): ToolResult {
        val resolved = resolver.resolveForWrite(path)
        return when (resolved) {
            is FileResolver.ResolveResult.PermissionNeeded -> resolved.result
            is FileResolver.ResolveResult.Error -> ToolResult.error(resolved.message)
            is FileResolver.ResolveResult.Ok -> {
                val perm = resolver.checkWritePermission(resolved.file)
                if (perm != null) return perm
                writeResolved(resolved.file, path, content, append, binary)
            }
        }
    }

    private fun writeResolved(
        file: File,
        originalPath: String,
        content: String,
        append: Boolean,
        binary: Boolean
    ): ToolResult {
        return try {
            file.parentFile?.mkdirs()
            if (binary) {
                val bytes = Base64.decode(content, Base64.NO_WRAP)
                if (append) file.appendBytes(bytes) else file.writeBytes(bytes)
                ToolResult.ok("Wrote ${resolver.formatSize(bytes.size.toLong())} binary data to '$originalPath' (resolved: ${file.canonicalPath}).")
            } else {
                val bytes = content.toByteArray(Charsets.UTF_8)
                if (append) file.appendBytes(bytes) else file.writeBytes(bytes)
                ToolResult.ok("${if (append) "Appended" else "Wrote"} ${resolver.formatSize(bytes.size.toLong())} to '$originalPath' (resolved: ${file.canonicalPath}).")
            }
        } catch (e: Exception) {
            ToolResult.error("Could not write '$originalPath': ${e.message}")
        }
    }

    fun listFiles(
        path: String,
        recursive: Boolean = false,
        sortBy: String? = null,
        include: String? = null,
        exclude: String? = null,
        maxDepth: Int? = null
    ): ToolResult {
        val resolved = resolver.resolveForRead(path)
        return when (resolved) {
            is FileResolver.ResolveResult.PermissionNeeded -> resolved.result
            is FileResolver.ResolveResult.Error -> ToolResult.error(resolved.message)
            is FileResolver.ResolveResult.Ok -> listResolved(
                resolved.file, path, sortBy, include, exclude, recursive, maxDepth
            )
        }
    }

    private fun listResolved(
        file: File,
        originalPath: String,
        sortBy: String?,
        include: String?,
        exclude: String?,
        recursive: Boolean,
        maxDepth: Int?
    ): ToolResult {
        if (!file.exists()) return ToolResult.error("Path '$originalPath' does not exist (resolved: ${file.canonicalPath}).")
        if (!file.isDirectory) {
            return ToolResult.ok("${file.name} (${resolver.formatSize(file.length())})\nResolved: ${file.canonicalPath}")
        }
        val perm = resolver.checkReadPermission(file)
        if (perm != null) return perm

        val results = mutableListOf<Pair<String, File>>()
        val depthLimit = maxDepth ?: if (recursive) MAX_LIST_DEPTH else 1
        var aborted = false

        fun walk(dir: File, depth: Int) {
            if (aborted || depth > depthLimit) return
            val entries = dir.listFiles()?.sortedWith(when (sortBy?.lowercase()) {
                "date" -> compareBy<File> { it.lastModified() }
                "size" -> compareBy<File> { it.length() }
                else -> compareBy { it.name.lowercase() }
            }) ?: return
            for (entry in entries) {
                if (results.size >= MAX_LIST_ENTRIES) { aborted = true; return }
                val relPath = if (depth == 0) entry.name else entry.absolutePath
                val name = entry.name.lowercase()
                if (include != null && !name.contains(include.lowercase())) continue
                if (exclude != null && name.contains(exclude.lowercase())) continue
                results.add(relPath to entry)
                if (entry.isDirectory && recursive) walk(entry, depth + 1)
            }
        }

        walk(file, 0)

        if (results.isEmpty()) return ToolResult.ok("'${file.canonicalPath}' is empty.")
        val sb = StringBuilder()
        sb.appendLine("${file.canonicalPath} (${results.size} entries):")
        for ((relPath, entry) in results) {
            val typeChar = when {
                entry.isDirectory -> '/'
                entry.canExecute() -> '*'
                else -> ' '
            }
            sb.appendLine("  $typeChar $relPath ${resolver.formatSize(entry.length())}")
        }
        if (aborted) sb.append("…(truncated, max $MAX_LIST_ENTRIES entries)")
        return ToolResult.ok(sb.toString().trimEnd())
    }

    fun fileInfo(path: String): ToolResult {
        val resolved = resolver.resolveForRead(path)
        return when (resolved) {
            is FileResolver.ResolveResult.PermissionNeeded -> resolved.result
            is FileResolver.ResolveResult.Error -> ToolResult.error(resolved.message)
            is FileResolver.ResolveResult.Ok -> {
                val f = resolved.file
                if (!f.exists()) return ToolResult.error("Path '$path' does not exist.")
                val perm = resolver.checkReadPermission(f)
                if (perm != null) return perm
                val type = when {
                    f.isDirectory -> "directory"
                    f.isFile -> "file"
                    else -> "other"
                }
                val size = resolver.formatSize(f.length())
                val modified = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                    .format(java.util.Date(f.lastModified()))
                val executable = if (f.canExecute()) ", executable" else ""
                val readable = if (f.canRead()) "readable" else "not readable"
                ToolResult.ok(
                    "Path: ${f.canonicalPath}\n" +
                    "Type: $type\n" +
                    "Size: $size\n" +
                    "Modified: $modified\n" +
                    "Permissions: $readable$executable"
                )
            }
        }
    }

    fun resolvePath(input: String): String {
        return resolver.canonicalPath(input)
    }
}
