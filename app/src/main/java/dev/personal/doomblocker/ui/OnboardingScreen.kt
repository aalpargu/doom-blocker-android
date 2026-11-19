package dev.personal.doomblocker.ui

import android.Manifest
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.personal.doomblocker.platform.DeviceAccess
import dev.personal.doomblocker.service.LimitNotificationManager
import kotlinx.coroutines.launch

/**
 * First-run walkthrough: what the app does, then each permission on its own page with the
 * reason and a button that opens the right settings screen. Each permission page shows a live
 * check mark when Android reports it as granted.
 */
@Composable
internal fun OnboardingScreen(onFinish: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    var permissions by remember { mutableStateOf(DeviceAccess.permissions(context)) }
    var notifications by remember { mutableStateOf(LimitNotificationManager.hasPermission(context)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permissions = DeviceAccess.permissions(context)
                notifications = LimitNotificationManager.hasPermission(context)
            }
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        LimitNotificationManager.setEnabled(context, granted)
        notifications = granted
    }
    fun open(intent: Intent) = runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }

    val pager = rememberPagerState { 4 }
    val scope = rememberCoroutineScope()
    val accent = MaterialTheme.colorScheme.primary
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize()) {
            AuroraBackground(listOf(accent.copy(alpha = .20f), Color(0xFF7FD6FF).copy(alpha = .10f)), Modifier.matchParentSize())
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Doom Blocker", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    TextButton(onClick = onFinish) { Text("Geç") }
                }
                HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth()) { page ->
                    val pageOffset = (pager.currentPage - page) + pager.currentPageOffsetFraction
                    Box(Modifier.fillMaxSize().graphicsLayer {
                        alpha = 1f - kotlin.math.abs(pageOffset).coerceIn(0f, 1f) * .6f
                        val scale = 1f - kotlin.math.abs(pageOffset).coerceIn(0f, 1f) * .08f
                        scaleX = scale; scaleY = scale
                    }) {
                        when (page) {
                            0 -> OnboardingPage(
                                step = null,
                                title = "Sonsuz kaydırmaya sınır koy",
                                body = "Seçtiğin uygulamalara günlük süre, oturum sınırı ve zorunlu mola koyarsın. İstersen Reels/Shorts'u kapatır, odak modunda hepsini kilitlersin. Hesap gerekmez; her şey telefonunda çalışır.",
                                visual = { GlowRing(.72f, accent, accent.copy(alpha = .12f), Modifier.size(150.dp), 12.dp) },
                            )
                            1 -> OnboardingPage(
                                step = 1,
                                title = "Kullanım erişimi",
                                body = "Telefon kapanıp açıldığında veya koruma kısa süre durduğunda hangi uygulamayı ne kadar kullandığını doğru hesaplamak için gerekir.",
                                granted = permissions.usageAccess,
                                action = "Ayarları aç" to { open(DeviceAccess.usageSettings(context)) },
                                hint = "Listede Doom Blocker'ı bul ve \"Kullanım erişimine izin ver\"i aç.",
                            )
                            2 -> OnboardingPage(
                                step = 2,
                                title = "Erişilebilirlik servisi",
                                body = "Korunan bir uygulama açıldığını anlamak ve sınır dolunca seni ana ekrana döndürmek için gerekir. Yalnızca öndeki uygulamanın adına bakılır; yazdıkların okunmaz.",
                                granted = permissions.accessibilityEnabled,
                                action = "Erişilebilirlik ayarları" to { open(DeviceAccess.accessibilitySettings()) },
                                hint = "Yüklü uygulamalar → Doom Blocker → Aç. Gri ve açılmıyorsa: Uygulama bilgisi → ⋮ → \"Kısıtlı ayarlara izin ver\", sonra tekrar dene.",
                                secondary = "Uygulama bilgisi" to { open(DeviceAccess.appSettings(context)) },
                            )
                            else -> OnboardingPage(
                                step = 3,
                                title = "Pil ve bildirimler",
                                body = "Samsung pil yönetimi korumayı uyutabilir; pil kullanımını \"Kısıtlanmamış\" yap. Bildirimler limitine yaklaşınca ve haftalık raporda seni haberdar eder.",
                                granted = permissions.batteryExempt && notifications,
                                action = "Pil ayarı" to { open(DeviceAccess.appSettings(context)) },
                                hint = "Uygulama bilgisi → Pil → Kısıtlanmamış.",
                                secondary = (if (notifications) "Bildirimler açık ✓" else "Bildirimlere izin ver") to {
                                    if (!notifications) notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                },
                            )
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        repeat(4) { index ->
                            val selected = pager.currentPage == index
                            val width by animateDpAsState(if (selected) 26.dp else 8.dp,
                                spring(dampingRatio = .7f, stiffness = Spring.StiffnessMediumLow), label = "dot")
                            val color by animateColorAsState(if (selected) accent else MaterialTheme.colorScheme.outlineVariant,
                                tween(250), label = "dot-color")
                            Box(Modifier.height(8.dp).width(width).clip(CircleShape).background(color))
                        }
                    }
                    val last = pager.currentPage == 3
                    AnimatedContent(last, label = "onboarding-next") { isLast ->
                        Button(
                            onClick = { if (isLast) onFinish() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } },
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.heightIn(min = 52.dp),
                        ) { Text(if (isLast) "Başla" else "Devam", fontWeight = FontWeight.Bold) }
                    }
                }
            }
        }
    }
}

@Composable
private fun OnboardingPage(
    step: Int?,
    title: String,
    body: String,
    granted: Boolean? = null,
    action: Pair<String, () -> Unit>? = null,
    hint: String? = null,
    secondary: Pair<String, () -> Unit>? = null,
    visual: (@Composable () -> Unit)? = null,
) {
    val accent = MaterialTheme.colorScheme.primary
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (visual != null) visual() else Box(Modifier.size(96.dp), contentAlignment = Alignment.Center) {
            val done = granted == true
            val bg by animateColorAsState(if (done) accent else accent.copy(alpha = .12f), tween(300), label = "step-bg")
            val scale = popScale(done)
            Box(Modifier.size(84.dp).graphicsLayer { scaleX = if (done) scale else 1f; scaleY = if (done) scale else 1f }
                .clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
                Text(if (done) "✓" else step.toString(), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
                    color = if (done) MaterialTheme.colorScheme.onPrimary else accent)
            }
        }
        if (step != null) Text("ADIM $step / 3", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = accent)
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (granted == true) Text("Tamam, izin verildi.", color = accent, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        else if (action != null) {
            Button(onClick = action.second, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                Text(action.first, fontWeight = FontWeight.Bold)
            }
            hint?.let {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(14.dp)) {
                    Text(it, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                }
            }
        }
        secondary?.let { (label, onClick) -> TextButton(onClick = onClick) { Text(label) } }
    }
}
