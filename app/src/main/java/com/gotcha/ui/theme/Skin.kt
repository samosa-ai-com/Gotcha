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
 */

/** How a skin answers the device's light/dark setting. */
enum class Brightness {
    /** Carries both palettes and switches between them. Deep Space only. */
    ADAPTIVE,

    /** A daylight skin. Pairs with a dark one via [Skin.pairedWith]. */
    LIGHT,

    /** A night skin. Pairs with a light one via [Skin.pairedWith]. */
    DARK,

    /**
     * Reads the same at noon and midnight. A saturated ground has no honest
     * darker twin — dimming it for night mode only makes it muddy.
     */
    FIXED
}

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
 *   live. `0.dp` means the skin never frosts.
 * @param grain film-grain opacity over the ground, 0f–1f.
 * @param darkSystemBarIcons true when the bars sit on a light ground and their
 *   icons must be dark. Derived from the skin and never from the system's
 *   dark-mode flag: Orchid is a dark violet ground that needs light icons even
 *   when the phone is in light mode, and getting this from `isSystemInDarkTheme`
 *   is how the clock disappears.
 * @param pairedWith the skin to swap to when "Match system light & dark" is on.
 */
data class Skin(
    val id: String,
    val label: String,
    val tagline: String,
    val brightness: Brightness,
    val backdrop: Backdrop,
    val ground: Color,
    private val schemeLight: ColorScheme,
    private val schemeDark: ColorScheme,
    val wallpaper: List<Color> = emptyList(),
    val frost: Dp = 0.dp,
    val grain: Float = 0f,
    /**
     * A veil of [ground] laid over the wallpaper, 0f–1f. Glass buys depth at the
     * cost of contrast: body text ends up sitting on whatever the wallpaper is
     * doing underneath it. The veil pulls the wallpaper's range in so the text
     * always wins, and the louder the wallpaper the more of it there is.
     */
    val scrim: Float = 0f,
    val corner: Dp = 20.dp,
    val darkSystemBarIcons: Boolean = false,
    val pairedWith: String? = null
) {
    /** [dark] only changes anything for [Brightness.ADAPTIVE] skins. */
    fun scheme(dark: Boolean): ColorScheme = if (dark) schemeDark else schemeLight

    /** True when this skin has something to say about light and dark at all. */
    val followsSystem: Boolean
        get() = brightness == Brightness.ADAPTIVE || pairedWith != null

    /** True when the chrome is meant to be see-through. */
    val isGlass: Boolean
        get() = backdrop != Backdrop.NONE

    /**
     * What the window should be painted before Compose draws anything. A glass
     * skin has a ground of its own; an opaque one is whatever its scheme says
     * the background is, which depends on [dark].
     */
    fun launchGround(dark: Boolean): Color =
        if (isGlass) ground else scheme(dark).background

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
            schemeLight = schemeLight.flattenOnto(ground),
            schemeDark = schemeDark.flattenOnto(ground)
        )
    }
}

/**
 * Alpha-composites the translucent roles onto [ground]. The surfaceContainer*
 * roles are already opaque in every skin — see Color.kt — so only the in-page
 * chrome needs flattening here.
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
val LocalSkin = staticCompositionLocalOf { Skins.DeepSpace }

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
     * What shipped, untouched: opaque slate, cyan primary, Material 3
     * elevation. It stays the default so an update never surprises anyone, and
     * it is the fallback whenever glass is unavailable.
     */
    val DeepSpace = Skin(
        id = "deepspace",
        label = "Deep Space",
        tagline = "The original. Opaque, cyan, no wallpaper.",
        brightness = Brightness.ADAPTIVE,
        backdrop = Backdrop.NONE,
        ground = DeepSpaceGround,
        schemeLight = LightColorScheme,
        schemeDark = DarkColorScheme
    )

    /**
     * Greyscale fog with the icon's rose as the only chroma on screen — send,
     * active mode, recording. Everything else is grey, which is exactly what
     * makes the pink land.
     */
    val Aura = Skin(
        id = "aura",
        label = "Aura",
        tagline = "Frosted dark. Grey fog, one rose accent.",
        brightness = Brightness.DARK,
        backdrop = Backdrop.FOG,
        ground = AuraGround,
        schemeLight = AuraScheme,
        schemeDark = AuraScheme,
        wallpaper = listOf(
            Color(0x24FFFFFF),
            Color(0x14FFFFFF),
            Color(0x1AFFFFFF),
            Color(0x0FFFFFFF)
        ),
        frost = 26.dp,
        grain = 0.05f,
        scrim = 0.10f,
        corner = 22.dp,
        pairedWith = "vellum"
    )

    /** The icon's white field as an interface. The one skin that survives sun. */
    val Vellum = Skin(
        id = "vellum",
        label = "Vellum",
        tagline = "Frosted light. Paper ground, magenta ink.",
        brightness = Brightness.LIGHT,
        backdrop = Backdrop.FOG,
        ground = VellumGround,
        schemeLight = VellumScheme,
        schemeDark = VellumScheme,
        wallpaper = listOf(
            Color(0xF2FFFFFF),
            Color(0x52DB63B8),
            Color(0xCCFFFFFF),
            Color(0x3D8F3AB2)
        ),
        frost = 22.dp,
        grain = 0.035f,
        scrim = 0.12f,
        corner = 22.dp,
        darkSystemBarIcons = true,
        pairedWith = "aura"
    )

    /**
     * The icon's deepest violet carrying the whole screen, grain over it, and
     * coral — the ramp's warmest stop — for every action. Two colours that
     * already sit next to each other in the icon, at maximum volume.
     */
    val Orchid = Skin(
        id = "orchid",
        label = "Orchid",
        tagline = "Tinted. Violet ground, coral actions, real grain.",
        brightness = Brightness.FIXED,
        backdrop = Backdrop.FLAT,
        ground = OrchidGround,
        schemeLight = OrchidScheme,
        schemeDark = OrchidScheme,
        frost = 8.dp,
        grain = 0.24f,
        corner = 22.dp
    )

    /** The launcher icon's low-poly geometry, blown up to wallpaper scale. */
    val Nocturne = Skin(
        id = "nocturne",
        label = "Nocturne",
        tagline = "Faceted dark. The icon's shards behind black glass.",
        brightness = Brightness.DARK,
        backdrop = Backdrop.FACETS,
        ground = NocturneGround,
        schemeLight = NocturneScheme,
        schemeDark = NocturneScheme,
        wallpaper = listOf(BrandViolet, BrandMagenta, BrandRose, BrandCoral, BrandSalmon),
        frost = 34.dp,
        grain = 0.05f,
        // The facets are the loudest thing any skin puts behind text.
        scrim = 0.30f,
        corner = 20.dp
    )

    /**
     * Material You. Present as a real choice rather than as the silent default
     * it used to be: [GotchaTheme] defaulted to `dynamicColor = true`, so the
     * user's wallpaper has been overriding every palette here since API 31 —
     * which is every device, given minSdk 30. Picking any other skin now turns
     * it off, and picking this one turns it back on.
     *
     * The schemes below are only reached on Android 11, where there is no
     * dynamic colour to read.
     */
    val System = Skin(
        id = "system",
        label = "System",
        tagline = "Material You. Colours follow your wallpaper.",
        brightness = Brightness.ADAPTIVE,
        backdrop = Backdrop.NONE,
        ground = DeepSpaceGround,
        schemeLight = LightColorScheme,
        schemeDark = DarkColorScheme
    )

    val all = listOf(DeepSpace, Aura, Vellum, Orchid, Nocturne, System)

    const val DEFAULT_ID = "deepspace"

    fun byId(id: String): Skin = all.firstOrNull { it.id == id } ?: DeepSpace

    /**
     * The skin actually painted, once "Match system light & dark" has had its
     * say. A paired skin hands over to its twin rather than inventing a washed
     * out light version of itself.
     */
    fun resolve(id: String, matchSystem: Boolean, dark: Boolean): Skin {
        val chosen = byId(id)
        if (!matchSystem) return chosen
        val twin = chosen.pairedWith?.let(::byId) ?: return chosen
        val wrongWayRound = (chosen.brightness == Brightness.DARK && !dark) ||
            (chosen.brightness == Brightness.LIGHT && dark)
        return if (wrongWayRound) twin else chosen
    }
}
