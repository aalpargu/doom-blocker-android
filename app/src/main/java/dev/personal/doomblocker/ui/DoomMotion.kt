package dev.personal.doomblocker.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin

/**
 * Motion helpers shared by every screen. All finite animations respect the system animator
 * scale; the few looping ones only run while their composable is visible.
 */

/** Cards rise and fade in one after another the first time a screen is shown. */
internal fun Modifier.staggeredEntrance(index: Int, key: Any = Unit): Modifier = composed {
    val progress = remember(key) { Animatable(0f) }
    LaunchedEffect(key) {
        delay((index.coerceAtMost(8) * 45L))
        progress.animateTo(1f, spring(dampingRatio = .82f, stiffness = Spring.StiffnessMediumLow))
    }
    graphicsLayer {
        val p = progress.value
        alpha = p.coerceIn(0f, 1f)
        translationY = (1f - p) * 28.dp.toPx()
        val s = .96f + .04f * p
        scaleX = s
        scaleY = s
    }
}

/** Gentle press feedback for any clickable surface. */
@Composable
internal fun rememberPressScale(interactions: MutableInteractionSource, pressedScale: Float = .97f): Float {
    val pressed by interactions.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) pressedScale else 1f,
        spring(dampingRatio = .55f, stiffness = Spring.StiffnessMedium),
        label = "press-scale",
    )
    return scale
}

/** A status dot whose halo breathes while protection is live. */
@Composable
internal fun PulsingDot(color: Color, active: Boolean, modifier: Modifier = Modifier, size: Dp = 8.dp) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val pulse by transition.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(1600, easing = FastOutSlowInEasing), RepeatMode.Restart),
        label = "pulse-progress",
    )
    Box(modifier.size(size * 2.4f).clearAndSetSemantics { }, contentAlignment = Alignment.Center) {
        if (active) Canvas(Modifier.size(size * 2.4f)) {
            drawCircle(color.copy(alpha = (1f - pulse) * .35f), radius = this.size.minDimension / 2f * (.35f + .65f * pulse))
        }
        Box(Modifier.size(size).background(color, CircleShape))
    }
}

/** Numbers slide vertically when they change, like a mechanical counter. */
@Composable
internal fun RollingText(text: String, style: TextStyle, color: Color, modifier: Modifier = Modifier) {
    Row(modifier) {
        text.forEachIndexed { index, char ->
            AnimatedContent(
                targetState = char,
                transitionSpec = {
                    val up = (targetState.digitToIntOrNull() ?: 0) >= (initialState.digitToIntOrNull() ?: 0)
                    (slideInVertically(tween(260)) { if (up) it else -it } + fadeIn(tween(200))) togetherWith
                        (slideOutVertically(tween(260)) { if (up) -it else it } + fadeOut(tween(160)))
                },
                label = "rolling-$index",
            ) { value -> Text(value.toString(), style = style, color = color) }
        }
    }
}

/**
 * Large progress ring with a soft glow and a bright head. [progress] is the real remaining
 * share (0..1); it sweeps in from zero when first shown.
 */
@Composable
internal fun GlowRing(
    progress: Float,
    color: Color,
    trackColor: Color,
    modifier: Modifier = Modifier,
    strokeWidth: Dp = 12.dp,
) {
    val target = progress.coerceIn(0f, 1f)
    val sweep = remember { Animatable(0f) }
    LaunchedEffect(target) { sweep.animateTo(target, tween(1100, easing = FastOutSlowInEasing)) }
    Canvas(modifier.clearAndSetSemantics { }) {
        val stroke = strokeWidth.toPx()
        val inset = stroke / 2f + 6.dp.toPx()
        val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
        val topLeft = Offset(inset, inset)
        drawArc(trackColor, -90f, 360f, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        val value = sweep.value
        if (value > 0f) {
            drawArc(color.copy(alpha = .22f), -90f, 360f * value, false, topLeft, arcSize,
                style = Stroke(stroke * 2.1f, cap = StrokeCap.Round))
            drawArc(
                Brush.sweepGradient(listOf(color.copy(alpha = .55f), color, color)),
                -90f, 360f * value, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round),
            )
            val angle = Math.toRadians((-90 + 360 * value).toDouble())
            val r = arcSize.width / 2f
            val head = Offset(center.x + cos(angle).toFloat() * r, center.y + sin(angle).toFloat() * r)
            drawCircle(Color.White.copy(alpha = .9f), stroke * .32f, head)
        }
    }
}

/** Soft, slowly drifting colour blobs behind hero content. */
@Composable
internal fun AuroraBackground(colors: List<Color>, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "aurora")
    val drift by transition.animateFloat(
        0f, 1f, infiniteRepeatable(tween(14_000, easing = LinearEasing), RepeatMode.Reverse), label = "aurora-drift",
    )
    Canvas(modifier.clearAndSetSemantics { }) {
        colors.forEachIndexed { index, color ->
            val phase = drift * 2f * Math.PI.toFloat() + index * 2.1f
            val cx = size.width * (.25f + .5f * ((index % 2) + .35f * sin(phase)) / 1.35f)
            val cy = size.height * (.2f + .6f * (index / 2f + .3f * cos(phase)).coerceIn(0f, 1f))
            val radius = size.maxDimension * (.42f + .06f * sin(phase))
            drawCircle(
                Brush.radialGradient(listOf(color, Color.Transparent), Offset(cx, cy), radius),
                radius, Offset(cx, cy),
            )
        }
    }
}

/** Expanding / contracting circle that paces a calm breath: 4 s in, 4 s out. */
@Composable
internal fun BreathingCircle(color: Color, modifier: Modifier = Modifier, onPhase: (Boolean) -> Unit = {}) {
    val transition = rememberInfiniteTransition(label = "breath")
    val breath by transition.animateFloat(
        0f, 1f, infiniteRepeatable(tween(4_000, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breath-scale",
    )
    // Report inhale/exhale on the same 4 s rhythm as the animation (true = inhale).
    LaunchedEffect(Unit) {
        while (true) {
            onPhase(true); delay(4_000)
            onPhase(false); delay(4_000)
        }
    }
    Canvas(modifier.clearAndSetSemantics { }) {
        val base = size.minDimension / 2f
        repeat(3) { ring ->
            val scale = .55f + .45f * breath - ring * .08f
            drawCircle(color.copy(alpha = .10f + ring * .06f), base * scale)
        }
        drawCircle(color.copy(alpha = .55f), base * (.38f + .22f * breath), style = Stroke(2.dp.toPx()))
    }
}

/** Short spring "pop" when a value first becomes true (e.g. a completed step). */
@Composable
internal fun popScale(trigger: Boolean): Float {
    val scale by animateFloatAsState(
        if (trigger) 1f else .85f,
        spring(dampingRatio = .45f, stiffness = Spring.StiffnessMediumLow),
        label = "pop",
    )
    return scale
}
