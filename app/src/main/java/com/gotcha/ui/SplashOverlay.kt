package com.gotcha.ui

import android.net.Uri
import android.widget.VideoView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import com.gotcha.R
import kotlinx.coroutines.delay

/** The footage's own aspect ratio: 720x1280 portrait. */
private const val VIDEO_ASPECT = 9f / 16f

/**
 * Failsafe for a wedged decoder: prepare plus two seconds of footage should
 * never take this long, and past it something is wrong enough that holding
 * the launch longer only punishes the user.
 */
private const val SPLASH_TIMEOUT_MS = 4_000L

/** The fade from the video's last frame into the app. */
private const val SPLASH_FADE_MS = 300

/**
 * The launch overlay's dismissal rule: the two-second video is the minimum,
 * the app shell gates the rest, and the timeout releases either wait on its
 * own. A decoder failure counts as finished: the fallback logo holds instead
 * of the last frame, under the same rule.
 */
fun shouldDismissSplash(playbackFinished: Boolean, contentReady: Boolean, timedOut: Boolean): Boolean =
    (playbackFinished && contentReady) || timedOut

/**
 * The cold-start launch video, played once per process while the app
 * initializes underneath it.
 *
 * One activity, not two: the content composes behind this overlay from the
 * first frame, so startup work overlaps the video instead of following it,
 * and notifications, the ball and deep links keep landing on the one
 * activity they already address. The overlay is opaque, so nothing behind it
 * needs to hold back — no permission or consent surface can peek through.
 *
 * @param contentReady true once the app shell has drawn its first frame.
 * @param onFinished called after the fade, when the player is released.
 */
@Composable
fun SplashVideoOverlay(
    contentReady: Boolean,
    onFinished: () -> Unit,
    /** Fires once the overlay commits to showing, even if the video restarts. */
    onStarted: () -> Unit = {},
    timeoutMs: Long = SPLASH_TIMEOUT_MS
) {
    var videoEnded by remember { mutableStateOf(false) }
    var videoFailed by remember { mutableStateOf(false) }
    var timedOut by remember { mutableStateOf(false) }
    var fading by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        onStarted()
        delay(timeoutMs)
        timedOut = true
    }
    LaunchedEffect(videoEnded, videoFailed, contentReady, timedOut) {
        if (shouldDismissSplash(videoEnded || videoFailed, contentReady, timedOut)) {
            fading = true
        }
    }
    val alpha by animateFloatAsState(
        targetValue = if (fading) 0f else 1f,
        animationSpec = tween(SPLASH_FADE_MS),
        // With animations disabled the fade finishes at once; the delay below
        // would leave an invisible overlay swallowing taps for 300ms.
        finishedListener = { if (it == 0f) onFinished() },
        label = "splashFade"
    )

    // The footage's own night gradient behind the video: sampled off the first
    // frame, so the fit bars on taller screens melt into the footage instead
    // of reading as a panel. Clicks are swallowed whole: the app is live
    // underneath, and a tap aimed at the logo must not land on a control
    // nobody can see yet.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0f to Color(0xFF081B3A),
                    0.5f to Color(0xFF060F26),
                    1f to Color(0xFF020818)
                )
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {}
            )
            .alpha(alpha)
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            // Scale to fit, never stretch: the footage is 9:16 and phones are
            // taller, so the width binds and matching ground fills the bands
            // above and below. A full-bleed crop would eat the logo on
            // narrow screens.
            val widthToHeight = maxWidth / maxHeight
            val videoModifier = if (widthToHeight < VIDEO_ASPECT) {
                Modifier.fillMaxWidth().aspectRatio(VIDEO_ASPECT)
            } else {
                Modifier.fillMaxHeight().aspectRatio(VIDEO_ASPECT)
            }
            if (videoFailed) {
                Image(
                    painter = painterResource(R.drawable.splash_logo),
                    contentDescription = stringResource(R.string.chat_gotcha_logo),
                    modifier = Modifier
                        .align(Alignment.Center)
                        .then(videoModifier)
                )
            } else {
                AndroidView(
                    factory = { ctx ->
                        VideoView(ctx).apply {
                            // A silent brand moment must not pause the user's
                            // music or podcast: VideoView takes audio focus by
                            // default, even with nothing to play.
                            setAudioFocusRequest(android.media.AudioManager.AUDIOFOCUS_NONE)
                            // Listeners first: a local resource can prepare
                            // before the next line runs.
                            setOnPreparedListener { mp ->
                                mp.setVolume(0f, 0f)
                                start()
                            }
                            setOnCompletionListener {
                                videoEnded = true
                            }
                            setOnErrorListener { _, what, extra ->
                                android.util.Log.w("SplashVideo", "error what=$what extra=$extra")
                                videoFailed = true
                                true
                            }
                            setVideoURI(
                                Uri.parse(
                                    "android.resource://${ctx.packageName}/${R.raw.gotcha_splash}"
                                )
                            )
                        }
                    },
                    onRelease = { player -> player.stopPlayback() },
                    modifier = Modifier
                        .align(Alignment.Center)
                        .then(videoModifier)
                )
            }
        }
    }
}
