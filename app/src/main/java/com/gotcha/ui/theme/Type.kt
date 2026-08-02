package com.gotcha.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.gotcha.R

/** Builds a family from a variable font, pinning the wght axis per weight. */
@OptIn(ExperimentalTextApi::class)
private fun variableFamily(resId: Int) = FontFamily(
    listOf(
        FontWeight.Light,
        FontWeight.Normal,
        FontWeight.Medium,
        FontWeight.SemiBold,
        FontWeight.Bold
    ).map { weight ->
        Font(
            resId = resId,
            weight = weight,
            variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight))
        )
    }
)

/** Figtree — the app-wide typeface (OFL-licensed; see assets/licenses). */
val Figtree = variableFamily(R.font.figtree)

/**
 * Machine text: tool names, exit codes, file paths, terminal output, token
 * counts. Figtree is for what the agent *said*; this is for what it *did*, and
 * the eye separates the two before it has read either.
 *
 * The platform family rather than a bundled face — it is the one typeface every
 * Android device already has a real monospace for, and a second font file is a
 * poor trade for text that is deliberately secondary.
 */
val GotchaMono = FontFamily.Monospace

/**
 * Digits that hold their column. Counters that tick while a reply streams jitter
 * badly in a proportional face, because the glyphs are different widths.
 */
val TabularNumerals = TextStyle(
    fontFamily = GotchaMono,
    fontFeatureSettings = "tnum"
)

private val baseline = Typography()

val Typography = Typography(
    displayLarge = baseline.displayLarge.copy(fontFamily = Figtree),
    displayMedium = baseline.displayMedium.copy(fontFamily = Figtree),
    displaySmall = baseline.displaySmall.copy(fontFamily = Figtree),
    headlineLarge = baseline.headlineLarge.copy(fontFamily = Figtree),
    headlineMedium = TextStyle(
        fontFamily = Figtree,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 36.sp
    ),
    headlineSmall = baseline.headlineSmall.copy(fontFamily = Figtree),
    titleLarge = baseline.titleLarge.copy(fontFamily = Figtree),
    titleMedium = baseline.titleMedium.copy(fontFamily = Figtree),
    titleSmall = baseline.titleSmall.copy(fontFamily = Figtree),
    bodyLarge = TextStyle(
        fontFamily = Figtree,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp
    ),
    bodyMedium = baseline.bodyMedium.copy(fontFamily = Figtree),
    bodySmall = baseline.bodySmall.copy(fontFamily = Figtree),
    labelLarge = baseline.labelLarge.copy(fontFamily = Figtree),
    labelMedium = baseline.labelMedium.copy(fontFamily = Figtree),
    labelSmall = baseline.labelSmall.copy(fontFamily = Figtree)
)
