package com.gotcha.ui

import androidx.compose.runtime.Composable

/**
 * The Permissions page. The groups themselves live in [PermissionsSection],
 * which reads live grant state from Android on every resume — this is only the
 * page frame around it.
 */
@Composable
fun PermissionsScreen(
    packageName: String,
    onBack: () -> Unit,
    /** Offered on the Termux row as a shortcut to its guided setup page. */
    onOpenTermuxSetup: (() -> Unit)? = null
) {
    val overlay = rememberSettingsOverlayState()
    SettingsScaffold(title = SettingsPage.PERMISSIONS.title, onBack = onBack, overlay = overlay) {
        PermissionsSection(packageName = packageName, onOpenTermuxSetup = onOpenTermuxSetup)
    }
}
