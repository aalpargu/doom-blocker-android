package dev.personal.doomblocker.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin

/** Finite motion follows Android's animator duration scale; no perpetual redraw loop. */
@Composable
internal fun rememberPageEntrance(key: Any): Animatable<Float, *> {
    val entrance = remember(key) { Animatable(0f) }
    LaunchedEffect(key) {
        entrance.snapTo(0f)
        entrance.animateTo(1f, tween(360))
    }
    return entrance
}

/** A quiet orbital dial. Its sweep represents a real remaining quota when supplied. */
@Composable
internal fun FocusOrbit(
    modifier: Modifier = Modifier,
    progress: Float = 1f,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appeared = true }
    val sweep by animateFloatAsState(if (appeared) progress.coerceIn(0f, 1f) else 0f, tween(850), label = "orbit-reveal")
    Canvas(modifier.clearAndSetSemantics { }) {
        val r = size.minDimension * .39f
        drawCircle(color.copy(alpha = .06f), radius = r * 1.26f)
        drawCircle(color.copy(alpha = .16f), radius = r * 1.25f, style = Stroke(1.dp.toPx()))
        drawCircle(color.copy(alpha = .12f), radius = r, style = Stroke(5.dp.toPx()))
        drawArc(color, -90f, 360f * sweep, false,
            Offset(center.x - r, center.y - r), Size(r * 2, r * 2),
            style = Stroke(5.dp.toPx(), cap = StrokeCap.Round))
        repeat(12) { i ->
            val angle = i * Math.PI / 6
            drawCircle(color.copy(alpha = .4f), 1.dp.toPx(),
                Offset(center.x + cos(angle).toFloat() * r * .77f, center.y + sin(angle).toFloat() * r * .77f))
        }
        // Clock hands anchor the illustration to time, without suggesting a second counter.
        drawLine(color, center, Offset(center.x, center.y - r * .46f), 3.dp.toPx(), StrokeCap.Round)
        drawLine(color, center, Offset(center.x + r * .31f, center.y + r * .12f), 3.dp.toPx(), StrokeCap.Round)
        drawCircle(color, 3.dp.toPx())
        val endAngle = Math.toRadians((-90 + sweep * 360).toDouble())
        if (sweep > 0f) drawCircle(color, 4.dp.toPx(),
            Offset(center.x + cos(endAngle).toFloat() * r, center.y + sin(endAngle).toFloat() * r))
    }
}

@Composable
internal fun TimeBalanceCard(remaining: String, used: String, appCount: Int, unavailable: Int, progress: Float) {
    val accent = MaterialTheme.colorScheme.primary
    val ink = lerp(accent, Color.Black, .73f)
    val mint = lerp(accent, Color.White, .76f)
    Surface(shape = RoundedCornerShape(DoomTokens.radiusXl), color = ink, contentColor = Color.White) {
        Box(Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(ink, lerp(accent, Color.Black, .60f))))) {
            // Decorative contour lines remain behind the content and carry no semantics.
            Canvas(Modifier.matchParentSize().clearAndSetSemantics { }) {
                repeat(4) { i ->
                    drawCircle(mint.copy(alpha = .05f), size.width * (.38f + i * .11f),
                        Offset(size.width * 1.06f, size.height * 1.3f), style = Stroke(1.dp.toPx()))
                }
            }
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("BUGÜN KALAN HAKKIN", color = mint, style = MaterialTheme.typography.labelSmall, letterSpacing = 1.8.sp)
                        Text(remaining, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                        Text("korunan uygulamalar için günlük toplam", color = Color.White.copy(alpha = .76f), style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.width(8.dp))
                    FocusOrbit(Modifier.size(82.dp), progress, mint)
                }
                HorizontalDivider(color = Color.White.copy(alpha = .13f))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("$appCount uygulama · $used kullanıldı", Modifier.weight(1f), color = Color.White.copy(alpha = .85f), style = MaterialTheme.typography.labelSmall)
                    Text(if (unavailable == 0) "Sınırlar hazır" else "$unavailable kısıtlı", color = mint, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
internal fun PrimaryFocusButton(label: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && enabled) .98f else 1f, spring(dampingRatio = .8f, stiffness = 600f), label = "focus-press")
    val buttonModifier = modifier.heightIn(min = 52.dp).graphicsLayer { scaleX = scale; scaleY = scale }
    Button(onClick, buttonModifier, enabled = enabled, interactionSource = interactions, shape = RoundedCornerShape(16.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
        Text(label, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
internal fun SetupProgress(completed: Int, total: Int = 4) {
    val progress by animateFloatAsState(completed.toFloat() / total, tween(400), label = "setup-progress")
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Sana özel, dikkat dağıtmayan bir alan.", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("$completed / $total", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(5.dp),
            color = MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            drawStopIndicator = {})
    }
}
