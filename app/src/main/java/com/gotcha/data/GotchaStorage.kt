package com.gotcha.data

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import java.io.File
import java.io.IOException

/**
 * Single source of truth for every path Gotcha reads or writes under shared
 * storage.
 */
@Suppress("TooManyFunctions") // one accessor per well-known directory, plus the MediaStore plumbing
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
    fun podcastsRoot(): File = File(root(), "Podcasts")

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

    /**
     * Like [ensureChatDir] but never renames — returns the existing dir if any,
     * otherwise creates/returns the desired dir. Used mid-run to avoid
     * invalidating an absolute path the agent already received in the system
     * prompt (`Working directory: .../Slug_id`) when the title is generated
     * mid-run.
     */
    fun findOrCreateChatDir(sessionId: String, title: String, create: Boolean = true): File {
        val existing = findChatDir(sessionId)
        if (existing != null) {
            if (create) existing.mkdirs()
            return existing
        }
        return ensureChatDir(sessionId, title, create)
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

    /**
     * Saves [bitmap] as a PNG named [fileName] under [screenshotsRoot], returning
     * the human-readable location it landed in.
     *
     * Creating anything under the Gotcha root needs "All files access"
     * (MANAGE_EXTERNAL_STORAGE) on API 30+; without it `mkdirs()` fails and the
     * open would throw ENOENT. Since screenshot capture is a standalone feature
     * that shouldn't depend on that grant, fall back to a MediaStore insert into
     * `Pictures/Gotcha`, which needs no permission at all.
     */
    fun saveScreenshot(context: Context, fileName: String, bitmap: Bitmap): String {
        val dir = screenshotsRoot()
        if (dir.mkdirs() || dir.isDirectory) {
            val file = File(dir, fileName)
            file.outputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
            publishToGallery(context, file)
            return "Gotcha/Screenshots"
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Gotcha")
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Could not create $fileName in Pictures/Gotcha")
        resolver.openOutputStream(uri)?.use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        } ?: throw IOException("Could not open Pictures/Gotcha for writing")
        return "Pictures/Gotcha"
    }

    sealed class RecordingTarget {
        /** A caller-supplied [FileResolver] path written to directly. */
        data class DirectFile(val file: File) : RecordingTarget()

        /** MediaStore entry; [MediaRecorder] writes through the held fd. */
        data class MediaStoreEntry(val uri: Uri, val pfd: ParcelFileDescriptor, val displayPath: String) :
            RecordingTarget()
    }

    /**
     * Opens a target for [fileName] in the system-wide public Recordings folder
     * (visible to the system Recorder/Files apps), not the per-chat working
     * directory.
     *
     * There is no direct filesystem access to public folders without "All
     * files access", so recordings go through a MediaStore insert + file
     * descriptor instead; [MediaRecorder.setOutputFile] accepts that fd directly.
     */
    fun createRecordingTarget(context: Context, fileName: String): RecordingTarget {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "audio/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Recordings")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Could not create $fileName in Recordings")
        val pfd = resolver.openFileDescriptor(uri, "w")
            ?: throw IOException("Could not open Recordings for writing")
        return RecordingTarget.MediaStoreEntry(uri, pfd, "Recordings/$fileName")
    }

    /** Clears the pending flag so other apps can see/play the finished recording. */
    fun finalizeRecording(context: Context, uri: Uri) {
        val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        context.contentResolver.update(uri, values, null, null)
    }

    /** Removes an abandoned pending entry if [startAudioRecording] failed after the insert. */
    fun discardPendingRecording(context: Context, uri: Uri) {
        try {
            context.contentResolver.delete(uri, null, null)
        } catch (_: Exception) { }
    }
}
