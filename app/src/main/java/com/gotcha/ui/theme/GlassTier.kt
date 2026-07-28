package com.gotcha.ui.theme

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import java.util.function.Consumer

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
 * Resolves the tier and keeps it current. Two of the inputs flip while the app
 * is running — battery saver, and the system-wide blur toggle in developer
 * options — so both are observed rather than sampled once at startup.
 *
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

    var blurAvailable by remember { mutableStateOf(systemBlurEnabled(context)) }
    DisposableEffect(context) {
        val listener = crossWindowBlurListener(context) { blurAvailable = it }
        onDispose { listener?.invoke() }
    }

    val lowRam = remember(context) {
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).isLowRamDevice
    }

    return when {
        powerSaving -> GlassTier.SOLID
        blurAvailable && !lowRam -> GlassTier.LIVE
        else -> GlassTier.STATIC
    }
}

/** Whether the platform will render blur at all right now. API 31+ only. */
private fun systemBlurEnabled(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
    val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    return wm.isCrossWindowBlurEnabled
}

/**
 * Subscribes to the platform blur toggle, returning the un-subscribe call — or
 * null on Android 11, where the toggle does not exist and the answer can never
 * change.
 */
private fun crossWindowBlurListener(
    context: Context,
    onChange: (Boolean) -> Unit
): (() -> Unit)? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
    val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    val listener = Consumer<Boolean> { enabled -> onChange(enabled) }
    wm.addCrossWindowBlurEnabledListener(listener)
    return { wm.removeCrossWindowBlurEnabledListener(listener) }
}
