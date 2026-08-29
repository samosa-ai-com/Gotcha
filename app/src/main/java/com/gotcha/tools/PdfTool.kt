package com.gotcha.tools

import android.content.Context
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.io.File

/**
 * Structural PDF editing on top of pdfbox-android — the write half of the
 * library [DocumentParser] already uses for text extraction.
 *
 * Covers the operations that do not require re-typesetting a page: merging,
 * splitting, extracting, deleting and rotating pages, plus a cheap [info] read
 * so the model can see the page count before choosing a range. Editing existing
 * body text is deliberately out of scope — PDF stores glyph runs at fixed
 * coordinates, so there is nothing to reflow.
 *
 * Every operation reads and writes through [FileResolver], so shared-storage
 * paths raise the usual "All files access" permission result rather than
 * throwing. Only available to Operator (writes files).
 */
@Suppress("TooManyFunctions")
class PdfTool(private val context: Context) {

    private val resolver = FileResolver(context)

    companion object {
        /**
         * Per-file input cap. Higher than [DocumentParser.MAX_DOC_BYTES] because
         * that limit protects the model's context window, which structural edits
         * never touch — this one only protects device memory.
         */
        const val MAX_PDF_BYTES = 50L * 1024 * 1024

        /** Keeps a merge from being handed an unbounded glob result. */
        const val MAX_MERGE_INPUTS = 50

        /** Ceiling on files produced by one split, so a 900-page scan cannot flood a directory. */
        const val MAX_SPLIT_FILES = 200

        /**
         * Above this, pdfbox spills its parsed objects to temp files instead of the
         * heap. Android gives an app a few hundred MB at best, and a scanned PDF
         * expands far beyond its on-disk size.
         */
        private const val MAX_MAIN_MEMORY_BYTES = 16L * 1024 * 1024

        val OPERATIONS = listOf("info", "merge", "split", "extract_pages", "delete_pages", "rotate_pages")

        private val VALID_ROTATIONS = setOf(90, 180, 270)
    }

    /**
     * Single entry point; [operation] selects the branch. Arguments that do not
     * apply to the chosen operation are ignored rather than rejected, so a model
     * that over-supplies (a `pages` on a merge) still succeeds.
     */
    @Suppress("LongParameterList", "ReturnCount")
    fun edit(
        operation: String,
        input: String? = null,
        inputs: List<String>? = null,
        output: String? = null,
        pages: String? = null,
        degrees: Int? = null,
        password: String? = null,
        overwrite: Boolean = false,
        confirmed: Boolean = false
    ): ToolResult {
        val op = operation.trim().lowercase()
        if (op !in OPERATIONS) {
            return ToolResult.error(
                "Unknown operation '$operation'. Valid operations: ${OPERATIONS.joinToString(", ")}."
            )
        }
        return try {
            when (op) {
                "info" -> info(input ?: return missing("input", op), password)
                "merge" -> merge(inputs, output ?: return missing("output", op), password, overwrite, confirmed)
                "split" -> split(input ?: return missing("input", op), output, password, overwrite, confirmed)
                "extract_pages" -> extract(
                    input ?: return missing("input", op),
                    output ?: return missing("output", op),
                    pages ?: return missing("pages", op),
                    password,
                    overwrite,
                    confirmed
                )
                "delete_pages" -> delete(
                    input ?: return missing("input", op),
                    output ?: return missing("output", op),
                    pages ?: return missing("pages", op),
                    password,
                    overwrite,
                    confirmed
                )
                else -> rotate(
                    input ?: return missing("input", op),
                    output ?: return missing("output", op),
                    pages,
                    degrees ?: return missing("degrees", op),
                    password,
                    overwrite,
                    confirmed
                )
            }
        } catch (e: Exception) {
            ToolResult.error(describeFailure(e, password != null))
        }
    }

    // ---- operations ----

    private fun info(input: String, password: String?): ToolResult {
        val file = resolveInput(input).unwrap { return it }
        return openDocument(file, password).use { doc ->
            val sizes = (0 until doc.numberOfPages).take(MAX_SPLIT_FILES).map { i ->
                val box = doc.getPage(i).mediaBox
                "%.0f×%.0f".format(box.width, box.height)
            }
            val distinct = sizes.distinct()
            val geometry = if (distinct.size == 1) {
                "all pages ${distinct.first()} pt"
            } else {
                "mixed page sizes: ${distinct.take(4).joinToString(", ")}"
            }
            ToolResult.ok(
                "'${file.canonicalPath}': ${doc.numberOfPages} page(s), ${resolver.formatSize(file.length())}, $geometry." +
                    if (doc.isEncrypted) {
                        " The file is password-protected (opened with the supplied password); any edit writes an " +
                            "UNPROTECTED copy, so tell the user that before editing it."
                    } else {
                        ""
                    }
            )
        }
    }

    @Suppress("LongParameterList")
    private fun merge(
        inputs: List<String>?,
        output: String,
        password: String?,
        overwrite: Boolean,
        confirmed: Boolean
    ): ToolResult {
        if (inputs.isNullOrEmpty()) {
            return ToolResult.error(
                "merge needs an 'inputs' array of at least two PDF paths, in the order they should be joined."
            )
        }
        if (inputs.size < 2) {
            return ToolResult.error(
                "merge needs at least two input files — got ${inputs.size}. Use extract_pages to copy a single file."
            )
        }
        if (inputs.size > MAX_MERGE_INPUTS) {
            return ToolResult.error(
                "Too many inputs (${inputs.size}). Merge at most $MAX_MERGE_INPUTS files at a time."
            )
        }
        val files = inputs.map { path -> resolveInput(path).unwrap { return it } }
        val target = resolveOutput(output, overwrite).unwrap { return it }

        val sources = mutableListOf<PDDocument>()
        return try {
            val merger = PDFMergerUtility()
            PDDocument().use { dest ->
                files.forEach { file ->
                    val src = openDocument(file, password)
                    sources.add(src)
                    decryptionConsent(src, confirmed, file.name)?.let { return it }
                    merger.appendDocument(dest, src)
                }
                val wasEncrypted = sources.any { it.isEncrypted }
                val pageCount = dest.numberOfPages
                writeDocument(dest, target)
                ToolResult.ok(
                    "Merged ${files.size} PDFs into '${target.canonicalPath}' — $pageCount page(s), " +
                        "${resolver.formatSize(target.length())}. Source order: " +
                        files.joinToString(", ") { it.name } + "." + unprotectedNote(wasEncrypted)
                )
            }
        } finally {
            sources.forEach { runCatching { it.close() } }
        }
    }

    @Suppress("LongParameterList")
    private fun split(
        input: String,
        output: String?,
        password: String?,
        overwrite: Boolean,
        confirmed: Boolean
    ): ToolResult {
        val file = resolveInput(input).unwrap { return it }
        return openDocument(file, password).use { doc ->
            decryptionConsent(doc, confirmed, file.name)?.let { return it }
            val wasEncrypted = doc.isEncrypted
            val count = doc.numberOfPages
            if (count > MAX_SPLIT_FILES) {
                return ToolResult.error(
                    "'${file.name}' has $count pages, which would create $count files (limit $MAX_SPLIT_FILES). " +
                        "Use extract_pages with a range instead."
                )
            }
            val dir = resolveOutputDir(output ?: file.parent.orEmpty()).unwrap { return it }
            val stem = file.nameWithoutExtension
            val written = mutableListOf<File>()
            for (i in 0 until count) {
                val part = File(dir, "$stem-p${i + 1}.pdf")
                if (part.exists() && !overwrite) {
                    return ToolResult.error(
                        "'${part.canonicalPath}' already exists. Pass overwrite=true to replace the split output, " +
                            "or choose a different output directory."
                    )
                }
                PDDocument().use { single ->
                    single.importPage(doc.getPage(i))
                    writeDocument(single, part)
                }
                written.add(part)
            }
            ToolResult.ok(
                "Split '${file.name}' into ${written.size} file(s) in '${dir.canonicalPath}': " +
                    written.joinToString(", ") { it.name } + "." + unprotectedNote(wasEncrypted)
            )
        }
    }

    @Suppress("LongParameterList")
    private fun extract(
        input: String,
        output: String,
        pages: String,
        password: String?,
        overwrite: Boolean,
        confirmed: Boolean
    ): ToolResult {
        val file = resolveInput(input).unwrap { return it }
        val target = resolveOutput(output, overwrite).unwrap { return it }
        return openDocument(file, password).use { doc ->
            decryptionConsent(doc, confirmed, file.name)?.let { return it }
            val wasEncrypted = doc.isEncrypted
            val selected = PdfPageSpec.parse(pages, doc.numberOfPages).unwrapSpec { return ToolResult.error(it) }
            PDDocument().use { out ->
                selected.forEach { pageNumber -> out.importPage(doc.getPage(pageNumber - 1)) }
                writeDocument(out, target)
            }
            ToolResult.ok(
                "Extracted ${selected.size} page(s) (${PdfPageSpec.describe(selected)}) from '${file.name}' " +
                    "into '${target.canonicalPath}'." + unprotectedNote(wasEncrypted)
            )
        }
    }

    @Suppress("LongParameterList")
    private fun delete(
        input: String,
        output: String,
        pages: String,
        password: String?,
        overwrite: Boolean,
        confirmed: Boolean
    ): ToolResult {
        val file = resolveInput(input).unwrap { return it }
        val target = resolveOutput(output, overwrite).unwrap { return it }
        return openDocument(file, password).use { doc ->
            decryptionConsent(doc, confirmed, file.name)?.let { return it }
            val wasEncrypted = doc.isEncrypted
            val total = doc.numberOfPages
            val doomed = PdfPageSpec.parse(pages, total).unwrapSpec { return ToolResult.error(it) }
            val keep = (1..total).filterNot { it in doomed }
            if (keep.isEmpty()) {
                return ToolResult.error(
                    "That would delete all $total page(s), leaving an empty PDF. Keep at least one page."
                )
            }
            PDDocument().use { out ->
                keep.forEach { pageNumber -> out.importPage(doc.getPage(pageNumber - 1)) }
                writeDocument(out, target)
            }
            ToolResult.ok(
                "Deleted ${doomed.size} page(s) (${PdfPageSpec.describe(doomed)}) from '${file.name}' — " +
                    "${keep.size} of $total page(s) remain in '${target.canonicalPath}'." +
                    unprotectedNote(wasEncrypted)
            )
        }
    }

    @Suppress("LongParameterList")
    private fun rotate(
        input: String,
        output: String,
        pages: String?,
        degrees: Int,
        password: String?,
        overwrite: Boolean,
        confirmed: Boolean
    ): ToolResult {
        if (degrees !in VALID_ROTATIONS) {
            return ToolResult.error(
                "degrees must be one of ${VALID_ROTATIONS.sorted().joinToString(", ")} (clockwise). Got $degrees."
            )
        }
        val file = resolveInput(input).unwrap { return it }
        val target = resolveOutput(output, overwrite).unwrap { return it }
        return openDocument(file, password).use { doc ->
            decryptionConsent(doc, confirmed, file.name)?.let { return it }
            val wasEncrypted = doc.isEncrypted
            val selected = PdfPageSpec.parse(pages ?: PdfPageSpec.ALL, doc.numberOfPages)
                .unwrapSpec { return ToolResult.error(it) }
            selected.forEach { pageNumber ->
                val page = doc.getPage(pageNumber - 1)
                page.rotation = Math.floorMod(page.rotation + degrees, 360)
            }
            writeDocument(doc, target)
            ToolResult.ok(
                "Rotated ${selected.size} page(s) (${PdfPageSpec.describe(selected)}) by $degrees° clockwise in " +
                    "'${target.canonicalPath}'." + unprotectedNote(wasEncrypted)
            )
        }
    }

    // ---- shared plumbing ----

    /**
     * Opens with security stripped, so a document unlocked by [password] is
     * saved as a readable file rather than one pdfbox refuses to write.
     */
    private fun openDocument(file: File, password: String?): PDDocument {
        val memory = MemoryUsageSetting.setupMixed(MAX_MAIN_MEMORY_BYTES).setTempDir(context.cacheDir)
        val doc = PDDocument.load(file, password.orEmpty(), memory)
        if (doc.isEncrypted) doc.setAllSecurityToBeRemoved(true)
        return doc
    }

    /**
     * Refuses to write an unprotected copy of a protected document until the user
     * has been told. pdfbox cannot preserve the source's encryption on save, so
     * every edit of a locked PDF silently strips its password — the file is a
     * bank statement or a payslip often enough that the user has to agree to that
     * first, not discover it afterwards. Mirrors `open_setting`'s confirm-first
     * gate: refuse once with the consequence spelled out, proceed on the re-call.
     *
     * Null when there is nothing to warn about (an unencrypted source, or consent
     * already given).
     */
    /**
     * Appended to a success message when the source was locked, so the stripped
     * protection is stated in the result the user hears, not only in the gate
     * they already clicked past.
     */
    private fun unprotectedNote(wasEncrypted: Boolean): String =
        if (wasEncrypted) " NOTE: the source was password-protected; this copy is NOT — it opens without a password." else ""

    private fun decryptionConsent(doc: PDDocument, confirmed: Boolean, name: String): ToolResult? {
        if (!doc.isEncrypted || confirmed) return null
        return ToolResult.error(
            "'$name' is password-protected, and the edited copy CANNOT keep that protection — it will be " +
                "written so that anyone can open it without the password. Tell the user this in plain words " +
                "with the 'question' tool, and only if they agree, call pdf_edit again with confirmed=true. " +
                "Do not set confirmed=true on your own."
        )
    }

    /**
     * Saves via a sibling temp file, so writing back over an input path cannot
     * truncate the document pdfbox is still reading from.
     */
    private fun writeDocument(doc: PDDocument, target: File) {
        val temp = File(target.parentFile, ".${target.name}.gotcha-tmp")
        try {
            doc.save(temp)
            if (target.exists() && !target.delete()) {
                throw java.io.IOException("could not replace the existing file at ${target.canonicalPath}")
            }
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
            }
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    private fun resolveInput(path: String): Result<File> {
        val file = when (val resolved = resolver.resolveForRead(path)) {
            is FileResolver.ResolveResult.PermissionNeeded -> return Result.failure(ResultCarrier(resolved.result))
            is FileResolver.ResolveResult.Error -> return fail(resolved.message)
            is FileResolver.ResolveResult.Ok -> resolved.file
        }
        resolver.checkReadPermission(file)?.let { return Result.failure(ResultCarrier(it)) }
        if (!file.exists()) {
            return fail(
                "PDF '$path' does not exist (resolved: ${file.canonicalPath}). You may use list_files or glob to find it."
            )
        }
        if (!file.isFile) return fail("'$path' is not a regular file.")
        if (file.length() > MAX_PDF_BYTES) {
            return fail(
                "'${file.name}' is ${resolver.formatSize(file.length())}; " +
                    "the limit is ${resolver.formatSize(MAX_PDF_BYTES)}."
            )
        }
        if (!looksLikePdf(file)) {
            return fail("'${file.name}' is not a PDF — it does not start with the %PDF- signature.")
        }
        return Result.success(file)
    }

    private fun resolveOutput(path: String, overwrite: Boolean): Result<File> {
        val file = when (val resolved = resolver.resolveForWrite(path)) {
            is FileResolver.ResolveResult.PermissionNeeded -> return Result.failure(ResultCarrier(resolved.result))
            is FileResolver.ResolveResult.Error -> return fail(resolved.message)
            is FileResolver.ResolveResult.Ok -> resolved.file
        }
        resolver.checkWritePermission(file)?.let { return Result.failure(ResultCarrier(it)) }
        if (file.isDirectory) return fail("Output '$path' is a directory. Give the full path of the PDF to write.")
        if (file.exists() && !overwrite) {
            return fail(
                "'${file.canonicalPath}' already exists. Pass overwrite=true to replace it, or choose a different output path."
            )
        }
        file.parentFile?.let { parent ->
            if (!parent.exists() && !parent.mkdirs()) {
                return fail("Could not create the output directory '${parent.canonicalPath}'.")
            }
        }
        return Result.success(file)
    }

    private fun resolveOutputDir(path: String): Result<File> {
        val dir = when (val resolved = resolver.resolveForWrite(path)) {
            is FileResolver.ResolveResult.PermissionNeeded -> return Result.failure(ResultCarrier(resolved.result))
            is FileResolver.ResolveResult.Error -> return fail(resolved.message)
            is FileResolver.ResolveResult.Ok -> resolved.file
        }
        resolver.checkWritePermission(dir)?.let { return Result.failure(ResultCarrier(it)) }
        if (dir.exists() && !dir.isDirectory) return fail("Split output '$path' exists but is not a directory.")
        if (!dir.exists() && !dir.mkdirs()) return fail("Could not create the output directory '${dir.canonicalPath}'.")
        return Result.success(dir)
    }

    private fun looksLikePdf(file: File): Boolean {
        val header = ByteArray(5)
        val read = file.inputStream().use { it.read(header) }
        return read == header.size && resolver.detectMime(header) == "application/pdf"
    }

    /**
     * pdfbox reports a wrong or absent password as a generic
     * `InvalidPasswordException`; without translation the model sees a stack-trace
     * message and retries the same call.
     */
    private fun describeFailure(e: Exception, hadPassword: Boolean): String {
        val name = e.javaClass.simpleName
        return when {
            name == "InvalidPasswordException" && hadPassword ->
                "The supplied password did not open this PDF. Ask the user for the correct one."
            name == "InvalidPasswordException" ->
                "This PDF is password-protected. Ask the user for the password and pass it as 'password'."
            else -> "PDF operation failed: ${e.message ?: name}"
        }
    }

    private fun missing(param: String, operation: String) =
        ToolResult.error("Missing required parameter '$param' for operation '$operation'.")

    /** Carries an already-formed [ToolResult] (permission or validation) out of a helper. */
    private class ResultCarrier(val result: ToolResult) : Exception(result.message)

    private fun fail(message: String): Result<File> = Result.failure(ResultCarrier(ToolResult.error(message)))

    /** Unwraps a page-spec failure into its model-facing message. */
    private inline fun Result<List<Int>>.unwrapSpec(onFailure: (String) -> Nothing): List<Int> =
        fold(onSuccess = { it }, onFailure = { e -> onFailure(e.message.orEmpty()) })

    /** Unwraps a [ResultCarrier] failure back into the [ToolResult] the caller returns. */
    private inline fun Result<File>.unwrap(onFailure: (ToolResult) -> Nothing): File =
        fold(
            onSuccess = { it },
            onFailure = { e -> onFailure((e as? ResultCarrier)?.result ?: ToolResult.error(e.message.orEmpty())) }
        )
}

/**
 * Parsing of the 1-based page specifications the model writes ("1-3,7,10-").
 *
 * Kept apart from [PdfTool] and free of Android APIs so the range arithmetic —
 * the part most likely to be wrong — is unit-testable without Robolectric.
 */
object PdfPageSpec {

    /** Accepted spellings for "every page". */
    const val ALL = "all"

    /**
     * Returns the selected page numbers, sorted and de-duplicated, or a failure
     * whose message is written for the model to act on.
     */
    @Suppress("ReturnCount")
    fun parse(spec: String, pageCount: Int): Result<List<Int>> {
        if (pageCount <= 0) return Result.failure(IllegalArgumentException("The document has no pages."))
        val trimmed = spec.trim()
        if (trimmed.isEmpty() || trimmed.equals(ALL, ignoreCase = true)) return Result.success((1..pageCount).toList())

        val selected = sortedSetOf<Int>()
        for (part in trimmed.split(",")) {
            val token = part.trim()
            if (token.isEmpty()) continue
            val range = parseToken(token, pageCount) ?: return Result.failure(
                IllegalArgumentException(
                    "Could not read the page range '$token'. Use 1-based page numbers like '1-3,7' or '9-' " +
                        "(9 to the end), or 'all'. The document has $pageCount page(s)."
                )
            )
            if (range.first > pageCount || range.last > pageCount) {
                return Result.failure(
                    IllegalArgumentException(
                        "Page range '$token' is out of bounds — the document has $pageCount page(s)."
                    )
                )
            }
            if (range.first > range.last) {
                return Result.failure(
                    IllegalArgumentException("Page range '$token' runs backwards; write it low-to-high.")
                )
            }
            selected.addAll(range)
        }
        if (selected.isEmpty()) {
            return Result.failure(IllegalArgumentException("No pages selected by '$spec'. Use e.g. '1-3,7' or 'all'."))
        }
        return Result.success(selected.toList())
    }

    /** One comma-separated token: "5", "2-6", or "4-" (open-ended). Null when malformed. */
    private fun parseToken(token: String, pageCount: Int): IntRange? {
        if (!token.contains('-')) {
            val single = token.toIntOrNull() ?: return null
            return if (single < 1) null else single..single
        }
        val halves = token.split("-")
        if (halves.size != 2) return null
        val start = halves[0].trim().toIntOrNull() ?: return null
        val endText = halves[1].trim()
        val end = if (endText.isEmpty()) pageCount else endText.toIntOrNull() ?: return null
        return if (start < 1 || end < 1) null else start..end
    }

    /** Collapses a page list back to a compact human description ("1-3, 7"). */
    fun describe(pages: List<Int>): String {
        if (pages.isEmpty()) return "none"
        val parts = mutableListOf<String>()
        var start = pages.first()
        var previous = start
        for (page in pages.drop(1)) {
            if (page == previous + 1) {
                previous = page
                continue
            }
            parts.add(if (start == previous) "$start" else "$start-$previous")
            start = page
            previous = page
        }
        parts.add(if (start == previous) "$start" else "$start-$previous")
        return parts.joinToString(", ")
    }
}
