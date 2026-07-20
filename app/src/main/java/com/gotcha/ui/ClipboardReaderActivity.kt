package com.gotcha.ui

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle

class ClipboardReaderActivity : Activity() {
    private var hasAttemptedRead = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        android.util.Log.d("ClipboardReaderActivity", "onCreate")

        // Create a dummy view and set it to ensure the window has a view that can gain focus
        val dummyView = android.view.View(this).apply {
            isFocusable = true
            isFocusableInTouchMode = true
        }
        setContentView(dummyView)
        dummyView.requestFocus()

        // Delay a bit in case window focus doesn't fire
        window.decorView.postDelayed({
            android.util.Log.d(
                "ClipboardReaderActivity",
                "postDelayed triggered, read=$hasAttemptedRead"
            )
            if (!hasAttemptedRead) {
                readClipboardAndFinish("timeout")
            }
        }, 500)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        android.util.Log.d(
            "ClipboardReaderActivity",
            "onWindowFocusChanged: $hasFocus, read=$hasAttemptedRead"
        )
        if (hasFocus && !hasAttemptedRead) {
            readClipboardAndFinish("focus")
        }
    }

    private fun readClipboardAndFinish(source: String) {
        android.util.Log.d("ClipboardReaderActivity", "readClipboardAndFinish: $source")
        hasAttemptedRead = true
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            android.util.Log.d("ClipboardReaderActivity", "Retrieving primaryClip")
            val clip = cm.primaryClip
            android.util.Log.d(
                "ClipboardReaderActivity",
                "clip retrieved: count=${clip?.itemCount ?: 0}"
            )
            onClipboardRead?.invoke(clip)
        } catch (e: Exception) {
            android.util.Log.e("ClipboardReaderActivity", "Failed to read clipboard", e)
            onClipboardRead?.invoke(null)
        }

        onClipboardRead = null
        finish()
    }

    override fun onDestroy() {
        onClipboardRead = null
        super.onDestroy()
    }

    companion object {
        var onClipboardRead: ((android.content.ClipData?) -> Unit)? = null
    }
}
