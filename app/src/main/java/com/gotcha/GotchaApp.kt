package com.gotcha

import android.app.Application
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Top-level application class registering process-wide uncaught exception
 * diagnostic logging.
 *
 * Each crash is logged to logcat (so `adb logcat` and the system dropbox
 * still see it) and also appended to `filesDir/crash.log` so post-mortem
 * analysis is possible from a user report even after the logcat buffer has
 * rolled over. The file is capped at the most recent [MAX_CRASH_ENTRIES]
 * entries to bound its size.
 */
class GotchaApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // pdfbox ships its glyph tables as Android assets; the resource loader
        // needs a Context before the first PDF text extraction (DocumentParser).
        com.gotcha.tools.DocumentParser.init(this)
        setupUncaughtExceptionHandler()
    }

    private fun setupUncaughtExceptionHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                Log.e(TAG, "Uncaught exception on thread ${thread.name}: ${throwable.message}", throwable)
                appendCrashLog(thread, throwable)
            } catch (_: Throwable) {
                // Ensure logging itself never throws
            }
            // Forward to system default handler to record OS diagnostics and manage process lifecycle.
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun appendCrashLog(thread: Thread, throwable: Throwable) {
        val file = File(filesDir, CRASH_LOG_FILE)
        // SimpleDateFormat is not thread-safe; a crash loop can hit this from
        // two threads at once, so a per-call formatter is required.
        val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).format(Date())
        val stack = StringWriter().also { sw -> throwable.printStackTrace(PrintWriter(sw)) }.toString()
        val firstFrames = stack.lineSequence().take(MAX_STACK_FRAMES).joinToString("\n")
        val entry = buildString {
            append(timestamp)
            append(" | thread=").append(thread.name)
            append(" | type=").append(throwable.javaClass.name)
            append(" | message=").append(throwable.message.orEmpty())
            append('\n')
            append(firstFrames)
        }
        val existing = if (file.exists()) file.readText() else ""
        // Newest first: prepend, then trim the tail to the cap.
        file.writeText(trimToMaxEntries(entry + ENTRY_SEPARATOR + existing))
    }

    private fun trimToMaxEntries(content: String): String {
        val entries = content.split(ENTRY_SEPARATOR).filter { it.isNotBlank() }
        val retained = if (entries.size <= MAX_CRASH_ENTRIES) entries else entries.take(MAX_CRASH_ENTRIES)
        return retained.joinToString(ENTRY_SEPARATOR)
    }

    companion object {
        private const val TAG = "GotchaApp"
        private const val CRASH_LOG_FILE = "crash.log"
        private const val MAX_CRASH_ENTRIES = 50
        private const val MAX_STACK_FRAMES = 20

        /**
         * Standalone block separating crash entries. Kept deliberately
         * distinctive: a Java stack frame is always `\tat ...` and exception
         * messages rarely contain a full line of equals signs, so this is safe
         * to split on where a bare `---` could collide with message text.
         */
        private const val ENTRY_SEPARATOR = "\n\n===== Gotcha Crash =====\n\n"
    }
}
