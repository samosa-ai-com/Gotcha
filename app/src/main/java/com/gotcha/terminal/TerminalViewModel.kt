package com.gotcha.terminal

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gotcha.tools.CompanionTerminal
import com.gotcha.tools.ShellSession
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** How the terminal gets a session; the real one asks Gotcha Shell, tests pass a stand-in. */
fun interface TerminalOpener {
    suspend fun open(rows: Int, cols: Int): CompanionTerminal.Opened
}

/** What the terminal screen shows. */
sealed interface TerminalUiState {
    /** Nothing open and nothing asked yet. */
    data object Idle : TerminalUiState

    data object Opening : TerminalUiState

    /** A session. When [ended] its last screen stays readable, and a new session can be started. */
    data class Live(val session: TerminalSession, val ended: Boolean = false) : TerminalUiState

    data class Refused(val refusal: CompanionTerminal.Refusal) : TerminalUiState
}

/**
 * Keeps the terminal session alive across screen rotation and across leaving the screen, which an
 * activity-scoped view model does and `remember` does not: rotation recreates the activity. The
 * session ends when the user ends it, when the shell exits, or when the activity is finished.
 */
class TerminalViewModel internal constructor(
    application: Application,
    private val opener: TerminalOpener,
    private val io: CoroutineDispatcher
) : AndroidViewModel(application) {

    /** What the framework calls: the real opener, off the main thread where it blocks. */
    constructor(application: Application) : this(
        application,
        CompanionTerminal(application).let { terminal -> TerminalOpener { rows, cols -> terminal.open(rows, cols) } },
        Dispatchers.IO
    )

    private val _state = MutableStateFlow<TerminalUiState>(TerminalUiState.Idle)
    val state: StateFlow<TerminalUiState> = _state.asStateFlow()

    private var relay: TerminalRelay? = null
    private var shell: ShellSession? = null

    /**
     * Opens a session unless one is open or opening. [rows] and [columns] are a first guess: the
     * view reports its real size as soon as it has one.
     */
    fun open(rows: Int = DEFAULT_ROWS, columns: Int = DEFAULT_COLUMNS) {
        val current = _state.value
        if (current is TerminalUiState.Opening || (current is TerminalUiState.Live && !current.ended)) return
        discardEnded()
        _state.value = TerminalUiState.Opening
        viewModelScope.launch {
            when (val opened = opener.open(rows, columns)) {
                is CompanionTerminal.Opened.Refused -> _state.value = TerminalUiState.Refused(opened.refusal)
                is CompanionTerminal.Opened.Session -> start(opened.session)
            }
        }
    }

    private fun start(opened: ShellSession) {
        val client = TerminalRelay(getApplication())
        val session = TerminalSession(opened.input, ShellHost(opened), client, TRANSCRIPT_ROWS)
        client.onFinished = { markEnded(session) }
        shell = opened
        relay = client
        _state.value = TerminalUiState.Live(session)
    }

    private fun markEnded(session: TerminalSession) {
        val current = _state.value
        if (current is TerminalUiState.Live && current.session === session && !current.ended) {
            _state.value = current.copy(ended = true)
            releaseShell()
        }
    }

    /** The view showing the session, or null when none is on screen; it is told when the screen changes. */
    fun attachView(view: TerminalView?) {
        relay?.view = view
    }

    /** Pastes the clipboard into the shell. */
    fun paste() {
        val current = _state.value as? TerminalUiState.Live ?: return
        if (!current.ended) relay?.onPasteTextFromClipboard(current.session)
    }

    /** Clears the screen state, as the terminal's own reset does. */
    fun resetTerminal() {
        (_state.value as? TerminalUiState.Live)?.takeIf { !it.ended }?.session?.reset()
    }

    /** Ends the session the user asked to end, and goes back to nothing open. */
    fun close() {
        val current = _state.value
        if (current is TerminalUiState.Live && !current.ended) current.session.finishIfRunning()
        releaseShell()
        relay?.view = null
        relay = null
        _state.value = TerminalUiState.Idle
    }

    private fun discardEnded() {
        releaseShell()
        relay = null
    }

    /** Lets go of the connection's file descriptors off the main thread, since closing asks Binder. */
    private fun releaseShell() {
        val toClose = shell ?: return
        shell = null
        viewModelScope.launch(io) { toClose.close() }
    }

    override fun onCleared() {
        val current = _state.value
        if (current is TerminalUiState.Live && !current.ended) current.session.finishIfRunning()
        shell?.let { leftOver -> kotlinx.coroutines.runBlocking { leftOver.close() } }
        shell = null
    }

    /** Keystrokes and size changes to the shell, off the main thread (the session calls these on its writer thread). */
    private class ShellHost(private val shell: ShellSession) : TerminalSession.Host {
        override fun write(data: ByteArray, offset: Int, count: Int) {
            try {
                shell.output.write(data, offset, count)
                shell.output.flush()
            } catch (_: java.io.IOException) {
                // The session is ending; the reader reports it.
            }
        }

        override fun resize(rows: Int, columns: Int) {
            kotlinx.coroutines.runBlocking { shell.resize(rows, columns) }
        }

        override fun hangUp() {
            kotlinx.coroutines.runBlocking { shell.close() }
        }
    }

    companion object {
        const val DEFAULT_ROWS = 24
        const val DEFAULT_COLUMNS = 80

        /** Lines of scrollback the emulator keeps. */
        const val TRANSCRIPT_ROWS = 2000
    }
}
