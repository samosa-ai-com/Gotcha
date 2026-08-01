package com.gotcha.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.gotcha.ui.theme.LocalAnimationsEnabled
import com.gotcha.ui.theme.WarningAmber
import kotlin.math.roundToInt

/**
 * Pieces the chat screen is assembled from. They exist here rather than inline
 * so the same shape can be reused by the drawer and the overlays without three
 * copies drifting apart.
 */

private const val METER_SEGMENTS = 12
private const val METER_WARN_AT = 0.75f
private const val METER_CRITICAL_AT = 0.9f

/**
 * How much of the context window this chat has spent.
 *
 * Twelve segments rather than a continuous bar: a bar answers "roughly how
 * full", which nobody acts on, while segments answer "how many turns do I have
 * left", which is the question actually being asked. Colour carries the same
 * information a second way, for the three-quarters of it that matters.
 */
@Composable
fun ContextMeter(fraction: Float, modifier: Modifier = Modifier) {
    val clamped = fraction.coerceIn(0f, 1f)
    val filled = (clamped * METER_SEGMENTS).roundToInt()
    val colors = MaterialTheme.colorScheme
    val fillColor = when {
        clamped >= METER_CRITICAL_AT -> colors.error
        clamped >= METER_WARN_AT -> WarningAmber
        else -> colors.primary
    }
    val emptyColor = colors.onSurfaceVariant.copy(alpha = 0.22f)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        repeat(METER_SEGMENTS) { index ->
            Box(
                Modifier
                    .weight(1f)
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (index < filled) fillColor else emptyColor)
            )
        }
    }
}

/**
 * A message arriving.
 *
 * It rises a little and fades in on a spring, which is enough to say "this is
 * new" without making the reader wait. Only genuinely new messages animate:
 * anything already on screen when the chat opened appears instantly, and an
 * item that scrolls out of view and back does not replay — a list that
 * re-animates on scroll is a list that feels broken.
 *
 * There is no explicit stagger. A turn that emits a reply and four tool lines
 * emits them milliseconds apart already, so the offset arrives on its own and
 * stays honest about when things actually happened.
 */
@Composable
fun MessageArrival(animate: Boolean, content: @Composable () -> Unit) {
    val enabled = animate && LocalAnimationsEnabled.current
    val progress = remember { Animatable(if (enabled) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (enabled) {
            progress.animateTo(
                targetValue = 1f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioLowBouncy,
                    stiffness = Spring.StiffnessMediumLow
                )
            )
        }
    }
    Box(
        Modifier.graphicsLayer {
            alpha = progress.value
            translationY = (1f - progress.value) * ARRIVAL_RISE.toPx()
        }
    ) {
        content()
    }
}

/** How far a new message travels on its way in. */
private val ARRIVAL_RISE = 14.dp

/**
 * The agent is working. A dot that breathes says so with one moving part, where
 * a spinner says "loading" — a different claim, and the wrong one for something
 * that may be thinking for a minute.
 */
@Composable
fun ActivityPulse(color: Color, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "activity")
    val alpha by transition.animateFloat(
        initialValue = 0.32f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )
    Box(
        modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = alpha))
    )
}
