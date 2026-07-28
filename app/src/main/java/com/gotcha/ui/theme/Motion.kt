package com.gotcha.ui.theme

import android.provider.Settings
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/** How long a theme takes to become another theme. */
const val SKIN_TRANSITION_MS = 320

/**
 * Whether this device wants animation at all.
 *
 * Android exposes the accessibility "Remove animations" preference as an
 * animator duration scale of zero. Compose does not consult it for us, so every
 * animation we write has to ask — otherwise "remove animations" removes
 * everyone's animations except ours.
 */
val LocalAnimationsEnabled = staticCompositionLocalOf { true }

@Composable
fun rememberAnimationsEnabled(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        val scale = Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f
        )
        scale > 0f
    }
}

/** [tween] when animation is wanted, [snap] when it is not. */
@Composable
fun <T> motionSpec(durationMillis: Int): FiniteAnimationSpec<T> =
    if (LocalAnimationsEnabled.current) tween(durationMillis) else snap()

/**
 * The same scheme, with each role easing toward its new value.
 *
 * Switching skin used to be a single frame in which every colour on screen
 * changed at once, which reads as a glitch rather than as a choice. Tweening
 * the roles turns it into one movement — and because everything downstream
 * reads the scheme rather than the skin, the whole app comes along without
 * knowing anything happened.
 *
 * Only the roles that cover real estate are animated. Tweening all thirty would
 * cost more recompositions than the difference is worth on colours the eye never
 * catches mid-transition.
 */
@Composable
fun ColorScheme.animated(): ColorScheme {
    val spec = motionSpec<Color>(SKIN_TRANSITION_MS)
    val background by animateColorAsState(background, spec, label = "background")
    val surface by animateColorAsState(surface, spec, label = "surface")
    val surfaceVariant by animateColorAsState(surfaceVariant, spec, label = "surfaceVariant")
    val primary by animateColorAsState(primary, spec, label = "primary")
    val onSurface by animateColorAsState(onSurface, spec, label = "onSurface")
    val onSurfaceVariant by animateColorAsState(onSurfaceVariant, spec, label = "onSurfaceVariant")
    val primaryContainer by animateColorAsState(primaryContainer, spec, label = "primaryContainer")
    val onPrimaryContainer by animateColorAsState(
        targetValue = onPrimaryContainer,
        animationSpec = spec,
        label = "onPrimaryContainer"
    )
    val secondaryContainer by animateColorAsState(
        targetValue = secondaryContainer,
        animationSpec = spec,
        label = "secondaryContainer"
    )
    val surfaceContainer by animateColorAsState(surfaceContainer, spec, label = "surfaceContainer")
    val outlineVariant by animateColorAsState(outlineVariant, spec, label = "outlineVariant")

    return copy(
        background = background,
        surface = surface,
        surfaceVariant = surfaceVariant,
        primary = primary,
        onSurface = onSurface,
        onSurfaceVariant = onSurfaceVariant,
        primaryContainer = primaryContainer,
        onPrimaryContainer = onPrimaryContainer,
        secondaryContainer = secondaryContainer,
        surfaceContainer = surfaceContainer,
        outlineVariant = outlineVariant
    )
}
