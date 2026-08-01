package com.gotcha.ui.theme

import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

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
