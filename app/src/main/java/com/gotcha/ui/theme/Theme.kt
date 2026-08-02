package com.gotcha.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
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
 * @param skinId which entry of [Skins] the user picked. A skin is the whole
 *   theme: there is no light/dark switch on top of it, because a skin that has
 *   to work in both is two designs sharing a name. Deep Space ships as two.
 */
@Composable
fun GotchaTheme(
    skinId: String = Skins.DEFAULT_ID,
    content: @Composable () -> Unit
) {
    val tier = rememberGlassTier()
    val picked = Skins.byId(skinId)
    // The tier decides what the skin is made of, not whether the user gets it.
    val skin = if (tier == GlassTier.SOLID) picked.opaque() else picked

    SystemBars(skin)

    CompositionLocalProvider(
        LocalSkin provides skin,
        LocalGlassTier provides tier,
        LocalAnimationsEnabled provides rememberAnimationsEnabled()
    ) {
        MaterialTheme(
            // Animated so changing skin is one movement rather than one frame in
            // which every colour on screen is suddenly different.
            colorScheme = skin.scheme.animated(),
            typography = Typography,
            content = content
        )
    }
}

/**
 * Status- and navigation-bar icons follow the skin's own ground. Reading the
 * device's dark-mode flag here is how the clock disappears on Orchid, which is
 * a dark violet ground whatever the system thinks the time of day is.
 */
@Composable
private fun SystemBars(skin: Skin) {
    val view = LocalView.current
    if (view.isInEditMode) return
    val lightBars = skin.darkSystemBarIcons
    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = lightBars
            isAppearanceLightNavigationBars = lightBars
        }
    }
}
