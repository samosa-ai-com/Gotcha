package com.gotcha.ui.theme

import android.graphics.Bitmap
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.random.Random

/**
 * Everything painted behind the app's translucent chrome.
 *
 * The wallpapers are ours rather than the user's, which is the whole reason the
 * glass is affordable: a diffuse gradient needs no blur to look blurred, so a
 * device that cannot blur live still gets the frosted look. Only [Backdrop.FACETS]
 * — hard edges by design — actually reads differently between tiers.
 *
 * Nothing here animates. A drifting wallpaper redraws the full screen forever,
 * which is a real battery cost for an effect the user stops noticing in a day.
 */
@Composable
fun SkinBackdrop(modifier: Modifier = Modifier) {
    val skin = LocalSkin.current
    val tier = LocalGlassTier.current
    // An opaque skin paints its own background through the colour scheme; drawing
    // a second ground under it would only cost a full-screen fill. Whether the
    // skin still has a wallpaper at this tier was already decided in GotchaTheme.
    if (!skin.isGlass) return

    // Crossfaded rather than colour-tweened: fog cannot become facets by
    // interpolation, so the two wallpapers are drawn at once and dissolved.
    Crossfade(
        targetState = skin,
        animationSpec = motionSpec(SKIN_TRANSITION_MS),
        label = "backdrop",
        modifier = modifier
    ) { shown ->
        Box(Modifier.fillMaxSize().background(shown.ground)) {
            Wallpaper(shown, live = tier == GlassTier.LIVE)
            if (shown.grain > 0f) Grain(shown.grain)
            Scrim(shown)
        }
    }
}

@Composable
private fun Wallpaper(skin: Skin, live: Boolean) {
    val canvas = Modifier
        .fillMaxSize()
        .let { if (live && skin.frost > 0.dp) it.blur(skin.frost) else it }

    when (skin.backdrop) {
        Backdrop.FOG -> Canvas(canvas) { drawFog(skin.wallpaper) }
        Backdrop.FACETS -> Canvas(canvas) { drawFacets(skin.wallpaper) }
        Backdrop.FLAT -> Canvas(canvas) { drawTint() }
        Backdrop.NONE -> Unit
    }
}

/**
 * The same wallpaper at tile size, for the theme picker. It shares the painters
 * with the real thing rather than shipping screenshots, so a preview cannot go
 * stale the first time a colour moves.
 */
@Composable
fun SkinMiniature(skin: Skin, modifier: Modifier = Modifier) {
    // Shown as it will actually render on this device and at these settings. A
    // preview that keeps its wallpaper while the app has dropped it is a picker
    // promising something the app then does not do.
    val shown = if (LocalGlassTier.current == GlassTier.SOLID) skin.opaque() else skin
    Box(modifier.background(shown.ground)) {
        Wallpaper(shown, live = false)
        if (shown.grain > 0f) Grain(shown.grain)
        Scrim(shown)
    }
}

/**
 * The contrast veil. Sits above the wallpaper and below every piece of UI, so
 * text is always reading against a wallpaper whose range has been pulled in
 * rather than against whatever the gradient happened to be doing there.
 */
@Composable
private fun Scrim(skin: Skin) {
    if (skin.scrim <= 0f) return
    Box(
        Modifier
            .fillMaxSize()
            .background(skin.ground.copy(alpha = skin.scrim))
    )
}

/**
 * A tinted ground with somewhere for the light to come from.
 *
 * One flat fill is not a tint, it is a wash — which is exactly what it looked
 * like. The hue is untouched; a wide highlight off the top-left and a vignette
 * into the corners give it a direction and an edge, and the grain on top gives
 * it a surface.
 */
private fun DrawScope.drawTint() {
    drawRect(
        brush = Brush.radialGradient(
            colors = listOf(Color.White.copy(alpha = 0.22f), Color.Transparent),
            center = Offset(size.width * 0.16f, size.height * 0.08f),
            radius = maxOf(size.width, size.height)
        )
    )
    drawRect(
        brush = Brush.radialGradient(
            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.38f)),
            center = size.center,
            radius = hypot(size.width, size.height) * 0.60f
        )
    )
}

/** Where the fog sits, in fractions of the screen, and how far each one reaches. */
private val FogSpots = listOf(
    Offset(0.22f, 0.14f) to 0.78f,
    Offset(0.84f, 0.30f) to 0.66f,
    Offset(0.36f, 0.76f) to 0.86f,
    Offset(0.90f, 0.94f) to 0.60f
)

private fun DrawScope.drawFog(stops: List<Color>) {
    if (stops.isEmpty()) return
    FogSpots.forEachIndexed { index, spot ->
        val (relative, reach) = spot
        val center = Offset(size.width * relative.x, size.height * relative.y)
        val radius = size.minDimension * reach
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(stops[index % stops.size], Color.Transparent),
                center = center,
                radius = radius
            ),
            radius = radius,
            center = center
        )
    }
}

/**
 * The launcher icon's low-poly geometry at wallpaper scale. Vertices are in
 * fractions of the screen so the field re-cuts itself for any aspect ratio
 * instead of stretching.
 */
private val Facets = listOf(
    listOf(0f to 0f, 0.62f to 0f, 0.28f to 0.44f, 0f to 0.30f),
    listOf(0.62f to 0f, 1f to 0f, 1f to 0.34f, 0.28f to 0.44f),
    listOf(0f to 0.30f, 0.28f to 0.44f, 0.16f to 0.78f, 0f to 0.66f),
    listOf(0.28f to 0.44f, 1f to 0.34f, 0.78f to 0.72f, 0.16f to 0.78f),
    listOf(0f to 0.66f, 0.16f to 0.78f, 0.42f to 1f, 0f to 1f),
    listOf(0.16f to 0.78f, 0.78f to 0.72f, 1f to 1f, 0.42f to 1f),
    listOf(0.78f to 0.72f, 1f to 0.34f, 1f to 1f)
)

/**
 * Per-facet opacity, ordered against the colour each facet draws.
 *
 * The ramp's warm end is far lighter than its violet end, so painting coral and
 * salmon at the same strength as violet put shards on screen that were brighter
 * than the text over them — measured 1.53:1, which is not readable by any
 * standard. The light stops are held well back so every shard stays a dark
 * ground for type; the geometry reads from hue, not from brightness.
 */
private val FacetAlpha = listOf(0.95f, 0.75f, 0.50f, 0.35f, 0.30f, 0.65f, 0.55f)

private fun DrawScope.drawFacets(stops: List<Color>) {
    if (stops.isEmpty()) return
    Facets.forEachIndexed { index, vertices ->
        val path = Path().apply {
            vertices.forEachIndexed { corner, (x, y) ->
                val point = Offset(size.width * x, size.height * y)
                if (corner == 0) moveTo(point.x, point.y) else lineTo(point.x, point.y)
            }
            close()
        }
        drawPath(
            path = path,
            color = stops[index % stops.size],
            alpha = FacetAlpha[index % FacetAlpha.size]
        )
    }
}

/**
 * Film grain, tiled from one small bitmap and blended in Overlay so mid-grey is
 * a no-op — only the deviation shows, which is what keeps a flat ground from
 * looking like an empty `<div>`.
 */
/**
 * Film grain.
 *
 * Two things were wrong with the first version. It was drawn at one device
 * pixel per cell, which on a 3x screen averages out to nothing before it
 * reaches the eye; and it was opaque mid-grey relying on an Overlay blend,
 * which — where the blend is not honoured — lays a grey wash over the ground
 * and desaturates it. Orchid's violet measured (126,31,136) and rendered
 * (126,63,133): the same hue with the life drained out of it.
 *
 * So: the deviation lives in the alpha channel, which composites correctly
 * everywhere, and each cell is scaled up to [GRAIN_CELL_PX] so there is
 * something to see. White is capped lower than black because lifting a
 * saturated colour toward white costs more saturation than dropping it
 * toward black, which scales all three channels evenly and holds the hue.
 */
@Composable
private fun Grain(opacity: Float) {
    val noise = remember { noiseTile() }
    val brush = remember(noise) {
        ShaderBrush(ImageShader(noise, TileMode.Repeated, TileMode.Repeated))
    }
    Canvas(Modifier.fillMaxSize()) {
        scale(GRAIN_CELL_PX, GRAIN_CELL_PX, pivot = Offset.Zero) {
            drawRect(brush = brush, alpha = opacity)
        }
    }
}

private const val NOISE_TILE_PX = 128

/** Device pixels per grain cell. Below about 3 the texture stops being visible. */
private const val GRAIN_CELL_PX = 4f

/** Ceilings on how far one cell may push the ground, out of 255. */
private const val NOISE_WHITE_MAX = 110
private const val NOISE_BLACK_MAX = 200

/** Fixed seed: the grain is part of the design, so it should not re-roll per launch. */
private const val NOISE_SEED = 0x6074CA

private fun noiseTile(): ImageBitmap {
    val random = Random(NOISE_SEED)
    val pixels = IntArray(NOISE_TILE_PX * NOISE_TILE_PX) {
        val deviation = random.nextInt(-100, 101)
        val lifts = deviation >= 0
        val ceiling = if (lifts) NOISE_WHITE_MAX else NOISE_BLACK_MAX
        val alpha = abs(deviation) * ceiling / 100
        val tone = if (lifts) 0xFF else 0x00
        (alpha shl 24) or (tone shl 16) or (tone shl 8) or tone
    }
    return Bitmap.createBitmap(pixels, NOISE_TILE_PX, NOISE_TILE_PX, Bitmap.Config.ARGB_8888)
        .asImageBitmap()
}
