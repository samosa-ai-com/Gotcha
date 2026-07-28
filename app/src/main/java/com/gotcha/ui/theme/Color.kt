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
// five-stop ramp over a white field. Every skin below Deep Space is built out
// of these five and nothing else, which is what keeps the app looking like its
// own icon no matter which theme is on.
//
// Deep Space is the exception and stays cyan — it is what shipped, and it is
// left alone so an update never repaints someone's app without asking.

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

// ---- Vellum: the icon's own white field, turned into an interface ----
val VellumGround = Color(0xFFF1EFF2)
val VellumPanel = Color(0x9EFFFFFF)
val VellumPanelHigh = Color(0xC7FFFFFF)
val VellumInk = Color(0xFF17111A)
val VellumInkDim = Color(0xFF6A5F70)
val VellumOutline = Color(0xFF867A88)
val VellumMagentaContainer = Color(0xFFFFD8EC)
val VellumOnMagentaContainer = Color(0xFF3A0028)

// ---- Orchid: one saturated violet ground, coral for every action ----
val OrchidGround = BrandViolet
val OrchidPanel = Color(0x40FFFFFF)
val OrchidPanelHigh = Color(0x59FFFFFF)
val OrchidInk = Color(0xFFFFFFFF)
val OrchidInkDim = Color(0xFFEBD4EF)
val OrchidOutline = Color(0xFFB878BE)
val OrchidOnCoral = Color(0xFF45061F)
val OrchidCoralContainer = Color(0xFF6E1443)

// ---- Nocturne: the icon's facets at wallpaper scale, behind dark glass ----
val NocturneGround = Color(0xFF0A050E)
val NocturnePanel = Color(0x8F1A1020)
val NocturnePanelHigh = Color(0x40FFFFFF)
val NocturneInk = Color(0xFFF7EAF1)
val NocturneInkDim = Color(0xFFB69EC0)
val NocturneOutline = Color(0xFF4A3355)
val NocturneOnSalmon = Color(0xFF3D0A16)
val NocturneSalmonContainer = Color(0xFF5C1A2A)
val NocturneMagentaContainer = Color(0xFF3A1140)
val NocturneOnMagentaContainer = Color(0xFFF7D9F2)

// ---------------------------------------------------------------------------
// Container roles
// ---------------------------------------------------------------------------
// Anything that floats *above* the app rather than sitting in it — the
// navigation drawer, dialogs, the long-press menu — is opaque, in every skin.
// Glass is for chrome you look past; a sheet you are reading from needs a
// ground of its own, or the screen behind it reads straight through the text.
//
// Material 3 routes all of those through the surfaceContainer* roles, so
// setting them opaque here fixes the drawer, dialogs and menus in one move,
// while `surface` and `surfaceVariant` stay translucent for the in-page chrome.

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

val NocturneContainerLowest = Color(0xFF07040A)
val NocturneContainerLow = Color(0xFF0D0812)
val NocturneContainer = Color(0xFF171021)
val NocturneContainerHigh = Color(0xFF201729)
val NocturneContainerHighest = Color(0xFF2A1F35)

/**
 * Error red for the pink skins. The stock M3 error would sit a few degrees from
 * Aura's rose and Nocturne's salmon, and an error that looks like the send
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
