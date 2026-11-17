package com.gotcha.terminal

import android.content.Context
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.gotcha.util.GotchaLog
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient

/**
 * Gotcha's side of what the copied Termux view asks of its owner: soft keyboard, the Ctrl and Alt
 * buttons of the extra-keys bar, and pinch-to-zoom of the text.
 *
 * Ctrl and Alt are latched: tapping the button arms it for the next key, and the view's read of it
 * disarms it again, so "Ctrl, then c" sends Ctrl+C and the next key is plain.
 */
@Suppress("TooManyFunctions") // The Termux interface has this many methods; most are one-liners.
internal class GotchaTerminalViewClient(
    private val view: TerminalView,
    initialTextSizePx: Int,
    private val textSizeLimits: IntRange,
    private val onTextSizeChanged: (Int) -> Unit
) : TerminalViewClient {

    var ctrlArmed by mutableStateOf(false)
        private set
    var altArmed by mutableStateOf(false)
        private set

    private var textSizePx = initialTextSizePx

    fun toggleCtrl() {
        ctrlArmed = !ctrlArmed
    }

    fun toggleAlt() {
        altArmed = !altArmed
    }

    override fun onScale(scale: Float): Float {
        if (scale in KEEP_ZOOM_BAND) return scale
        val step = (textSizePx * ZOOM_STEP).toInt().coerceAtLeast(1)
        val next = (if (scale > 1f) textSizePx + step else textSizePx - step)
            .coerceIn(textSizeLimits.first, textSizeLimits.last)
        if (next != textSizePx) {
            textSizePx = next
            view.setTextSize(next)
            onTextSizeChanged(next)
        }
        return 1.0f
    }

    override fun onSingleTapUp(e: MotionEvent?) {
        view.requestFocus()
        view.context.getSystemService(Context.INPUT_METHOD_SERVICE).let { it as InputMethodManager }
            .showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
    }

    /** Back leaves the screen; Escape is a button of its own. */
    override fun shouldBackButtonBeMappedToEscape(): Boolean = false

    /** Characters, not composed words: no autocorrect rewriting what reaches a shell. */
    override fun shouldEnforceCharBasedInput(): Boolean = true

    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false

    override fun isTerminalViewSelected(): Boolean = true

    override fun copyModeChanged(copyMode: Boolean) = Unit

    override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?): Boolean = false

    override fun onKeyUp(keyCode: Int, e: KeyEvent?): Boolean = false

    override fun onLongPress(event: MotionEvent?): Boolean = false

    override fun readControlKey(): Boolean = consume(ctrlArmed) { ctrlArmed = false }

    override fun readAltKey(): Boolean = consume(altArmed) { altArmed = false }

    override fun readShiftKey(): Boolean = false

    override fun readFnKey(): Boolean = false

    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean = false

    override fun onEmulatorSet() = Unit

    private inline fun consume(armed: Boolean, disarm: () -> Unit): Boolean {
        if (armed) disarm()
        return armed
    }

    // Debug builds only, errors included: Termux's messages can quote typed or printed text. See
    // TerminalRelay.
    override fun logError(tag: String?, message: String?) = GotchaLog.d(TAG) { "$tag: $message" }

    override fun logWarn(tag: String?, message: String?) = GotchaLog.d(TAG) { "$tag: $message" }

    override fun logInfo(tag: String?, message: String?) = GotchaLog.d(TAG) { "$tag: $message" }

    override fun logDebug(tag: String?, message: String?) = GotchaLog.d(TAG) { "$tag: $message" }

    override fun logVerbose(tag: String?, message: String?) = GotchaLog.d(TAG) { "$tag: $message" }

    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) =
        GotchaLog.d(TAG, e) { "$tag: $message" }

    override fun logStackTrace(tag: String?, e: Exception?) = GotchaLog.d(TAG, e) { "$tag" }

    private companion object {
        const val TAG = "Terminal"

        /** Pinches this close to 1 are ignored, so a resting finger does not jitter the size. */
        val KEEP_ZOOM_BAND = 0.9f..1.1f

        /** Each zoom step changes the text size by this fraction. */
        const val ZOOM_STEP = 0.1f
    }
}
