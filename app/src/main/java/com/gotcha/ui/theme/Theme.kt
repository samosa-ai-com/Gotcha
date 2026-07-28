package com.gotcha.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

internal val DarkColorScheme = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = DarkOnPrimaryContainer,
    secondary = DarkSecondary,
    secondaryContainer = DarkSecondaryContainer,
    onSecondaryContainer = DarkOnSecondaryContainer,
    surface = DeepSpace,
    background = DeepSpace,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant
)

internal val LightColorScheme = lightColorScheme(
    primary = LightPrimary,
    onPrimary = LightOnPrimary,
    primaryContainer = LightPrimaryContainer,
    onPrimaryContainer = LightOnPrimaryContainer,
    secondary = LightSecondary,
    secondaryContainer = LightSecondaryContainer,
    onSecondaryContainer = LightOnSecondaryContainer,
    surface = LightSurface,
    background = LightSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant
)

/**
 * @param skinId which entry of [Skins] the user picked.
 * @param matchSystemBrightness swap a paired skin for its twin when the device
 *   flips between light and dark, instead of keeping one skin in both.
 * @param frostPercent scales the skin's frost and scrim, 0–100.
 * @param reduceTransparency our own accessibility switch — Android has no
 *   system-level equivalent, so this is the only way to ask for solid panels.
 */
@Composable
fun GotchaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    skinId: String = Skins.DEFAULT_ID,
    matchSystemBrightness: Boolean = true,
    reduceTransparency: Boolean = false,
    frostPercent: Int = 100,
    content: @Composable () -> Unit
) {
    val tier = rememberGlassTier(reduceTransparency)
    val picked = Skins.resolve(skinId, matchSystemBrightness, darkTheme)
        .withFrost(frostPercent)
    // The tier decides what the skin is made of, not whether the user gets it.
    val skin = if (tier == GlassTier.SOLID) picked.opaque() else picked

    val colorScheme = resolveScheme(skin, darkTheme)
    SystemBars(skin, darkTheme)

    CompositionLocalProvider(
        LocalSkin provides skin,
        LocalGlassTier provides tier
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}

/**
 * Material You is now a skin the user picks rather than the silent default it
 * used to be. It has to be, once there is a picker at all: dynamic colour
 * overrides every palette here, so leaving it on would have made choosing a
 * skin do nothing visible.
 */
@Composable
private fun resolveScheme(skin: Skin, darkTheme: Boolean): ColorScheme {
    val dynamic = skin.id == Skins.System.id &&
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    if (!dynamic) return skin.scheme(darkTheme)
    val context = LocalContext.current
    return if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
}

/**
 * Status- and navigation-bar icons follow the *skin's* ground, not the device's
 * dark-mode flag. Orchid is a dark violet ground that keeps light icons even in
 * light mode; reading `isSystemInDarkTheme` here is how the clock disappears.
 */
@Composable
private fun SystemBars(skin: Skin, darkTheme: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return
    val lightBars = if (skin.brightness == Brightness.ADAPTIVE) {
        !darkTheme
    } else {
        skin.darkSystemBarIcons
    }
    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = lightBars
            isAppearanceLightNavigationBars = lightBars
        }
    }
}
