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

/** DM Sans — headlines and titles (greeting, drawer/app-bar titles). */
val DmSans = variableFamily(R.font.dm_sans)

/** Inter — body text and labels. */
val Inter = variableFamily(R.font.inter)

private val baseline = Typography()

val Typography = Typography(
    displayLarge = baseline.displayLarge.copy(fontFamily = DmSans),
    displayMedium = baseline.displayMedium.copy(fontFamily = DmSans),
    displaySmall = baseline.displaySmall.copy(fontFamily = DmSans),
    headlineLarge = baseline.headlineLarge.copy(fontFamily = DmSans),
    headlineMedium = TextStyle(
        fontFamily = DmSans,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 36.sp
    ),
    headlineSmall = baseline.headlineSmall.copy(fontFamily = DmSans),
    titleLarge = baseline.titleLarge.copy(fontFamily = DmSans),
    titleMedium = baseline.titleMedium.copy(fontFamily = DmSans),
    titleSmall = baseline.titleSmall.copy(fontFamily = DmSans),
    bodyLarge = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp
    ),
    bodyMedium = baseline.bodyMedium.copy(fontFamily = Inter),
    bodySmall = baseline.bodySmall.copy(fontFamily = Inter),
    labelLarge = baseline.labelLarge.copy(fontFamily = Inter),
    labelMedium = baseline.labelMedium.copy(fontFamily = Inter),
    labelSmall = baseline.labelSmall.copy(fontFamily = Inter)
)
