package com.gotcha.ui.theme

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import com.gotcha.R

/** How far the top edge is lifted off a dark surface. */
private const val EDGE_LIGHTEN = 0.14f

/**
 * How far it is sunk into a light one. Lower than it looks like it should be:
 * on a near-white panel a little ink goes a very long way, and 22% — the
 * mirror of the dark figure — reads as a second border rather than an edge.
 */
private const val EDGE_DARKEN = 0.12f

private const val SHADOW_ALPHA_DARK = 0x66
private const val SHADOW_ALPHA_LIGHT = 0x4D
private const val SHADOW_RADIUS_DP = 12f

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
    /**
     * What the skin paints under everything else. The ball is drawn on this
     * rather than on [surface]: it is not a panel, it is the app itself made
     * small, so it wears the app's floor.
     */
    val ground: Int,
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
    /**
     * The lit top edge of a raised surface, already composited onto [surface].
     *
     * Lighter than the surface on a dark skin, darker on a light one. That
     * asymmetry is the point: a white hairline on Vellum's near-white panel is
     * invisible, so what sells the raise there is a fine dark rule against the
     * app underneath, the same way a printed card sits on paper.
     */
    val edgeHighlight: Int,
    /** Colour of the drop shadow, alpha included. */
    val shadowColor: Int,
    /** How far the shadow spreads beyond the card, in dp. */
    val shadowRadiusDp: Float,
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
    val surface = scheme.surface.toArgb()
    val dark = skin.brightness == Brightness.DARK
    return OverlaySkin(
        surface = surface,
        ground = scheme.background.toArgb(),
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
        edgeHighlight = ColorUtils.blendARGB(
            surface,
            if (dark) Color.WHITE else Color.BLACK,
            if (dark) EDGE_LIGHTEN else EDGE_DARKEN
        ),
        // Deeper under a light skin, where there is less contrast between the
        // card and a bright host app to do the separating on its own.
        shadowColor = ColorUtils.setAlphaComponent(
            Color.BLACK,
            if (dark) SHADOW_ALPHA_DARK else SHADOW_ALPHA_LIGHT
        ),
        shadowRadiusDp = SHADOW_RADIUS_DP,
        sans = figtree(context),
        mono = Typeface.MONOSPACE,
        titleSp = Typography.titleMedium.fontSize.value,
        bodySp = Typography.bodyMedium.fontSize.value,
        labelSp = Typography.labelMedium.fontSize.value
    )
}
