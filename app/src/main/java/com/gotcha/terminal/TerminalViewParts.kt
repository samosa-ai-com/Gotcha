package com.gotcha.terminal

import android.content.Context
import android.graphics.Typeface
import com.termux.view.TerminalView

/** A terminal view and the client that answers it; the view is ready to be laid out. */
internal class TerminalViewParts(val view: TerminalView, val client: GotchaTerminalViewClient)

/**
 * Builds a [TerminalView] that is complete before it is attached or laid out.
 *
 * Termux's view creates its renderer only when the text size is set, and reads it on its first
 * layout, so the client, the size and then the typeface must all be in place first. Doing this in an
 * effect that runs after composition crashes the first layout with a null renderer.
 */
internal fun newTerminalView(
    context: Context,
    initialTextSizePx: Int,
    textSizeLimits: IntRange,
    onTextSizeChanged: (Int) -> Unit
): TerminalViewParts {
    val view = TerminalView(context, null)
    val client = GotchaTerminalViewClient(view, initialTextSizePx, textSizeLimits, onTextSizeChanged)
    view.setTerminalViewClient(client)
    // The text size creates the renderer, and the typeface changes it: in this order only.
    view.setTextSize(initialTextSizePx)
    view.setTypeface(Typeface.MONOSPACE)
    view.keepScreenOn = true
    // Termux sets these in its layout XML. Without them the keyboard is never offered to the view
    // ("showSoftInput ... is not served") and typed keys reach nobody.
    view.isFocusable = true
    view.isFocusableInTouchMode = true
    return TerminalViewParts(view, client)
}
