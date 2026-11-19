package dev.personal.doomblocker.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.personal.doomblocker.BlockerApplication
import dev.personal.doomblocker.core.UsageEngine
import dev.personal.doomblocker.platform.BreakSuggestion
import dev.personal.doomblocker.platform.BreakSuggestions
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Informational only: no counter resets, bypasses, or automatic target launches. */
class CooldownActivity : ComponentActivity() {
    private var targetPackage by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        targetPackage = intent.getStringExtra(EXTRA_PACKAGE).orEmpty()
        if (targetPackage.isBlank()) { finish(); return }
        enableEdgeToEdge()
        setContent {
            val blocker = application as BlockerApplication
            val preferences by blocker.uiPreferences.state.collectAsStateWithLifecycle()
            val protection by blocker.repository.state.collectAsStateWithLifecycle()
            val runtime by blocker.serviceStatus.state.collectAsStateWithLifecycle()
            var now by remember { mutableStateOf(blocker.clock.now()) }
            LaunchedEffect(Unit) {
                lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                    while (true) { now = blocker.clock.now(); delay(1_000) }
                }
            }
            val snapshot = protection.snapshot
            val target = snapshot?.apps?.firstOrNull { it.packageName == targetPackage }
            val view = if (snapshot != null && target != null) UsageEngine.view(snapshot, target, now) else null
            val remaining = view?.cooldownRemainingMillis ?: 0L
            val endTime = if (snapshot != null && remaining > 0) {
                Instant.ofEpochMilli(UsageEngine.trustedWall(snapshot, now) + remaining)
                    .atZone(ZoneId.of(snapshot.quotaZoneId)).format(DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault()))
            } else null
            val warning = when {
                protection.error != null -> "Mola bilgisi okunamadı. Koruma durumunu kontrol et."
                !runtime.connected -> "Koruma servisi bağlı değil. Devam etmeden önce korumayı kontrol et."
                runtime.problem != null -> runtime.problem
                else -> null
            }
            DoomBlockerTheme(preferences.amoledBlack, preferences.accent) {
                BackHandler { goHome() }
                CooldownScreen(
                    label = target?.label ?: "Uygulama",
                    remaining = remaining,
                    total = target?.cooldown?.durationMillis ?: remaining,
                    endTime = endTime,
                    ready = protection.ready && target != null,
                    completion = view?.let { afterCooldownMessage(it.availability) }
                        ?: if (protection.ready) "Bu uygulamanın güncel kaydı bulunamadı." else "Güncel mola bilgisi yükleniyor.",
                    warning = warning,
                    suggestion = target?.cooldown?.let {
                        BreakSuggestions.forCooldown(target.packageName, it.startedAt.wallMillis)
                    },
                    onHome = ::goHome,
                    onDetails = {
                        startActivity(Intent(this, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
                        finish()
                    },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        targetPackage = intent.getStringExtra(EXTRA_PACKAGE).orEmpty()
        if (targetPackage.isBlank()) finish()
    }

    private fun goHome() {
        runCatching { startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        finish()
    }

    companion object { const val EXTRA_PACKAGE = "cooldown_package" }
}

@Composable
private fun CooldownScreen(
    label: String, remaining: Long, total: Long, endTime: String?, ready: Boolean,
    completion: String, warning: String?, suggestion: BreakSuggestion?,
    onHome: () -> Unit, onDetails: () -> Unit,
) {
    val resting = ready && remaining > 0
    val accent by animateColorAsState(
        if (resting) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary,
        tween(350), label = "break-color")
    val progress by animateFloatAsState(
        if (resting && total > 0) (1f - remaining.toFloat() / total).coerceIn(0f, 1f) else if (ready) 1f else 0f,
        tween(450), label = "break-progress")
    val seconds = ((remaining + 999) / 1_000).coerceAtLeast(0)
    val countdown = String.format(Locale.getDefault(), "%02d:%02d", seconds / 60, seconds % 60)
    var inhale by remember { mutableStateOf(true) }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize()) {
            AuroraBackground(
                listOf(accent.copy(alpha = .22f), MaterialTheme.colorScheme.tertiary.copy(alpha = .10f), accent.copy(alpha = .12f)),
                Modifier.matchParentSize(),
            )
            Column(
                Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Surface(shape = RoundedCornerShape(50), color = accent.copy(alpha = .12f),
                    modifier = Modifier.staggeredEntrance(0, label)) {
                    Text(label, Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                        color = accent, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                }
                Column(Modifier.staggeredEntrance(1, label), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(if (!ready) "Mola bilgisi" else if (resting) "Şimdi mola zamanı" else "Mola tamamlandı",
                        style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    Text(if (resting) "Oturum sınırına ulaştın. Gözlerini ekrandan al, birlikte nefes alalım." else completion,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
                // Breathing guide: the circle grows for 4 s (inhale) and shrinks for 4 s (exhale).
                Box(Modifier.size(260.dp).staggeredEntrance(2, label), contentAlignment = Alignment.Center) {
                    if (resting) BreathingCircle(accent, Modifier.fillMaxSize()) { inhale = it }
                    Box(Modifier.size(196.dp), contentAlignment = Alignment.Center) {
                        GlowRing(progress, accent, accent.copy(alpha = .12f), Modifier.fillMaxSize(), 8.dp)
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            if (resting) {
                                RollingText(countdown, MaterialTheme.typography.displaySmall.copy(
                                    fontSize = 44.sp, fontWeight = FontWeight.Bold), accent)
                                AnimatedContent(inhale, label = "breath-label") { inhaling ->
                                    Text(if (inhaling) "nefes al…" else "nefes ver…",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.titleSmall)
                                }
                            } else {
                                Text(if (ready) "✓" else "…", color = accent, fontSize = 48.sp, fontWeight = FontWeight.Bold)
                                Text(if (ready) "Molan bitti" else "Kontrol ediliyor…", color = accent,
                                    style = MaterialTheme.typography.titleMedium)
                            }
                        }
                    }
                }
                if (resting) endTime?.let {
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = .85f), shape = RoundedCornerShape(50)) {
                        Text("Mola bitişi · $it", Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    }
                }
                if (resting && suggestion != null) {
                    Surface(
                        modifier = Modifier.fillMaxWidth().staggeredEntrance(3, label),
                        shape = RoundedCornerShape(24.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                    ) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("BU MOLADA", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
                                letterSpacing = 1.5.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            Text(suggestion.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer)
                            Text(suggestion.message, style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    }
                }
                Text(if (resting) "Ekranı kapatabilirsin; mola devam eder. Tekrar denemek molayı uzatmaz. Mola bitince diğer sınırların geçerli olmaya devam eder."
                    else "Uygulama kendiliğinden açılmaz.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                warning?.let {
                    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(16.dp)) {
                        Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
                PrimaryFocusButton("Ana ekrana dön", true, onHome, Modifier.fillMaxWidth())
                TextButton(onClick = onDetails) { Text("Koruma durumunu gör") }
            }
        }
    }
}
