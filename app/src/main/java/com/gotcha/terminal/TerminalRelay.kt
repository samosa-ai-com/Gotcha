package com.gotcha.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.gotcha.util.GotchaLog
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView

/**
 * What the emulator tells its owner: the screen changed, the title changed, text should be copied,
 * the session ended. The session outlives any one screen, so the view that currently shows it is
 * attached and detached through [view] rather than being the client itself.
 *
 * All of these arrive on the main thread.
 */
internal class TerminalRelay(private val context: Context) : TerminalSessionClient {

    /** The view on screen, if any. */
    @Volatile
    var view: TerminalView? = null

    /** Called once when the shell ends. */
    var onFinished: () -> Unit = {}

    override fun onTextChanged(changedSession: TerminalSession) {
        view?.onScreenUpdated()
    }

    override fun onTitleChanged(changedSession: TerminalSession) = Unit

    override fun onSessionFinished(finishedSession: TerminalSession) {
        view?.onScreenUpdated()
        onFinished()
    }

    override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {
        if (text.isNullOrEmpty()) return
        clipboard()?.setPrimaryClip(ClipData.newPlainText("terminal", text))
    }

    override fun onPasteTextFromClipboard(session: TerminalSession?) {
        val clip = clipboard()?.primaryClip ?: return
        if (clip.itemCount == 0) return
        val text = clip.getItemAt(0).coerceToText(context)?.toString().orEmpty()
        if (text.isNotEmpty()) session?.emulator?.paste(text)
    }

    override fun onBell(session: TerminalSession) = Unit

    override fun onColorsChanged(session: TerminalSession) {
        view?.onScreenUpdated()
    }

    override fun onTerminalCursorStateChange(state: Boolean) = Unit

    override fun getTerminalCursorStyle(): Int? = null

    // Debug builds only, errors included: Termux's messages quote what the shell printed (control
    // strings, OSC 52 clipboard text), which must not reach logcat in a release build.
    override fun logError(tag: String?, message: String?) = GotchaLog.d(TAG) { "$tag: $message" }

    override fun logWarn(tag: String?, message: String?) = GotchaLog.d(TAG) { "$tag: $message" }

    override fun logInfo(tag: String?, message: String?) = GotchaLog.d(TAG) { "$tag: $message" }

    override fun logDebug(tag: String?, message: String?) = GotchaLog.d(TAG) { "$tag: $message" }

    override fun logVerbose(tag: String?, message: String?) = GotchaLog.d(TAG) { "$tag: $message" }

    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) =
        GotchaLog.d(TAG, e) { "$tag: $message" }

    override fun logStackTrace(tag: String?, e: Exception?) = GotchaLog.d(TAG, e) { "$tag" }

    private fun clipboard(): ClipboardManager? = context.getSystemService(ClipboardManager::class.java)

    private companion object {
        const val TAG = "Terminal"
    }
}
