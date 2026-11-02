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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import com.gotcha.R
import kotlinx.coroutines.delay

/** The footage's own aspect ratio: 1080x1920 portrait. */
private const val VIDEO_ASPECT = 9f / 16f

/** Failsafe: a wedged decoder must never hold the launch hostage. */
private const val SPLASH_TIMEOUT_MS = 12_000L

/** The fade from the video's last frame into the app. */
private const val SPLASH_FADE_MS = 300

/**
 * Whether the overlay may go away. The video is the minimum: it is two seconds
 * long and cannot finish earlier, so a fast device still watches the whole
 * launch. Past it, dismissal waits for the app shell ([contentReady]) — on a
 * slow start the last frame holds as the logo. The timeout releases either
 * wait on its own, so a decoder that never calls back cannot hold the launch
 * hostage.
 */
fun shouldDismissSplash(videoEnded: Boolean, contentReady: Boolean, timedOut: Boolean): Boolean =
    (videoEnded && contentReady) || timedOut

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
        label = "splashFade"
    )
    LaunchedEffect(fading) {
        if (fading) {
            delay(SPLASH_FADE_MS.toLong())
            onFinished()
        }
    }

    // The video's own dark ground, matching the window behind it: the footage
    // around the video must not read as a panel. Clicks are swallowed whole:
    // the app is live underneath, and a tap aimed at the logo must not land
    // on a control nobody can see yet.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colorResource(R.color.splash_background))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {}
            )
            .alpha(alpha)
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            // Scale to fit, never stretch: the footage is 9:16 and phones are
            // taller, so the height binds and matching ground fills the rest.
            // A full-bleed crop would eat the logo on narrow screens.
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
                var player by remember { mutableStateOf<VideoView?>(null) }
                AndroidView(
                    factory = { ctx ->
                        VideoView(ctx).apply {
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
                            player = this
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.Center)
                        .then(videoModifier)
                )
                DisposableEffect(Unit) {
                    onDispose { player?.stopPlayback() }
                }
            }
        }
    }
}
