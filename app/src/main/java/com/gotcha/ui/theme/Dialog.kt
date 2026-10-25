package com.gotcha.ui.theme

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.window.Dialog

/**
 * Dialogs, in the skin.
 *
 * The same problem [SkinDropdownMenu] exists to solve, one layer up. Material 3
 * paints an alert dialog from the `surface` role, which every glass skin leaves
 * translucent on purpose — that alpha is what makes the in-page panels read as
 * glass over the wallpaper. A dialog is not a panel: it floats over live
 * content, so the same alpha lets the screen underneath read straight through
 * the text, which is unreadable rather than atmospheric. It is worst over a busy
 * screen — the legal gate on top of a chat — but it is wrong everywhere, and a
 * confirmation the user cannot read is a confirmation they cannot give.
 *
 * Hands the dialog the same colour with the ground already behind it. See
 * [Skin.menuContainer], which names menus only because they were the first
 * floating surface to need it.
 *
 * Use this everywhere instead of `AlertDialog`. Parameter order follows the
 * Material 3 original so call sites read the same.
 */
@Composable
fun SkinAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    containerColor: Color = LocalSkin.current.menuContainer
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = confirmButton,
        modifier = modifier,
        dismissButton = dismissButton,
        title = title,
        text = text,
        containerColor = containerColor
    )
}

/**
 * A free-form dialog, in the skin: [Dialog] with its content already on an
 * opaque [Skin.menuContainer] panel.
 *
 * For dialogs [SkinAlertDialog]'s title/text/buttons layout does not fit. A bare
 * `Dialog { Surface { … } }` falls into the same trap as the Material alert
 * dialog: `Surface` defaults to the translucent `surface` role, so the screen
 * underneath reads through it on every glass skin.
 *
 * The content colour is set explicitly because `Surface` cannot derive one:
 * `contentColorFor` only recognises the scheme's own roles, and
 * [Skin.menuContainer] is not one of them, so a bare `Surface` hands its
 * children whatever `LocalContentColor` happens to be ambient — dark text on a
 * dark skin's dark panel. `onSurface` is the colour [SkinContrastTest] checks
 * against [Skin.menuContainer].
 */
@Composable
fun SkinDialog(
    onDismissRequest: () -> Unit,
    shape: Shape = MaterialTheme.shapes.extraLarge,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    content: @Composable () -> Unit
) {
    Dialog(onDismissRequest = onDismissRequest) {
        Surface(
            shape = shape,
            color = LocalSkin.current.menuContainer,
            contentColor = contentColor,
            content = content
        )
    }
}
