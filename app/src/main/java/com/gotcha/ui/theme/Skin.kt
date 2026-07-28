package com.gotcha.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A skin is a whole surface treatment, not just a palette: the colours, what is
 * painted behind the chrome, how translucent that chrome is, and how round it
 * is. Material 3's [ColorScheme] has nowhere to put the last three, so they
 * live here and travel beside it through [LocalSkin].
 *
 * One skin is one look. There is no light/dark axis on top: a theme that has to
 * work in two brightnesses is two designs sharing a name, and the compromise
 * shows in both. Deep Space ships as two entries for exactly that reason.
 */

/** Which way round a skin is, for the system bars and the picker's caption. */
enum class Brightness { LIGHT, DARK }

/** What gets painted behind the translucent chrome. */
enum class Backdrop {
    /** Nothing. The scheme's own background is the whole story. */
    NONE,

    /** Soft diffused blobs — glass without anything to look at underneath. */
    FOG,

    /** Hard-edged shards taken from the launcher icon's geometry. */
    FACETS,

    /** One flat saturated colour, carried by grain rather than depth. */
    FLAT
}

/**
 * @param ground painted first, under [wallpaper]. Also the colour the system
 *   bars sit on, which is why it is a field rather than read back off the scheme.
 * @param wallpaper stops the backdrop painter reads. Empty for [Backdrop.NONE].
 * @param frost blur radius applied to the backdrop when the device can do it
 *   live. Deliberately moderate: blur far enough and the wallpaper stops having
 *   any structure to see through the glass, which is the whole effect.
 * @param grain film-grain opacity over the ground, 0f–1f.
 * @param scrim a veil of [ground] over the wallpaper, 0f–1f, so body text is
 *   never fighting whatever the wallpaper is doing underneath it.
 */
data class Skin(
    val id: String,
    val label: String,
    val tagline: String,
    val brightness: Brightness,
    val backdrop: Backdrop,
    val ground: Color,
    val scheme: ColorScheme,
    val wallpaper: List<Color> = emptyList(),
    val frost: Dp = 0.dp,
    val grain: Float = 0f,
    val scrim: Float = 0f,
    val corner: Dp = 20.dp
) {
    /** True when the chrome is meant to be see-through. */
    val isGlass: Boolean
        get() = backdrop != Backdrop.NONE

    /**
     * True when the system bars sit on a light ground and their icons must be
     * dark. Derived from the skin and never from the device's dark-mode flag:
     * Orchid is a dark violet ground that needs light icons whatever the phone
     * thinks the time of day is.
     */
    val darkSystemBarIcons: Boolean
        get() = brightness == Brightness.LIGHT

    /** What the window is painted before Compose draws anything. */
    val launchGround: Color
        get() = if (isGlass) ground else scheme.background

    /**
     * The same skin with the glass taken out: panels composited down onto their
     * own ground, no wallpaper, no grain. The palette and the layout are
     * untouched — it simply stops being see-through. This is what a device on
     * [GlassTier.SOLID] gets, instead of losing the theme altogether.
     */
    fun opaque(): Skin {
        if (!isGlass) return this
        return copy(
            backdrop = Backdrop.NONE,
            grain = 0f,
            scrim = 0f,
            scheme = scheme.flattenOnto(ground)
        )
    }
}

/**
 * Alpha-composites the translucent roles onto [ground]. The surfaceContainer*
 * roles are already opaque in every skin — see Color.kt — so only the in-page
 * chrome needs flattening here.
 *
 * `background` takes the ground: Scaffold paints its container from that role,
 * and at this tier there is no wallpaper left behind the app for it to hide.
 */
private fun ColorScheme.flattenOnto(ground: Color): ColorScheme = copy(
    background = ground,
    surface = surface.over(ground),
    surfaceVariant = surfaceVariant.over(ground),
    secondaryContainer = secondaryContainer.over(ground)
)

private fun Color.over(background: Color): Color {
    if (alpha >= 1f) return this
    return Color(
        red = red * alpha + background.red * (1f - alpha),
        green = green * alpha + background.green * (1f - alpha),
        blue = blue * alpha + background.blue * (1f - alpha),
        alpha = 1f
    )
}

/** The package-level colour, shadowed inside [Skins] by the skin of the same name. */
private val DeepSpaceGround = DeepSpace

/** The skin in force. Read it for anything [ColorScheme] cannot express. */
val LocalSkin = staticCompositionLocalOf { Skins.DeepSpaceDark }

// ---------------------------------------------------------------------------
// The catalogue
// ---------------------------------------------------------------------------

private val AuraScheme = darkColorScheme(
    primary = BrandRose,
    onPrimary = Color.White,
    primaryContainer = AuraRoseContainer,
    onPrimaryContainer = AuraOnRoseContainer,
    secondary = AuraInkDim,
    onSecondary = AuraGround,
    secondaryContainer = AuraPanelHigh,
    onSecondaryContainer = AuraInk,
    tertiary = BrandCoral,
    background = Color.Transparent,
    onBackground = AuraInk,
    surface = AuraPanel,
    onSurface = AuraInk,
    surfaceVariant = AuraPanelHigh,
    onSurfaceVariant = AuraInkDim,
    surfaceContainer = AuraContainer,
    surfaceContainerHigh = AuraContainerHigh,
    surfaceContainerHighest = AuraContainerHighest,
    surfaceContainerLow = AuraContainerLow,
    surfaceContainerLowest = AuraContainerLowest,
    outline = AuraOutline,
    outlineVariant = AuraOutline,
    error = BrandError,
    onError = BrandOnError
)

private val VellumScheme = lightColorScheme(
    primary = BrandMagentaInk,
    onPrimary = Color.White,
    primaryContainer = VellumMagentaContainer,
    onPrimaryContainer = VellumOnMagentaContainer,
    secondary = VellumInkDim,
    onSecondary = Color.White,
    secondaryContainer = VellumPanelHigh,
    onSecondaryContainer = VellumInk,
    tertiary = BrandViolet,
    background = Color.Transparent,
    onBackground = VellumInk,
    surface = VellumPanel,
    onSurface = VellumInk,
    surfaceVariant = VellumPanelHigh,
    onSurfaceVariant = VellumInkDim,
    surfaceContainer = VellumContainer,
    surfaceContainerHigh = VellumContainerHigh,
    surfaceContainerHighest = VellumContainerHighest,
    surfaceContainerLow = VellumContainerLow,
    surfaceContainerLowest = VellumContainerLowest,
    outline = VellumOutline,
    outlineVariant = VellumOutline
)

private val OrchidScheme = darkColorScheme(
    primary = BrandCoral,
    onPrimary = OrchidOnCoral,
    primaryContainer = OrchidCoralContainer,
    onPrimaryContainer = Color.White,
    secondary = OrchidInkDim,
    onSecondary = OrchidGround,
    secondaryContainer = OrchidPanelHigh,
    onSecondaryContainer = OrchidInk,
    tertiary = BrandSalmon,
    background = Color.Transparent,
    onBackground = OrchidInk,
    surface = OrchidPanel,
    onSurface = OrchidInk,
    surfaceVariant = OrchidPanelHigh,
    onSurfaceVariant = OrchidInkDim,
    surfaceContainer = OrchidContainer,
    surfaceContainerHigh = OrchidContainerHigh,
    surfaceContainerHighest = OrchidContainerHighest,
    surfaceContainerLow = OrchidContainerLow,
    surfaceContainerLowest = OrchidContainerLowest,
    outline = OrchidOutline,
    outlineVariant = OrchidOutline,
    error = BrandError,
    onError = BrandOnError
)

private val NocturneScheme = darkColorScheme(
    primary = BrandSalmon,
    onPrimary = NocturneOnSalmon,
    primaryContainer = NocturneSalmonContainer,
    onPrimaryContainer = Color.White,
    secondary = BrandMagenta,
    onSecondary = Color.White,
    secondaryContainer = NocturneMagentaContainer,
    onSecondaryContainer = NocturneOnMagentaContainer,
    tertiary = BrandViolet,
    background = Color.Transparent,
    onBackground = NocturneInk,
    surface = NocturnePanel,
    onSurface = NocturneInk,
    surfaceVariant = NocturnePanelHigh,
    onSurfaceVariant = NocturneInkDim,
    surfaceContainer = NocturneContainer,
    surfaceContainerHigh = NocturneContainerHigh,
    surfaceContainerHighest = NocturneContainerHighest,
    surfaceContainerLow = NocturneContainerLow,
    surfaceContainerLowest = NocturneContainerLowest,
    outline = NocturneOutline,
    outlineVariant = NocturneOutline,
    error = BrandError,
    onError = BrandOnError
)

/** Every skin the picker offers, in the order it offers them. */
object Skins {

    /**
     * Greyscale fog with the icon's rose as the only chroma on screen — send,
     * active mode, recording. Everything else is grey, which is exactly what
     * makes the pink land.
     */
    val Aura = Skin(
        id = "aura",
        label = "Aura",
        tagline = "Frosted dark. Grey fog behind glass, one rose accent.",
        brightness = Brightness.DARK,
        backdrop = Backdrop.FOG,
        ground = AuraGround,
        scheme = AuraScheme,
        wallpaper = listOf(
            Color(0x59FFFFFF),
            Color(0x33FFFFFF),
            Color(0x4DFFFFFF),
            Color(0x26FFFFFF)
        ),
        frost = 20.dp,
        grain = 0.06f,
        scrim = 0.05f,
        corner = 22.dp
    )

    /** The icon's white field as an interface. The one skin that survives sun. */
    val Vellum = Skin(
        id = "vellum",
        label = "Vellum",
        tagline = "Frosted light. Paper ground, rose haze, magenta ink.",
        brightness = Brightness.LIGHT,
        backdrop = Backdrop.FOG,
        ground = VellumGround,
        scheme = VellumScheme,
        wallpaper = listOf(
            Color(0xFFFFFFFF),
            Color(0x80DB63B8),
            Color(0xCCFFFFFF),
            Color(0x668F3AB2)
        ),
        frost = 18.dp,
        grain = 0.04f,
        scrim = 0.05f,
        corner = 22.dp
    )

    /**
     * The icon's deepest violet carrying the whole screen, grain over it, and
     * coral — the ramp's warmest stop — for every action. Two colours that
     * already sit next to each other in the icon, at maximum volume.
     */
    val Orchid = Skin(
        id = "orchid",
        label = "Orchid",
        tagline = "Tinted. Violet ground, coral actions, fine grain.",
        brightness = Brightness.DARK,
        backdrop = Backdrop.FLAT,
        ground = OrchidGround,
        scheme = OrchidScheme,
        frost = 8.dp,
        // Measured at roughly twice the other skins' grain rather than six times
        // it. Same cell size everywhere — amplitude is what reads as coarseness,
        // and the vignette carries the depth that the grain used to carry alone.
        grain = 0.12f,
        corner = 22.dp
    )

    /** The launcher icon's low-poly geometry, blown up to wallpaper scale. */
    val Nocturne = Skin(
        id = "nocturne",
        label = "Nocturne",
        tagline = "Faceted dark. The icon's shards read through black glass.",
        brightness = Brightness.DARK,
        backdrop = Backdrop.FACETS,
        ground = NocturneGround,
        scheme = NocturneScheme,
        wallpaper = listOf(BrandViolet, BrandMagenta, BrandRose, BrandCoral, BrandSalmon),
        frost = 18.dp,
        grain = 0.06f,
        scrim = 0.10f,
        corner = 20.dp
    )

    /**
     * What shipped, untouched: opaque slate, cyan primary, Material 3
     * elevation. Two entries rather than one that follows the system, so the
     * picker shows what you will get instead of what you might get.
     *
     * Dark is the default, and the fallback whenever glass is unavailable.
     */
    val DeepSpaceDark = Skin(
        id = "deepspace",
        label = "Deep Space Dark",
        tagline = "The original. Opaque slate, cyan, no wallpaper.",
        brightness = Brightness.DARK,
        backdrop = Backdrop.NONE,
        ground = DeepSpaceGround,
        scheme = DarkColorScheme
    )

    val DeepSpaceLight = Skin(
        id = "deepspace_light",
        label = "Deep Space Light",
        tagline = "The original, in daylight. Opaque, green, no wallpaper.",
        brightness = Brightness.LIGHT,
        backdrop = Backdrop.NONE,
        ground = LightSurface,
        scheme = LightColorScheme
    )

    val all = listOf(Aura, Vellum, Orchid, Nocturne, DeepSpaceDark, DeepSpaceLight)

    const val DEFAULT_ID = "deepspace"

    fun byId(id: String): Skin = all.firstOrNull { it.id == id } ?: DeepSpaceDark
}
