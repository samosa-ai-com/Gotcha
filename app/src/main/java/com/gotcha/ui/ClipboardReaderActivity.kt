package com.gotcha.ui

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle

class ClipboardReaderActivity : Activity() {
    private var hasAttemptedRead = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Delay a bit in case window focus doesn't fire
        window.decorView.postDelayed({
            if (!hasAttemptedRead) {
                readClipboardAndFinish()
            }
        }, 500)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !hasAttemptedRead) {
            readClipboardAndFinish()
        }
    }

    private fun readClipboardAndFinish() {
        hasAttemptedRead = true
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = cm.primaryClip
            onClipboardRead?.invoke(clip)
        } catch (e: SecurityException) {
            android.util.Log.e("ClipboardReaderActivity", "Failed to read clipboard", e)
            onClipboardRead?.invoke(null)
        }

        onClipboardRead = null
        finish()
    }

    @Suppress("DEPRECATION")
    override fun overridePendingTransition(enterAnim: Int, exitAnim: Int) {
        super.overridePendingTransition(0, 0)
    }

    companion object {
        var onClipboardRead: ((android.content.ClipData?) -> Unit)? = null
    }
}
