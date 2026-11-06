package com.gotcha.ui.theme

import androidx.compose.ui.graphics.Color

// Light Theme Colors
val LightPrimary = Color(0xFF006C53)
val LightOnPrimary = Color(0xFFFFFFFF)
val LightPrimaryContainer = Color(0xFFE8F6F2)
val LightOnPrimaryContainer = Color(0xFF002117)
val LightSecondary = Color(0xFF4C635B)
val LightSecondaryContainer = Color(0xFFCEE9DD)
val LightOnSecondaryContainer = Color(0xFF08201A)
val LightSurface = Color(0xFFFBFDF9)
val LightSurfaceVariant = Color(0xFFDBE5E0)
val LightOnSurfaceVariant = Color(0xFF3F4945)

// Dark Theme Colors (Neon / Cyberpunk inspired for modern AI feel)
val CyberCyan = Color(0xFF00E5FF)
val DeepSpace = Color(0xFF0F172A) // Very dark blue/grey
val NeonMagenta = Color(0xFFFF007F)
val DarkSurface = Color(0xFF1E293B)
val DarkSurfaceVariant = Color(0xFF334155)

val DarkPrimary = CyberCyan
val DarkOnPrimary = Color(0xFF00363D)
val DarkPrimaryContainer = Color(0xFF004F58)
val DarkOnPrimaryContainer = Color(0xFFA6FAFF)

val DarkSecondary = NeonMagenta
val DarkSecondaryContainer = Color(0xFF7A003C)
val DarkOnSecondaryContainer = Color(0xFFFFD9E2)

val DarkOnSurfaceVariant = Color(0xFFCBD5E1)

// ---------------------------------------------------------------------------
// Brand ramp
// ---------------------------------------------------------------------------
// Sampled from the launcher icon (res/mipmap-*/ic_launcher_round.png): a
// five-stop ramp over a white field. The icon skins are built out of these
// five and nothing else, which is what keeps the app looking like its own
// icon no matter which theme is on.
//
// Deep Space is the exception and stays cyan — it is what shipped, and it is
// left alone so an update never repaints someone's app without asking. Samosa
// is the other: it follows the company mark (gold on black), not the icon.

val BrandViolet = Color(0xFF7E1F88)
val BrandMagenta = Color(0xFFC82F92)
val BrandRose = Color(0xFFF04B8B)
val BrandCoral = Color(0xFFFC6A85)
val BrandSalmon = Color(0xFFFA8F8A)

/** Magenta darkened until it clears 4.5:1 on Vellum's near-white ground. */
val BrandMagentaInk = Color(0xFFB02A83)

// ---- Aura: greyscale fog, rose as the only chroma on screen ----
val AuraGround = Color(0xFF0A0A0B)
val AuraPanel = Color(0x1FFFFFFF)
val AuraPanelHigh = Color(0x30FFFFFF)
val AuraInk = Color(0xFFF6F6F7)
val AuraInkDim = Color(0xFFA2A2A8)
val AuraOutline = Color(0xFF57575E)
val AuraRoseContainer = Color(0xFF4A0F2C)
val AuraOnRoseContainer = Color(0xFFFFD9E4)

/** Links: pale rose, clears 4.5:1 on the ground, in the user bubble, and on
 * the fog blobs behind prose (checked at full blob strength; blur only helps). */
val AuraLink = Color(0xFFFFD0DC)

// ---- Vellum: the icon's own white field, turned into an interface ----
val VellumGround = Color(0xFFF1EFF2)
val VellumPanel = Color(0x9EFFFFFF)
val VellumPanelHigh = Color(0xC7FFFFFF)
val VellumInk = Color(0xFF17111A)
val VellumInkDim = Color(0xFF6A5F70)
val VellumOutline = Color(0xFF867A88)
val VellumMagentaContainer = Color(0xFFFFD8EC)
val VellumOnMagentaContainer = Color(0xFF3A0028)

/** Links: deep plum. The magenta ink passes on paper but collapses to 2.9:1
 * on the purple fog blob; this clears 4.5:1 on the ground, the bubble and
 * every blob at full strength. */
val VellumLink = Color(0xFF7A1857)

// ---- Orchid: one saturated violet ground, coral for every action ----
val OrchidGround = BrandViolet
val OrchidPanel = Color(0x40FFFFFF)
val OrchidPanelHigh = Color(0x59FFFFFF)
val OrchidInk = Color(0xFFFFFFFF)
val OrchidInkDim = Color(0xFFEBD4EF)
val OrchidOutline = Color(0xFFB878BE)
val OrchidOnCoral = Color(0xFF45061F)
val OrchidCoralContainer = Color(0xFF6E1443)

/** Bubble ink: soft rose instead of pure white — same 8.8:1, less glare. */
val OrchidOnCoralContainer = Color(0xFFFFD9E4)

/** Links: pale peach, clears 4.5:1 on the violet ground and in the bubble. */
val OrchidLink = Color(0xFFFFC9B8)

/** Dialogs and menus: deep enough that coral and the dim ink both clear 4.5:1. */
val OrchidPopup = Color(0xFF4A1052)

// ---- Samosa: the company mark as an interface ----
// Golden dark, not a sixth pink. The Samosa logo is a golden samosa
// (#E5AA36) with a white curve on black, so the skin is warm black,
// golden actions and warm-white ink — nothing shared with the icon ramp.
val SamosaGround = Color(0xFF0D0A05)
val SamosaPanel = Color(0x40FFFFFF)
val SamosaPanelHigh = Color(0x59FFFFFF)
val SamosaInk = Color(0xFFFFF8F0)
val SamosaInkDim = Color(0xFFC9B79A)
val SamosaOutline = Color(0xFF8A7360)
val SamosaGold = Color(0xFFE5AA36)
val SamosaOnGold = Color(0xFF2A1A00)
val SamosaGoldContainer = Color(0xFF4A2C08)
val SamosaOnGoldContainer = Color(0xFFFFF0DC)

/** Links: light gold, clears 4.5:1 on the ground and in the user bubble. */
val SamosaLink = Color(0xFFFFD58A)
val SamosaSecondaryContainer = Color(0xFF3A250E)

// ---------------------------------------------------------------------------
// Container roles
// ---------------------------------------------------------------------------
// Anything that floats *above* the app rather than sitting in it — the
// navigation drawer, dialogs, the long-press menu — is opaque, in every skin.
// Glass is for chrome you look past; a sheet you are reading from needs a
// ground of its own, or the screen behind it reads straight through the text.
//
// The drawer and sheets read the surfaceContainer* roles, so setting them
// opaque here covers those, while `surface` and `surfaceVariant` stay
// translucent for the in-page chrome. Dialogs and menus do NOT: Material 3
// 1.2 paints AlertDialog and DropdownMenu from `surface` (plus a rose tonal
// tint), which is why SkinAlertDialog and SkinDropdownMenu exist. The
// `gotcha` detekt rule set enforces both.

val AuraContainerLowest = Color(0xFF050506)
val AuraContainerLow = Color(0xFF0A0A0B)
val AuraContainer = Color(0xFF141416)
val AuraContainerHigh = Color(0xFF1B1B1E)
val AuraContainerHighest = Color(0xFF232326)

val VellumContainerLowest = Color(0xFFFFFFFF)
val VellumContainerLow = Color(0xFFF7F5F8)
val VellumContainer = Color(0xFFF1EFF2)
val VellumContainerHigh = Color(0xFFEBE7ED)
val VellumContainerHighest = Color(0xFFE4DEE7)

val OrchidContainerLowest = Color(0xFF6A1873)
val OrchidContainerLow = Color(0xFF741B7D)
val OrchidContainer = Color(0xFF7E1F88)
val OrchidContainerHigh = Color(0xFF8A2794)
val OrchidContainerHighest = Color(0xFF96309F)

val SamosaContainerLowest = Color(0xFF0B0805)
val SamosaContainerLow = Color(0xFF120D07)
val SamosaContainer = Color(0xFF1A130A)
val SamosaContainerHigh = Color(0xFF241B0C)
val SamosaContainerHighest = Color(0xFF2E2314)

/** Dialogs and menus: deep warm brown, so gold clears 8:1 on it. */
val SamosaPopup = Color(0xFF1E1206)

/**
 * Error red for the warm skins. The stock M3 error would sit a few degrees from
 * Aura's rose and Samosa's gold, and an error that looks like the send
 * button is not an error — this one is pushed toward orange to stay legible as
 * "something went wrong" next to an accent that is already warm.
 */
val BrandError = Color(0xFFFF6B4A)
val BrandOnError = Color(0xFF3E0A00)

/**
 * "Getting full", for the context meter. Semantic colour is deliberately its own
 * axis: it has to mean the same thing in every skin, so it cannot be the accent
 * — which is a different hue in each of them.
 */
val WarningAmber = Color(0xFFFFB020)
