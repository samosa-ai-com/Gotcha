package com.gotcha.tools

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.jsoup.Jsoup
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The text pulled out of an attached document, already truncated to fit the
 * model's context window ([DocumentParser.MAX_EXTRACTED_CHARS]).
 */
data class ExtractedDocument(
    val fileName: String,
    val mimeType: String,
    /** The extracted text, truncated with a visible note when needed. */
    val text: String,
    val pageCount: Int? = null,
    val truncated: Boolean = false
)

/** A document that could not be used as an attachment, with a human-readable message. */
sealed class DocumentError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class UnsupportedFormat(detail: String) : DocumentError(
        "Unsupported document format: ${detail.ifBlank { "unknown" }}. " +
            "Supported: PDF, DOCX, XLSX, PPTX and plain text (TXT/CSV/MD/JSON/HTML). " +
            "Legacy .doc and .xls files are not supported."
    )

    class TooLarge(size: Long) : DocumentError(
        "File too large (${FileResolver.formatSizeStatic(
            size
        )}). Max: ${DocumentParser.MAX_DOC_BYTES / 1024 / 1024} MB."
    )

    class Corrupt(detail: String, cause: Throwable? = null) : DocumentError(
        "Could not read this document: ${detail.ifBlank { "unrecognized content" }}",
        cause
    )
}

/**
 * Maps MIME type / extension to the plain text of a document, so attached files
 * can be fed to the model as a regular text part.
 *
 * Pure JVM-compatible for everything except PDF, which uses pdfbox-android (only
 * reachable through [extract] on a PDF — the Android runtime and Robolectric both
 * provide the `android.util.Log` the library needs). No Android framework APIs are
 * used directly, so the ZIP/XML branches are plain-JVM unit-testable.
 */
@Suppress("TooManyFunctions")
object DocumentParser {

    /** Mirror of [FileTool]'s binary cap. */
    const val MAX_DOC_BYTES = 20L * 1024 * 1024

    /**
     * Cap on extracted text (~10k tokens at 4 chars/token). The header the
     * message builder prepends is a few dozen chars, so this dominates the budget.
     */
    const val MAX_EXTRACTED_CHARS = 40_000

    /**
     * One-time pdfbox resource-loading initialisation. pdfbox ships its glyph
     * tables as Android assets (via [com.tom_roush.pdfbox.android.PDFBoxResourceLoader]),
     * so this must be called with a Context before any PDF is parsed. Idempotent;
     * call from app startup.
     */
    fun init(context: android.content.Context) {
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(context)
    }

    private const val DOCX_MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    private const val XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    private const val PPTX_MIME = "application/vnd.openxmlformats-officedocument.presentationml.presentation"

    private val TEXT_EXTS = setOf(
        "txt", "text", "csv", "tsv", "md", "markdown", "json", "xml", "log",
        "yml", "yaml", "ini", "cfg", "conf", "properties", "env", "gitignore",
        "sh", "py", "java", "kt", "js", "ts", "css", "sql", "rtf"
    )

    private const val TRUNCATION_NOTE = "\n\n[Note: document text was truncated to fit the model's context window]"

    /**
     * Extracts the text of [fileName] from [bytes]. [mimeType] is optional; when
     * missing (or generic, e.g. `application/octet-stream`) the extension decides.
     * Throws [DocumentError] for oversized, unsupported or corrupt files.
     */
    fun extract(bytes: ByteArray, fileName: String, mimeType: String?): ExtractedDocument {
        if (bytes.size.toLong() > MAX_DOC_BYTES) throw DocumentError.TooLarge(bytes.size.toLong())
        val ext = fileName.substringAfterLast('.', "").lowercase()
        val mime = (mimeType ?: extensionMime(ext)).trim().lowercase()

        val parsedPdf = if (isPdf(mime, ext)) pdfTextAndPagesOrThrow(bytes) else null
        val raw = parsedPdf?.first ?: extractTextOrThrow(mime, ext, bytes)

        val (text, truncated) = truncate(normalizeWhitespace(raw))
        return ExtractedDocument(
            fileName = fileName,
            mimeType = mime.ifBlank { ext },
            text = text,
            pageCount = parsedPdf?.second,
            truncated = truncated
        )
    }

    /** Runs the format branch, mapping any non-[DocumentError] failure to [DocumentError.Corrupt]. */
    private fun extractTextOrThrow(mime: String, ext: String, bytes: ByteArray): String {
        return try {
            extractByFormat(mime, ext, bytes)
        } catch (e: DocumentError) {
            throw e
        } catch (e: Exception) {
            throw DocumentError.Corrupt(e.message ?: e.javaClass.simpleName, e)
        }
    }

    private fun extractByFormat(mime: String, ext: String, bytes: ByteArray): String = when {
        isDocx(mime, ext) -> docxText(bytes)
        isXlsx(mime, ext) -> xlsxText(bytes)
        isPptx(mime, ext) -> pptxText(bytes)
        isHtml(mime, ext) -> Jsoup.parse(decodeText(bytes)).text()
        isText(mime, ext) -> decodeText(bytes)
        else -> throw DocumentError.UnsupportedFormat(mime.ifBlank { ext })
    }

    // ---- format detection ----

    private fun isPdf(mime: String, ext: String): Boolean =
        mime == "application/pdf" || ext == "pdf"

    private fun isDocx(mime: String, ext: String): Boolean =
        mime.startsWith("application/vnd.openxmlformats-officedocument.wordprocessingml") || ext == "docx"

    private fun isXlsx(mime: String, ext: String): Boolean =
        mime.startsWith("application/vnd.openxmlformats-officedocument.spreadsheetml") || ext == "xlsx"

    private fun isPptx(mime: String, ext: String): Boolean =
        mime.startsWith("application/vnd.openxmlformats-officedocument.presentationml") || ext == "pptx"

    private fun isHtml(mime: String, ext: String): Boolean =
        mime == "text/html" || mime == "application/xhtml+xml" || ext == "html" || ext == "htm"

    private fun isText(mime: String, ext: String): Boolean =
        mime.startsWith("text/") || mime == "application/json" || mime == "application/xml" ||
            mime == "application/javascript" || ext in TEXT_EXTS

    private fun extensionMime(ext: String): String = when (ext) {
        "pdf" -> "application/pdf"
        "docx" -> DOCX_MIME
        "xlsx" -> XLSX_MIME
        "pptx" -> PPTX_MIME
        "html", "htm" -> "text/html"
        in TEXT_EXTS -> "text/plain"
        else -> ""
    }

    // ---- PDF (pdfbox-android) ----

    /** Maps a pdfbox failure to [DocumentError.Corrupt] so [extract] stays a single error type. */
    private fun pdfTextAndPagesOrThrow(bytes: ByteArray): Pair<String, Int?> {
        return try {
            pdfTextAndPages(bytes)
        } catch (e: Exception) {
            throw DocumentError.Corrupt(e.message ?: e.javaClass.simpleName, e)
        }
    }

    private fun pdfTextAndPages(bytes: ByteArray): Pair<String, Int?> {
        val doc = PDDocument.load(bytes)
        return try {
            PDFTextStripper().getText(doc) to doc.numberOfPages
        } finally {
            doc.close()
        }
    }

    // ---- Office documents (ZIP + XML, zero-dependency) ----

    private fun docxText(bytes: ByteArray): String = zipEntryText(bytes, "word/document.xml") { doc ->
        val paragraphs = doc.getElementsByTagNameNS("*", "p")
        (0 until paragraphs.length).joinToString("\n") { i ->
            val paragraph = paragraphs.item(i) as Element
            val runs = paragraph.getElementsByTagNameNS("*", "t")
            (0 until runs.length).joinToString("") { runs.item(it).textContent }
        }
    }

    private fun xlsxText(bytes: ByteArray): String = zipFile(bytes) { zf ->
        val shared = readSharedStrings(zf)
        val sheets = zf.entries().asSequence().mapNotNull { it.name }
            .filter { it.startsWith("xl/worksheets/") && it.endsWith(".xml") }
            .sorted()
            .toList()
        if (sheets.isEmpty()) throw DocumentError.Corrupt("archive is missing its worksheets")
        sheets.joinToString("\n\n") { sheet ->
            val doc = parseXml(zf.getInputStream(zf.getEntry(sheet)!!))
            val rows = doc.getElementsByTagNameNS("*", "row")
            (0 until rows.length).joinToString("\n") { r ->
                val row = rows.item(r) as Element
                val cells = row.getElementsByTagNameNS("*", "c")
                (0 until cells.length).joinToString(" | ") { c -> cellText(cells.item(c), shared) }
            }
        }
    }

    private fun pptxText(bytes: ByteArray): String = zipFile(bytes) { zf ->
        val slides = zf.entries().asSequence().mapNotNull { it.name }
            .filter { it.startsWith("ppt/slides/slide") && it.endsWith(".xml") }
            .sorted()
            .toList()
        if (slides.isEmpty()) throw DocumentError.Corrupt("archive is missing its slides")
        slides.joinToString("\n\n") { slide ->
            val doc = parseXml(zf.getInputStream(zf.getEntry(slide)!!))
            val texts = doc.getElementsByTagNameNS("*", "t")
            (0 until texts.length).joinToString(" ") { texts.item(it).textContent }
        }
    }

    private fun readSharedStrings(zf: ZipFile): List<String> {
        val entry = zf.getEntry("xl/sharedStrings.xml") ?: return emptyList()
        val doc = parseXml(zf.getInputStream(entry))
        val items = doc.getElementsByTagNameNS("*", "si")
        return (0 until items.length).map { i ->
            val item = items.item(i) as Element
            val ts = item.getElementsByTagNameNS("*", "t")
            (0 until ts.length).joinToString("") { ts.item(it).textContent }
        }
    }

    private fun cellText(cell: Node, shared: List<String>): String {
        val inline = firstByLocalName(cell, "is")?.let { inlineElement ->
            val ts = inlineElement.getElementsByTagNameNS("*", "t")
            (0 until ts.length).joinToString("") { ts.item(it).textContent }
        }
        if (!inline.isNullOrEmpty()) return inline
        val v = firstByLocalName(cell, "v")?.textContent?.trim().orEmpty()
        if (v.isEmpty()) return ""
        return when ((cell as? Element)?.getAttribute("t")) {
            "s" -> shared.getOrNull(v.toIntOrNull() ?: return "") ?: ""
            "str", "inlineStr" -> v
            else -> v
        }
    }

    private fun firstByLocalName(node: Node, localName: String): Element? {
        val found = (node as? Element)?.getElementsByTagNameNS("*", localName) ?: return null
        return if (found.length > 0) found.item(0) as? Element else null
    }

    // ---- ZIP/XML plumbing ----

    private fun zipEntryText(bytes: ByteArray, entryName: String, parse: (Document) -> String): String =
        zipFile(bytes) { zf ->
            val entry = zf.getEntry(entryName)
                ?: throw DocumentError.Corrupt("archive is missing '$entryName'")
            parse(parseXml(zf.getInputStream(entry)))
        }

    private fun <T> zipFile(bytes: ByteArray, block: (ZipFile) -> T): T {
        val file = File.createTempFile("gotcha-doc", ".tmp")
        try {
            file.writeBytes(bytes)
            return ZipFile(file).use { block(it) }
        } finally {
            file.delete()
        }
    }

    @Suppress("SwallowedException")
    private fun parseXml(stream: InputStream): Document {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        // Best-effort XXE hardening: a DOCTYPE-bearing or entity-referencing
        // payload is rejected (or at worst parsed without entity expansion) rather
        // than resolved against local files or network URLs. Some parsers don't
        // recognize every feature, so a rejected feature must not break parsing.
        setFeatureSafely(factory, "http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeatureSafely(factory, "http://xml.org/sax/features/external-general-entities", false)
        setFeatureSafely(factory, "http://xml.org/sax/features/external-parameter-entities", false)
        try {
            factory.setXIncludeAware(false)
            factory.setExpandEntityReferences(false)
        } catch (_: Exception) {
            // Non-fatal: hardening is best-effort on unsupported parsers.
        }
        return try {
            factory.newDocumentBuilder().parse(stream)
        } catch (e: Exception) {
            throw DocumentError.Corrupt("invalid XML: ${e.message ?: e.javaClass.simpleName}", e)
        }
    }

    @Suppress("SwallowedException")
    private fun setFeatureSafely(factory: DocumentBuilderFactory, feature: String, enabled: Boolean) {
        try {
            factory.setFeature(feature, enabled)
        } catch (_: Exception) {
            // Unsupported by this parser — degrade gracefully.
        }
    }

    // ---- plain text ----

    private fun decodeText(bytes: ByteArray): String {
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        ) {
            // Strip a UTF-8 BOM so it never leaks into the model input.
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        return String(bytes, Charsets.UTF_8)
    }

    private fun normalizeWhitespace(raw: String): String = raw
        .replace("\u0000", "")
        .replace(Regex("[\\t\\r\\x0B\\x0C]+"), " ")
        .replace(Regex("[ ]{2,}"), " ")
        .replace(Regex("( *\n *){3,}"), "\n\n")
        .trim()

    private fun truncate(text: String): Pair<String, Boolean> {
        if (text.length <= MAX_EXTRACTED_CHARS) return text to false
        val budget = (MAX_EXTRACTED_CHARS - TRUNCATION_NOTE.length).coerceAtLeast(1)
        return (text.take(budget).trimEnd() + TRUNCATION_NOTE) to true
    }
}
