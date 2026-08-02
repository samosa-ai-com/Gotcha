package com.gotcha.ui.theme

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBoxScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Menus, in the skin.
 *
 * Material 3 paints a menu popup from the `surface` role, and a glass skin
 * leaves that role translucent on purpose — it is what makes the in-page panels
 * read as glass over the wallpaper. A popup is not a panel: it floats over live
 * content, so the same alpha lets the page underneath read through the items,
 * which is unreadable rather than atmospheric. These wrappers hand the menu the
 * same colour with the ground already behind it. See [Skin.menuContainer].
 *
 * Use them everywhere instead of `DropdownMenu` and `ExposedDropdownMenu`.
 */
@Composable
private fun MenuColors(content: @Composable () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    MaterialTheme(
        colorScheme = scheme.copy(surface = LocalSkin.current.menuContainer),
        typography = MaterialTheme.typography,
        shapes = MaterialTheme.shapes,
        content = content
    )
}

/** [ExposedDropdownMenuBoxScope.ExposedDropdownMenu], on an opaque container. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExposedDropdownMenuBoxScope.SkinExposedDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    MenuColors {
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = onDismissRequest,
            modifier = modifier,
            content = content
        )
    }
}

/** [DropdownMenu], on an opaque container. */
@Composable
fun SkinDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    MenuColors {
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = onDismissRequest,
            modifier = modifier,
            content = content
        )
    }
}
