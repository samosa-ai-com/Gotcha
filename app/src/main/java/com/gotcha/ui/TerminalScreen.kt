package com.gotcha.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.KeyEvent
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gotcha.R
import com.gotcha.terminal.GotchaTerminalViewClient
import com.gotcha.terminal.TerminalUiState
import com.gotcha.terminal.TerminalViewModel
import com.gotcha.terminal.newTerminalView
import com.gotcha.terminal.refusalMessage
import com.gotcha.terminal.typeExtraText
import com.gotcha.ui.theme.SkinDropdownMenu
import com.termux.view.TerminalView
import kotlinx.coroutines.delay

/**
 * Settings ▸ Linux shell ▸ Terminal: a real terminal in the Linux system of Gotcha Shell.
 *
 * The session lives in [TerminalViewModel], so rotating the phone or leaving the screen does not
 * end it; "End the session" or `exit` does. The view is Termux's, copied unchanged into
 * `third_party/termux-terminal`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(onBack: () -> Unit, viewModel: TerminalViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    var menuOpen by remember { mutableStateOf(false) }

    // Only a screen that finds nothing open starts a session; after a refusal the user decides.
    LaunchedEffect(Unit) { if (viewModel.state.value is TerminalUiState.Idle) viewModel.open() }
    ResizeForKeyboard()

    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                title = { Text(stringResource(SettingsPage.LINUX_TERMINAL.title)) },
                navigationIcon = {
                    TextButton(onClick = onBack, modifier = Modifier.testTag("settings_back")) {
                        Text(stringResource(R.string.action_back))
                    }
                },
                actions = {
                    if (state is TerminalUiState.Live) {
                        IconButton(onClick = { menuOpen = true }, modifier = Modifier.testTag("terminal_menu")) {
                            Icon(
                                Icons.Default.MoreVert,
                                contentDescription = stringResource(R.string.linux_terminal_more)
                            )
                        }
                        TerminalMenu(
                            expanded = menuOpen,
                            ended = (state as TerminalUiState.Live).ended,
                            onDismiss = { menuOpen = false },
                            viewModel = viewModel,
                            onEnded = onBack
                        )
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize().background(Color.Black)) {
            when (val current = state) {
                TerminalUiState.Idle, TerminalUiState.Opening ->
                    Message(stringResource(R.string.linux_terminal_opening), busy = true)
                is TerminalUiState.Refused -> Refusal(current, onRetry = { viewModel.open() })
                is TerminalUiState.Live -> TerminalPane(current, viewModel)
            }
        }
    }
}

/**
 * While this screen is up the window shrinks for the keyboard instead of sliding under it or
 * panning, so the last rows and the extra-keys bar stay visible and the terminal reports its
 * true size. The window's previous mode is put back when the screen goes.
 */
@Composable
private fun ResizeForKeyboard() {
    val context = LocalContext.current
    DisposableEffect(context) {
        val window = generateSequence(context) { (it as? ContextWrapper)?.baseContext }
            .filterIsInstance<Activity>().firstOrNull()?.window
        val previous = window?.attributes?.softInputMode
        window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        onDispose { previous?.let { window.setSoftInputMode(it) } }
    }
}

@Composable
private fun TerminalMenu(
    expanded: Boolean,
    ended: Boolean,
    onDismiss: () -> Unit,
    viewModel: TerminalViewModel,
    onEnded: () -> Unit
) {
    SkinDropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        if (!ended) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.linux_terminal_paste)) },
                onClick = {
                    onDismiss()
                    viewModel.paste()
                },
                modifier = Modifier.testTag("terminal_paste")
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.linux_terminal_reset)) },
                onClick = {
                    onDismiss()
                    viewModel.resetTerminal()
                }
            )
        }
        DropdownMenuItem(
            text = { Text(stringResource(R.string.linux_terminal_end_session)) },
            onClick = {
                onDismiss()
                viewModel.close()
                // Nothing is left to show here, so leave the screen.
                onEnded()
            },
            modifier = Modifier.testTag("terminal_end")
        )
    }
}

@Composable
private fun Message(text: String, busy: Boolean = false) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (busy) {
            CircularProgressIndicator(modifier = Modifier.size(32.dp), color = Color.White)
            Box(modifier = Modifier.height(16.dp))
        }
        Text(text, color = Color.White, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun Refusal(state: TerminalUiState.Refused, onRetry: () -> Unit) {
    val context = LocalContext.current
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            refusalMessage(context, state.refusal),
            color = Color.White,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.testTag("terminal_refusal")
        )
        Box(modifier = Modifier.height(16.dp))
        Button(onClick = onRetry, modifier = Modifier.testTag("terminal_retry")) {
            Text(stringResource(R.string.linux_terminal_retry))
        }
    }
}

/** The Termux view, with the extra-keys bar under it while the session runs. */
@Composable
private fun TerminalPane(live: TerminalUiState.Live, viewModel: TerminalViewModel) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val prefs = remember(context) { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    val limits = remember(density) { with(density) { MIN_TEXT_SP.sp.roundToPx()..MAX_TEXT_SP.sp.roundToPx() } }
    val initialPx = remember(density) {
        val default = with(density) { DEFAULT_TEXT_SP.sp.roundToPx() }
        prefs.getInt(KEY_TEXT_PX, default).coerceIn(limits.first, limits.last)
    }
    val parts = remember(context) {
        newTerminalView(context, initialPx, limits) { px -> prefs.edit().putInt(KEY_TEXT_PX, px).apply() }
    }
    val view = parts.view
    val client = parts.client

    DisposableEffect(view) {
        viewModel.attachView(view)
        onDispose { viewModel.attachView(null) }
    }
    LaunchedEffect(view, live.ended) {
        val keyboard = context.getSystemService(InputMethodManager::class.java)
        if (!live.ended) {
            view.requestFocus()
            // The window needs a moment to hand the keyboard to a newly focused view.
            delay(KEYBOARD_DELAY_MS)
            keyboard?.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
        } else {
            // Nothing takes typing once the shell ended; give the room back to its last screen.
            keyboard?.hideSoftInputFromWindow(view.windowToken, 0)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { view },
            update = { it.attachSession(live.session) },
            modifier = Modifier.weight(1f).fillMaxWidth().testTag("terminal_view")
        )
        if (live.ended) {
            EndedBar(onRestart = { viewModel.open() })
        } else {
            ExtraKeys(client = client, view = view)
        }
    }
}

@Composable
private fun EndedBar(onRestart: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().background(Color(ENDED_BAR)).padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(stringResource(R.string.linux_terminal_ended), color = Color.White)
        Button(onClick = onRestart, modifier = Modifier.testTag("terminal_restart")) {
            Text(stringResource(R.string.linux_terminal_restart))
        }
    }
}

/** What each button of the extra-keys bar does. */
private sealed interface ExtraKey {
    data class Key(val label: String, val description: String?, val keyCode: Int) : ExtraKey
    data class Text(val text: String) : ExtraKey
    data object Ctrl : ExtraKey
    data object Alt : ExtraKey
}

@Composable
private fun ExtraKeys(client: GotchaTerminalViewClient, view: TerminalView) {
    val keys = listOf(
        ExtraKey.Key(stringResource(R.string.linux_terminal_key_esc), null, KeyEvent.KEYCODE_ESCAPE),
        ExtraKey.Key(stringResource(R.string.linux_terminal_key_tab), null, KeyEvent.KEYCODE_TAB),
        ExtraKey.Ctrl,
        ExtraKey.Alt,
        ExtraKey.Key("←", stringResource(R.string.linux_terminal_key_left), KeyEvent.KEYCODE_DPAD_LEFT),
        ExtraKey.Key("↑", stringResource(R.string.linux_terminal_key_up), KeyEvent.KEYCODE_DPAD_UP),
        ExtraKey.Key("↓", stringResource(R.string.linux_terminal_key_down), KeyEvent.KEYCODE_DPAD_DOWN),
        ExtraKey.Key("→", stringResource(R.string.linux_terminal_key_right), KeyEvent.KEYCODE_DPAD_RIGHT),
        ExtraKey.Text("-"),
        ExtraKey.Text("/"),
        ExtraKey.Text("|"),
        ExtraKey.Text("~")
    )
    Row(modifier = Modifier.fillMaxWidth().background(Color(KEY_BAR))) {
        keys.forEach { key ->
            when (key) {
                is ExtraKey.Key -> KeyButton(key.label, armed = false, description = key.description) {
                    view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key.keyCode))
                    view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key.keyCode))
                }
                is ExtraKey.Text -> KeyButton(key.text, armed = false) { typeExtraText(view, key.text) }
                ExtraKey.Ctrl -> KeyButton(
                    stringResource(R.string.linux_terminal_key_ctrl),
                    armed = client.ctrlArmed,
                    tag = "terminal_key_ctrl"
                ) { client.toggleCtrl() }
                ExtraKey.Alt -> KeyButton(
                    stringResource(R.string.linux_terminal_key_alt),
                    armed = client.altArmed,
                    tag = "terminal_key_alt"
                ) { client.toggleAlt() }
            }
        }
    }
}

@Composable
private fun RowScope.KeyButton(
    label: String,
    armed: Boolean,
    description: String? = null,
    tag: String? = null,
    onClick: () -> Unit
) {
    val modifier = Modifier
        .weight(1f)
        .height(KEY_HEIGHT.dp)
        .let { if (tag != null) it.testTag(tag) else it }
        .let { if (description != null) it.semantics { contentDescription = description } else it }
    val content: @Composable () -> Unit = {
        Text(label, fontSize = KEY_TEXT_SP.sp, maxLines = 1, color = if (armed) Color.Unspecified else Color.White)
    }
    val padding = PaddingValues(0.dp)
    if (armed) {
        FilledTonalButton(onClick = onClick, modifier = modifier, contentPadding = padding) {
            content()
        }
    } else {
        TextButton(onClick = onClick, modifier = modifier, contentPadding = padding) {
            content()
        }
    }
}

private const val PREFS = "terminal"
private const val KEY_TEXT_PX = "text_size_px"
private const val DEFAULT_TEXT_SP = 14
private const val MIN_TEXT_SP = 8
private const val MAX_TEXT_SP = 32
private const val KEYBOARD_DELAY_MS = 300L
private const val KEY_HEIGHT = 44
private const val KEY_TEXT_SP = 14
private const val KEY_BAR = 0xFF1B1B1F
private const val ENDED_BAR = 0xFF2A2A30
