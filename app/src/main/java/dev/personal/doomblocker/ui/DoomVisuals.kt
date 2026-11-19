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

/**
 * The Today hero: a living gradient card with the real remaining daily balance as a glowing ring.
 * [remainingMillis] / [progress] come straight from the usage engine, nothing is decorative data.
 */
@Composable
internal fun TimeBalanceCard(
    remainingMillis: Long,
    used: String,
    appCount: Int,
    unavailable: Int,
    progress: Float,
    greeting: String,
    usingNow: String?,
) {
    val accent = MaterialTheme.colorScheme.primary
    val ink = lerp(accent, Color.Black, .78f)
    val inkLight = lerp(accent, Color.Black, .55f)
    val mint = lerp(accent, Color.White, .78f)
    val ringColor = when {
        progress <= 0f -> Color(0xFFFF8A80)
        progress < .2f -> Color(0xFFFFC86A)
        else -> mint
    }
    val totalSeconds = (remainingMillis / 1_000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val bigText = if (hours > 0) "%d:%02d".format(hours, minutes) else "%d".format(minutes)
    val unit = if (hours > 0) "saat" else "dk"
    Surface(shape = RoundedCornerShape(DoomTokens.radiusXl), color = ink, contentColor = Color.White, shadowElevation = 10.dp) {
        Box(Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(ink, inkLight)))) {
            AuroraBackground(
                listOf(accent.copy(alpha = .55f), Color(0xFF7FD6FF).copy(alpha = .18f), mint.copy(alpha = .22f)),
                Modifier.matchParentSize(),
            )
            Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(greeting, color = Color.White.copy(alpha = .82f), style = MaterialTheme.typography.titleMedium)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("BUGÜN KALAN HAKKIN", color = mint, style = MaterialTheme.typography.labelSmall, letterSpacing = 1.8.sp)
                        Row(verticalAlignment = Alignment.Bottom) {
                            RollingText(bigText, MaterialTheme.typography.displaySmall.copy(fontSize = 52.sp, lineHeight = 56.sp,
                                fontWeight = FontWeight.Bold), Color.White)
                            Text(" $unit", Modifier.padding(bottom = 8.dp), color = Color.White.copy(alpha = .75f),
                                style = MaterialTheme.typography.titleMedium)
                        }
                        Text(if (progress <= 0f) "Bugünkü hakkın doldu. Yarın yenilenir."
                            else "korunan uygulamalar için günlük toplam",
                            color = Color.White.copy(alpha = .72f), style = MaterialTheme.typography.bodySmall)
                    }
                    Box(Modifier.size(112.dp), contentAlignment = Alignment.Center) {
                        GlowRing(progress, ringColor, Color.White.copy(alpha = .12f), Modifier.fillMaxSize(), 10.dp)
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("%${(progress * 100).toInt().coerceIn(0, 100)}", style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold)
                            Text("kaldı", color = Color.White.copy(alpha = .7f), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                usingNow?.let {
                    Surface(color = Color.White.copy(alpha = .12f), shape = RoundedCornerShape(14.dp)) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PulsingDot(ringColor, active = true, size = 7.dp)
                            Text(it, color = Color.White, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HeroStat("Uygulama", appCount.toString(), Modifier.weight(1f))
                    HeroStat("Kullanılan", used, Modifier.weight(1f))
                    HeroStat("Kısıtlı", unavailable.toString(), Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun HeroStat(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(modifier, color = Color.White.copy(alpha = .09f), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(label, color = Color.White.copy(alpha = .68f), style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    }
}

@Composable
internal fun PrimaryFocusButton(label: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interactions = remember { MutableInteractionSource() }
    val scale = rememberPressScale(interactions, .96f)
    val accent = MaterialTheme.colorScheme.primary
    val gradient = Brush.horizontalGradient(listOf(accent, lerp(accent, Color(0xFF00B4D8), .35f)))
    val buttonModifier = modifier.heightIn(min = 56.dp).graphicsLayer { scaleX = scale; scaleY = scale }
    Button(onClick, buttonModifier, enabled = enabled, interactionSource = interactions, shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, disabledContainerColor = Color.Transparent),
        contentPadding = PaddingValues(0.dp)) {
        Box(
            Modifier.fillMaxWidth().heightIn(min = 56.dp)
                .background(if (enabled) gradient else Brush.horizontalGradient(listOf(
                    MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.colorScheme.surfaceContainerHigh)),
                    RoundedCornerShape(18.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                color = if (enabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
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
