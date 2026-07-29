package com.gotcha.ui.theme

import android.content.Context
import android.graphics.Typeface
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.res.ResourcesCompat
import com.gotcha.R

/**
 * The active skin, in the form View code can use.
 *
 * The overlays are `LinearLayout`/`TextView`/`GradientDrawable` rather than
 * Compose, so none of them can read [LocalSkin]. They were therefore carrying
 * their own hardcoded look — two dozen loose radii and text sizes, a `#1E1E1E`
 * panel, a `Color.CYAN` stroke left over from when Deep Space was the only
 * theme. This is the one place that translates a [Skin] for them.
 *
 * Always built from [Skin.opaque]. An overlay floats over somebody else's app,
 * not over our wallpaper: there is nothing of ours behind it to show through,
 * and translucent chrome over an unknown screen is unreadable. Depth here comes
 * from an edge highlight and a shadow, never from transparency.
 */
data class OverlaySkin(
    val surface: Int,
    val onSurface: Int,
    val onSurfaceVariant: Int,
    val outline: Int,
    val buttonBg: Int,
    val buttonText: Int,
    val accent: Int,
    val onAccent: Int,
    val error: Int,
    /** Radius for cards and menus, in dp, from [Skin.corner]. */
    val cardRadiusDp: Float,
    /** Radius for buttons and rows, in dp, from [Skin.cornerSmall]. */
    val buttonRadiusDp: Float,
    val sans: Typeface,
    val mono: Typeface,
    val titleSp: Float,
    val bodySp: Float,
    val labelSp: Float
)

/**
 * Figtree, cached. Loaded once because an overlay can be rebuilt many times in a
 * session and font lookup is not free.
 *
 * The fallback is deliberate: an overlay that fails to draw is worse than one
 * drawn in the platform face, and this code runs over other people's apps where
 * there is no screen of ours left to report an error on.
 */
private var figtreeCache: Typeface? = null

private fun figtree(context: Context): Typeface {
    figtreeCache?.let { return it }
    val loaded = runCatching {
        ResourcesCompat.getFont(context.applicationContext, R.font.figtree)
    }.getOrNull() ?: Typeface.DEFAULT
    figtreeCache = loaded
    return loaded
}

/**
 * Builds the overlay tokens for [skinId].
 *
 * Type sizes come off the app's own [Typography] rather than being chosen here,
 * so the overlays cannot drift onto a scale of their own.
 */
fun overlaySkin(context: Context, skinId: String): OverlaySkin {
    val skin = Skins.byId(skinId).opaque()
    val scheme = skin.scheme
    return OverlaySkin(
        surface = scheme.surface.toArgb(),
        onSurface = scheme.onSurface.toArgb(),
        onSurfaceVariant = scheme.onSurfaceVariant.toArgb(),
        outline = scheme.outline.toArgb(),
        buttonBg = scheme.secondaryContainer.toArgb(),
        buttonText = scheme.onSecondaryContainer.toArgb(),
        accent = scheme.primary.toArgb(),
        onAccent = scheme.onPrimary.toArgb(),
        error = scheme.error.toArgb(),
        cardRadiusDp = skin.corner.value,
        buttonRadiusDp = skin.cornerSmall.value,
        sans = figtree(context),
        mono = Typeface.MONOSPACE,
        titleSp = Typography.titleMedium.fontSize.value,
        bodySp = Typography.bodyMedium.fontSize.value,
        labelSp = Typography.labelMedium.fontSize.value
    )
}
