package com.gotcha.ui.tour

import android.content.Context
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.gotcha.ui.openSpecialAccess
import com.gotcha.ui.theme.GotchaMono
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import android.provider.Settings as AndroidSettings

/** Runs a coach card's primary action — always a trip to a system settings screen. */
private fun openTourAction(context: Context, action: TourAction) {
    openSpecialAccess(context, action.marker, context.packageName)
}

/** Gap between the spotlit control and the card explaining it. */
private val CARD_GAP = 12.dp

/** How far the cut-out is inflated beyond the control itself. */
private val HOLE_PADDING = 6.dp

/**
 * Grace period before deciding a [TourStep.requiresAnchor] control is really absent.
 * Long enough to cover a settings-page scroll plus a layout pass on a mid-range device.
 */
private const val ANCHOR_GRACE_MS = 1200L

/**
 * The coach layer: dims the app, cuts one control back out of the dimming, and
 * explains why it matters.
 *
 * Composed above the whole navigation host rather than inside any screen, so the
 * screens themselves stay unaware they are being toured — all they contribute is
 * a [tourAnchor] on the control worth pointing at.
 */
@Composable
fun TourOverlay(
    controller: TourController,
    modifier: Modifier = Modifier
) {
    val step = controller.current
    if (!controller.isShowing || step == null) return

    val anchors = LocalTourAnchors.current
    val hole = step.anchor?.let { anchors[it] }

    // A branch this user isn't on, most likely: the Samosa sign-in button, for
    // someone who chose their own API key. The step is about to skip itself, so
    // show nothing in the meantime — a card that appears and then vanishes on
    // its own reads as a glitch, and the user cannot tell whether they missed
    // something. If the control does turn up inside the grace period the card
    // appears normally, because this is read from live anchor state.
    val awaitingAbsentAnchor = step.requiresAnchor && hole == null

    // The control this step is about is often below the fold — a Save button at
    // the foot of a long page. Scroll it up before asking the user to press it.
    LaunchedEffect(step.id, step.anchor) {
        step.anchor?.let { anchors.bringIntoView(it) }
    }

    // Wait a beat for the page to finish laying out, and for the scroll above to
    // land, before concluding the control is genuinely not there.
    LaunchedEffect(step.id) {
        if (step.requiresAnchor) {
            delay(ANCHOR_GRACE_MS)
            if (anchors[step.anchor ?: return@LaunchedEffect] == null) controller.skipMissingAnchor()
        }
    }

    if (awaitingAbsentAnchor) return

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val holePadPx = with(density) { HOLE_PADDING.toPx() }
        val spotlight = hole?.inflate(holePadPx)

        val containerWidth = with(density) { maxWidth.toPx() }
        val containerHeightPx = with(density) { maxHeight.toPx() }
        Scrim(
            spotlight = spotlight,
            containerSize = Size(containerWidth, containerHeightPx)
        )

        if (spotlight != null) Pulse(spotlight)

        var cardHeight by remember(step.id) { mutableIntStateOf(0) }
        val containerHeight = with(density) { maxHeight.toPx() }
        val gapPx = with(density) { CARD_GAP.toPx() }

        val cardY = when {
            spotlight == null -> (containerHeight - cardHeight) / 2f
            // Below the control when there is room, above it when there isn't —
            // a card that covers the button it is describing is worse than useless.
            spotlight.bottom + gapPx + cardHeight <= containerHeight -> spotlight.bottom + gapPx
            else -> spotlight.top - gapPx - cardHeight
        }.coerceIn(0f, (containerHeight - cardHeight).coerceAtLeast(0f))

        CoachCard(
            controller = controller,
            step = step,
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset { IntOffset(0, cardY.toInt()) }
                .onSizeChanged { cardHeight = it.height }
        )
    }
}

/**
 * The dimming, with [spotlight] punched out of it.
 *
 * Painting and touch-blocking are deliberately two different things here. A
 * single full-screen node that swallowed touches outside the hole and declined
 * to consume them inside it *looks* like it would work, and does not: Compose
 * stops hit-testing at the topmost node covering a point, so the control under
 * the cut-out never enters the hit path and consumption never gets a say.
 *
 * So the paint is one node that takes no input at all, and the blocking is four
 * bands around the hole. The hole itself is covered by nothing, which is what
 * lets the user press the real button — the entire point of the coach layer.
 */
@Composable
private fun Scrim(spotlight: Rect?, containerSize: Size) {
    val scrimColor = MaterialTheme.colorScheme.scrim.copy(alpha = 0.82f)
    val radius = with(LocalDensity.current) { 14.dp.toPx() }

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            // BlendMode.Clear needs a layer of its own to erase into; without it
            // the cut-out is drawn against the window and does nothing visible.
            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
            // The scrim is decoration. Screen readers should hear the card.
            .clearAndSetSemantics { }
            .testTag("tour_scrim")
    ) {
        drawRect(scrimColor)
        if (spotlight != null) {
            drawRoundRect(
                color = Color.Transparent,
                topLeft = spotlight.topLeft,
                size = spotlight.size,
                cornerRadius = CornerRadius(radius),
                blendMode = BlendMode.Clear
            )
        }
    }

    val blocked = if (spotlight == null) {
        listOf(Rect(Offset.Zero, containerSize))
    } else {
        listOf(
            Rect(0f, 0f, containerSize.width, spotlight.top),
            Rect(0f, spotlight.bottom, containerSize.width, containerSize.height),
            Rect(0f, spotlight.top, spotlight.left, spotlight.bottom),
            Rect(spotlight.right, spotlight.top, containerSize.width, spotlight.bottom)
        )
    }
    blocked.forEach { TouchBlocker(it) }
}

/** Swallows every touch over [rect] so only the spotlit control stays live. */
@Composable
private fun TouchBlocker(rect: Rect) {
    val density = LocalDensity.current
    // A hole against an edge leaves a band with no area; asking for a negative
    // size is a crash, and drawing nothing is the correct answer anyway.
    val width = with(density) { rect.width.coerceAtLeast(0f).toDp() }
    val height = with(density) { rect.height.coerceAtLeast(0f).toDp() }
    Box(
        modifier = Modifier
            .offset { IntOffset(rect.left.roundToInt(), rect.top.roundToInt()) }
            .size(width, height)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial)
                            .changes.forEach { it.consume() }
                    }
                }
            }
    )
}

/** A ring that breathes outward from the spotlit control, drawing the eye to it. */
@Composable
private fun Pulse(spotlight: Rect) {
    val context = LocalContext.current
    // Honour the system's "remove animations" setting: a looping pulse is exactly
    // the kind of motion people turn that off to be rid of.
    val animated = remember {
        AndroidSettings.Global.getFloat(
            context.contentResolver,
            AndroidSettings.Global.ANIMATOR_DURATION_SCALE,
            1f
        ) != 0f
    }
    val accent = MaterialTheme.colorScheme.primary
    val radius = with(LocalDensity.current) { 14.dp.toPx() }
    val strokeWidth = with(LocalDensity.current) { 2.dp.toPx() }

    val progress = if (animated) {
        val transition = rememberInfiniteTransition(label = "tour_pulse")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1800, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "tour_pulse_progress"
        ).value
    } else {
        0f
    }

    val spread = with(LocalDensity.current) { 10.dp.toPx() } * progress
    Canvas(modifier = Modifier.fillMaxSize().clearAndSetSemantics { }) {
        drawRoundRect(
            color = accent.copy(alpha = if (animated) (1f - progress) * 0.9f else 0.9f),
            topLeft = Offset(spotlight.left - spread, spotlight.top - spread),
            size = Size(spotlight.width + spread * 2, spotlight.height + spread * 2),
            cornerRadius = CornerRadius(radius + spread),
            style = Stroke(width = strokeWidth)
        )
    }
}

/** The instruction itself: what to press, why, and the two ways out. */
@Composable
private fun CoachCard(
    controller: TourController,
    step: TourStep,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 6.dp,
        shadowElevation = 12.dp,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            // Announced when it appears, so the tour is followable without sight
            // of the spotlight that is doing the pointing.
            .semantics { liveRegion = LiveRegionMode.Polite }
            .testTag("tour_card")
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "STEP ${controller.stepNumber} OF ${controller.stepCount}",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = GotchaMono,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = step.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = step.body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            step.hint?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // The way out of the app, for the steps whose toggle lives in
            // Android's own settings. Telling someone where to go is not the
            // same as taking them, and the path is three screens deep on some
            // phones — see the hint above, which is only there because of that.
            step.action?.let { action ->
                val context = LocalContext.current
                Button(
                    onClick = { openTourAction(context, action) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                        .testTag("tour_action")
                ) { Text(action.label) }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = { controller.cancel() },
                    modifier = Modifier.testTag("tour_skip_all")
                ) { Text("Skip tour") }
                if (step.ackLabel != null) {
                    TextButton(
                        onClick = { controller.acknowledge() },
                        modifier = Modifier.testTag("tour_ack")
                    ) { Text(step.ackLabel) }
                } else {
                    Text(
                        text = "Waiting for you…",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 12.dp)
                    )
                }
            }
        }
    }
}
