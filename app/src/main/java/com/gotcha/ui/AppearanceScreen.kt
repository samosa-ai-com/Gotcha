package com.gotcha.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gotcha.data.Settings
import com.gotcha.data.ThemeMode
import com.gotcha.ui.theme.Brightness
import com.gotcha.ui.theme.GlassTier
import com.gotcha.ui.theme.GotchaMono
import com.gotcha.ui.theme.LocalGlassTier
import com.gotcha.ui.theme.Skin
import com.gotcha.ui.theme.SkinMiniature
import com.gotcha.ui.theme.Skins
import kotlin.math.roundToInt

/**
 * The Appearance page: light or dark, which skin, and how much glass.
 *
 * Every control here applies the moment it is touched — a theme you have to
 * save is a theme you cannot judge. That is also why this page exists at all:
 * Appearance used to be one segmented control on the settings home, where a
 * page of its own would have been emptier than the row that opened it. A skin
 * picker changes that arithmetic.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceScreen(
    load: () -> Settings,
    onSave: ((Settings) -> Settings) -> Unit,
    onBack: () -> Unit,
    /** Hands the applied settings straight to the activity, which repaints. */
    onApply: (Settings) -> Unit
) {
    val initial = remember { load() }
    var themeMode by remember { mutableStateOf(initial.themeMode) }
    var skinId by remember { mutableStateOf(initial.skinId) }
    var matchSystem by remember { mutableStateOf(initial.matchSystemBrightness) }
    var reduceTransparency by remember { mutableStateOf(initial.reduceTransparency) }
    var frostPercent by remember { mutableIntStateOf(initial.frostPercent) }

    val overlay = rememberSettingsOverlayState()
    val tier = LocalGlassTier.current
    val selected = Skins.byId(skinId)

    // One writer for every control on the page: persist, then repaint from storage
    // so the activity and the preferences can never disagree about what is on.
    fun apply(mutate: (Settings) -> Settings) {
        onSave(mutate)
        onApply(load())
    }

    SettingsScaffold(title = SettingsPage.APPEARANCE.title, onBack = onBack, overlay = overlay) {
        Text("Light and dark", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            ThemeMode.entries.forEachIndexed { index, mode ->
                SegmentedButton(
                    selected = themeMode == mode,
                    onClick = {
                        themeMode = mode
                        apply { it.copy(themeMode = mode) }
                    },
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = ThemeMode.entries.size
                    )
                ) { Text(mode.label) }
            }
        }

        HorizontalDivider(thickness = 1.dp)

        Text("Theme", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            "Every theme works on every device. Where live blur isn't available, " +
                "the glass is made a cheaper way rather than switched off.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        SkinGrid(
            selectedId = skinId,
            onSelect = { picked ->
                skinId = picked
                apply { it.copy(skinId = picked) }
            }
        )
        Text(
            selected.tagline,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        HorizontalDivider(thickness = 1.dp)

        val pairedId = selected.pairedWith
        if (pairedId != null) {
            val twin = Skins.byId(pairedId)
            SettingsToggleRow(
                label = "Match system light & dark",
                checked = matchSystem,
                onCheckedChange = {
                    matchSystem = it
                    apply { s -> s.copy(matchSystemBrightness = it) }
                },
                isLarge = true,
                switchTestTag = "appearance_match_system"
            )
            Text(
                "${selected.label} and ${twin.label} are the same theme in two " +
                    "brightnesses. With this on, the phone picks whichever one fits.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (selected.isGlass && !reduceTransparency) {
            Text(
                "Frost",
                style = MaterialTheme.typography.bodyLarge
            )
            Slider(
                value = frostPercent.toFloat(),
                onValueChange = { frostPercent = it.roundToInt() },
                // Persist on release rather than per pixel: the preview follows the
                // thumb, but a hundred writes per drag is a hundred writes.
                onValueChangeFinished = { apply { s -> s.copy(frostPercent = frostPercent) } },
                valueRange = 0f..100f,
                modifier = Modifier.testTag("appearance_frost")
            )
            Text(
                "$frostPercent% of ${selected.label}'s frost",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = GotchaMono,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        SettingsToggleRow(
            label = "Reduce transparency",
            checked = reduceTransparency,
            onCheckedChange = {
                reduceTransparency = it
                apply { s -> s.copy(reduceTransparency = it) }
            },
            isLarge = true,
            switchTestTag = "appearance_reduce_transparency"
        )
        Text(
            text = tierExplanation(tier, reduceTransparency),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Says what this device is actually doing, rather than what the theme wishes it did. */
private fun tierExplanation(tier: GlassTier, reduceTransparency: Boolean): String = when {
    reduceTransparency -> "Panels are solid and no wallpaper is drawn. Colours are unchanged."
    tier == GlassTier.LIVE -> "Panels blur what's behind them."
    tier == GlassTier.SOLID -> "Battery saver is on, so panels are solid until it's off."
    else -> "Live blur isn't available on this device, so panels use a fixed frost."
}

private const val TILES_PER_ROW = 2

@Composable
private fun SkinGrid(selectedId: String, onSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Skins.all.chunked(TILES_PER_ROW).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { skin ->
                    SkinTile(
                        skin = skin,
                        selected = skin.id == selectedId,
                        onSelect = { onSelect(skin.id) }
                    )
                }
                // Keeps the last row's single tile at tile width instead of full width.
                repeat(TILES_PER_ROW - row.size) { Box(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun RowScope.SkinTile(skin: Skin, selected: Boolean, onSelect: () -> Unit) {
    val border = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }
    Column(
        modifier = Modifier
            .weight(1f)
            .clip(RoundedCornerShape(16.dp))
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = border,
                shape = RoundedCornerShape(16.dp)
            )
            .selectable(selected = selected, onClick = onSelect)
            .testTag("appearance_skin_${skin.id}")
    ) {
        SkinPreview(skin)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                skin.label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
            )
            Text(
                brightnessLabel(skin),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun brightnessLabel(skin: Skin): String = when (skin.brightness) {
    Brightness.ADAPTIVE -> if (skin.id == Skins.System.id) "wallpaper" else "auto"
    Brightness.LIGHT -> "light"
    Brightness.DARK -> "dark"
    Brightness.FIXED -> "fixed"
}

/**
 * A miniature of the chat screen in the skin's own tokens: the wallpaper it
 * paints, a panel at the translucency it uses, two lines of thread, and the
 * accent as the send button. Enough to tell them apart at a glance, which is
 * all a tile has to do.
 */
@Composable
private fun SkinPreview(skin: Skin) {
    val scheme = skin.scheme(dark = skin.brightness != Brightness.LIGHT)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(84.dp)
            .background(if (skin.isGlass) Color.Transparent else scheme.background)
    ) {
        SkinMiniature(skin, Modifier.matchParentSize())
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(14.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(scheme.surfaceVariant)
            )
            Box(
                Modifier
                    .fillMaxWidth(0.62f)
                    .height(9.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(scheme.onSurfaceVariant.copy(alpha = 0.45f))
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                Box(
                    Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(scheme.primary)
                )
            }
        }
    }
}
