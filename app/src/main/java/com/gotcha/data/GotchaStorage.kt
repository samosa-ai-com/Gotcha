package com.gotcha.data

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import java.io.File

/**
 * Single source of truth for every path Gotcha reads or writes under shared
 * storage. See STORAGE_PLAN.md for the full layout and rationale.
 */
object GotchaStorage {

    private var overrideRootPath: String? = null

    /**
     * Overridable so JVM unit tests can point at a TemporaryFolder. The Android
     * default is computed lazily on first access so plain JUnit tests (no
     * Android framework available) can set this before it's ever touched.
     */
    var rootPath: String
        get() = overrideRootPath ?: File(Environment.getExternalStorageDirectory(), "Gotcha").absolutePath
        set(value) { overrideRootPath = value }

    enum class Kind(val dirName: String) {
        PICTURES("Pictures"),
        RECORDINGS("Recordings"),
        SCREENSHOTS("Screenshots"),
        DEBUG(".debug")
    }

    fun root(): File = File(rootPath)
    fun chatsRoot(): File = File(root(), "chats")
    fun callsRoot(): File = File(root(), "calls")
    fun archiveRoot(): File = File(root(), "old_chats")
    fun screenshotsRoot(): File = File(root(), "Screenshots")
    fun downloadsRoot(): File = File(root(), "Downloads")

    /**
     * Non-alphanumeric runs collapse to a single '-'; leading/trailing '-' trimmed;
     * truncated to 40 chars; blank input falls back to "New-Chat".
     */
    fun slugify(title: String): String {
        val collapsed = title
            .replace(Regex("[^\\p{L}\\p{N}]+"), "-")
            .trim('-')
            .take(40)
            .trim('-')
        return collapsed.ifEmpty { "New-Chat" }
    }

    fun shortId(sessionId: String): String = sessionId.replace("-", "").take(8)

    fun chatDirName(title: String, sessionId: String): String = "${slugify(title)}_${shortId(sessionId)}"

    /** Existing dir for this session under [chatsRoot], matched by "_<shortId>" suffix. */
    fun findChatDir(sessionId: String): File? {
        val suffix = "_${shortId(sessionId)}"
        return chatsRoot().listFiles()?.firstOrNull { it.isDirectory && it.name.endsWith(suffix) }
    }

    /**
     * Resolves the chat directory for [sessionId] / [title], renaming an existing
     * dir in place if the title changed (contents preserved), and creating it
     * (and any renamed target) when [create] is true. Idempotent: calling again
     * with the same title is a no-op beyond returning the same dir.
     */
    fun ensureChatDir(sessionId: String, title: String, create: Boolean = true): File {
        val desiredName = chatDirName(title, sessionId)
        val existing = findChatDir(sessionId)
        val dir = if (existing != null) {
            if (existing.name != desiredName) {
                val target = File(chatsRoot(), desiredName)
                if (existing.renameTo(target)) target else existing
            } else {
                existing
            }
        } else {
            File(chatsRoot(), desiredName)
        }
        if (create) dir.mkdirs()
        return dir
    }

    fun subdir(chatDir: File, kind: Kind): File {
        val dir = File(chatDir, kind.dirName)
        dir.mkdirs()
        return dir
    }

    /** Move chats/<dir> -> old_chats/<dir>, suffixing _<millis> on collision. */
    fun archiveChatDir(sessionId: String) {
        val dir = findChatDir(sessionId) ?: return
        val archive = archiveRoot()
        archive.mkdirs()
        var target = File(archive, dir.name)
        if (target.exists()) {
            target = File(archive, "${dir.name}_${System.currentTimeMillis()}")
        }
        if (!dir.renameTo(target)) {
            dir.copyRecursively(target, overwrite = true)
            dir.deleteRecursively()
        }
    }

    /** Scans [file] into the MediaStore so it shows up in the Gallery. */
    fun publishToGallery(context: Context, file: File) {
        MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), null, null)
    }
}
