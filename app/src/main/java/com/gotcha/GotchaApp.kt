package com.gotcha

import android.app.Application
import android.util.Log

/**
 * Top-level application class registering process-wide uncaught exception
 * diagnostic logging.
 */
class GotchaApp : Application() {

    override fun onCreate() {
        super.onCreate()
        setupUncaughtExceptionHandler()
    }

    private fun setupUncaughtExceptionHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                Log.e(TAG, "Uncaught exception on thread ${thread.name}: ${throwable.message}", throwable)
            } catch (_: Throwable) {
                // Ensure logging itself never throws
            }
            // Forward to system default handler to record OS diagnostics and manage process lifecycle.
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    companion object {
        private const val TAG = "GotchaApp"
    }
}
