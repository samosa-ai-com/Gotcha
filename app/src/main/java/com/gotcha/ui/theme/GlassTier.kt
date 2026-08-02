package com.gotcha.ui.theme

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * How much of the glass this device can actually make.
 *
 * Themes are never disabled per device — a locked tile is a dead end the user
 * cannot clear, and it turns "which theme do you have" into a support matrix.
 * Every skin renders everywhere; what changes is the material.
 */
enum class GlassTier {
    /** Live blur. The backdrop is blurred on the GPU as the user moves through it. */
    LIVE,

    /**
     * No live blur, so the softness has to be baked in. The wallpapers are our
     * own assets and are already diffuse, so the panels stay translucent and
     * only a facet field loses its haze.
     */
    STATIC,

    /** Nothing at all: opaque panels and no wallpaper. The cheapest we render. */
    SOLID
}

/** The tier in force, for anything that needs to branch on it. */
val LocalGlassTier = staticCompositionLocalOf { GlassTier.STATIC }

/**
 * Resolves the tier and keeps it current. Battery saver flips while the app is
 * running, so it is observed rather than sampled once at startup.
 *
 * The blur test is deliberately just the API level. This used to ask
 * `WindowManager.isCrossWindowBlurEnabled()`, which is a different capability:
 * it reports whether a *window* may blur what is behind it — the
 * `setBackgroundBlurRadius` path — and OEMs routinely ship with it off. Our
 * backdrop uses `Modifier.blur`, a RenderEffect inside our own window, which
 * works on any hardware-accelerated device from API 31 regardless. The wrong
 * question told modern phones they could not do something they do fine.
 */
@Composable
fun rememberGlassTier(): GlassTier {
    val context = LocalContext.current
    val powerManager = remember(context) {
        context.getSystemService(Context.POWER_SERVICE) as PowerManager
    }

    var powerSaving by remember { mutableStateOf(powerManager.isPowerSaveMode) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                powerSaving = powerManager.isPowerSaveMode
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }

    val canBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    val lowRam = remember(context) {
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).isLowRamDevice
    }

    return when {
        powerSaving -> GlassTier.SOLID
        canBlur && !lowRam -> GlassTier.LIVE
        else -> GlassTier.STATIC
    }
}
