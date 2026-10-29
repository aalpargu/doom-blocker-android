package dev.personal.doomblocker.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.view.doOnPreDraw
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.personal.doomblocker.BlockerApplication
import dev.personal.doomblocker.core.IntentionalAccessPolicy
import dev.personal.doomblocker.core.PlannedEntry
import dev.personal.doomblocker.core.UsageEngine
import dev.personal.doomblocker.sync.SharedQuota
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class IntentGateActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
        overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = leaveForHome()
        })
        val packageName = intent.getStringExtra(EXTRA_PACKAGE).orEmpty()
        val label = intent.getStringExtra(EXTRA_LABEL).orEmpty()
        if (packageName.isBlank() || label.isBlank()) {
            finish()
            return
        }
        enableEdgeToEdge()
        setContent {
            val blocker = application as BlockerApplication
            val preferences by blocker.uiPreferences.state.collectAsStateWithLifecycle()
            val protection by blocker.repository.state.collectAsStateWithLifecycle()
            val cloud by blocker.deviceSync.state.collectAsStateWithLifecycle()
            var now by remember { mutableStateOf(blocker.clock.now()) }
            LaunchedEffect(Unit) {
                lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                    while (true) { now = blocker.clock.now(); delay(1_000) }
                }
            }
            val snapshot = protection.snapshot
            val travelActive = snapshot != null && protection.error == null &&
                SharedQuota.travelActive(snapshot, now)
            LaunchedEffect(travelActive) {
                if (travelActive) {
                    blocker.intentGateShield?.dismiss()
                    launchProtectedApp(packageName) { leaveForHome() }
                }
            }
            val entry = snapshot?.let { SharedQuota.capEntry(UsageEngine.plannedEntry(it, packageName, now), cloud, it, packageName, now) }
            val sharedBlock = snapshot?.let { SharedQuota.blockMessage(cloud, it, packageName, now) }
            val nextSchedule = entry?.nextSchedule?.let {
                val time = Instant.ofEpochMilli(it.startsAtWallMillis).atZone(ZoneId.of(snapshot!!.quotaZoneId))
                    .format(DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.forLanguageTag("tr")))
                "${it.label} · $time. Program başladığında kullanım durur; kalan süren korunur."
            }
            DoomBlockerTheme(preferences.amoledBlack, preferences.accent) {
                IntentGateScreen(
                    label = label,
                    entry = entry,
                    quotaReady = protection.error == null && snapshot != null && (travelActive || !cloud.linked ||
                        (SharedQuota.fresh(cloud, snapshot, now) && cloud.apps.any { it.packageName == packageName })),
                    scheduleNotice = nextSchedule,
                    storageError = protection.error?.let { "Kullanım bilgisi okunamadı. Koruma durumunu kontrol et." } ?: sharedBlock,
                    onCancel = ::leaveForHome,
                    onStart = { minutes, reason, onError ->
                        val blocker = application as BlockerApplication
                        blocker.appScope.launch {
                            val currentSnapshot = blocker.repository.state.value.snapshot
                            if (currentSnapshot != null && SharedQuota.travelActive(currentSnapshot, blocker.clock.now())) {
                                blocker.intentGateShield?.dismiss()
                                launchProtectedApp(packageName, onError)
                                return@launch
                            }
                            if (blocker.deviceSync.state.value.linked) {
                                blocker.deviceSync.sync()
                                val snapshot = blocker.repository.state.value.snapshot
                                val block = snapshot?.let { SharedQuota.blockMessage(
                                    blocker.deviceSync.state.value, it, packageName, blocker.clock.now()) }
                                if (block != null || snapshot == null) {
                                    onError(block ?: "Kullanım bilgisi hazırlanıyor.")
                                    return@launch
                                }
                            }
                            val latest = blocker.repository.state.value
                            val current = latest.snapshot?.let {
                                val time = blocker.clock.now()
                                SharedQuota.capEntry(UsageEngine.plannedEntry(it, packageName, time),
                                    blocker.deviceSync.state.value, it, packageName, time)
                            }
                            val blocked = entryBlockMessage(current)
                            val grant = when {
                                latest.error != null -> Result.failure(IllegalStateException("Kullanım bilgisi okunamadı."))
                                blocked != null -> Result.failure(IllegalStateException(blocked))
                                current != null && current.existingMillis > 0 -> Result.success(Unit)
                                else -> blocker.repository.startPlannedAccess(packageName, minutes, reason,
                                    current?.availableMillis ?: 0L)
                            }
                            grant.fold(
                                onSuccess = {
                                    val launch = packageManager.getLaunchIntentForPackage(packageName)
                                    if (launch == null) onError("Uygulama artık açılamıyor.")
                                    else {
                                        runCatching { startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)) }
                                            .onSuccess { finish() }
                                            .onFailure { onError("Android uygulamayı açamadı. Planlı süre uzatılmadı.") }
                                    }
                                },
                                onFailure = { onError(it.message ?: "Kısa giriş başlatılamadı.") },
                            )
                        }
                    },
                )
            }
        }
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        val token = intent.getLongExtra(EXTRA_HANDOFF_TOKEN, 0L)
        window.decorView.doOnPreDraw { view ->
            // Pre-draw alone is too early: keep the opaque cover until a frame is submitted.
            view.viewTreeObserver.registerFrameCommitCallback {
                view.post {
                    if (!isFinishing && !isDestroyed) {
                        (application as BlockerApplication).intentGateShield?.onGateFrameCommitted(token)
                    }
                }
            }
        }
    }

    private fun leaveForHome() {
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
        finish()
    }

    private fun launchProtectedApp(packageName: String, onError: (String) -> Unit) {
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        if (launch == null) {
            onError("Uygulama artık açılamıyor.")
            return
        }
        runCatching { startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)) }
            .onSuccess { finish() }
            .onFailure { onError("Android uygulamayı açamadı.") }
    }

    companion object {
        const val EXTRA_PACKAGE = "protected_package"
        const val EXTRA_LABEL = "protected_label"
        const val EXTRA_HANDOFF_TOKEN = "gate_handoff_token"
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IntentGateScreen(
    label: String,
    entry: PlannedEntry?,
    quotaReady: Boolean,
    scheduleNotice: String?,
    storageError: String?,
    onCancel: () -> Unit,
    onStart: (Int, String, (String) -> Unit) -> Unit,
) {
    var selectedMinutes by rememberSaveable { mutableIntStateOf(5) }
    var reason by rememberSaveable { mutableStateOf("") }
    var waitSeconds by rememberSaveable { mutableIntStateOf(IntentionalAccessPolicy.waitSeconds) }
    var error by remember { mutableStateOf<String?>(null) }
    var submitting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val existingUsageMillis = entry?.existingMillis ?: 0L
    val choices = entryChoices(entry)
    val selected = choices.firstOrNull { it.requestedMinutes == selectedMinutes }
        ?: choices.lastOrNull { it.requestedMinutes < selectedMinutes } ?: choices.firstOrNull()
    val blocked = storageError ?: entryBlockMessage(entry)

    LaunchedEffect(Unit) {
        while (waitSeconds > 0) {
            delay(1_000)
            waitSeconds--
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState())
                .padding(DoomTokens.space2Xl),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.Start,
        ) {
            FocusOrbit(Modifier.fillMaxWidth().height(64.dp))
            Text("BİR NEFES ARASI", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(top = DoomTokens.spaceLg, bottom = DoomTokens.spaceSm))
            Text("Neden açıyorum?", style = MaterialTheme.typography.headlineMedium)
            RemainingQuotaCard(if (quotaReady) entry?.availableMillis else null)
            Text(
                "$label için ne yapacağını belirle, süren bitince otomatik çık.",
                modifier = Modifier.padding(top = DoomTokens.spaceSm),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyLarge,
            )

            Text("Ne kadar kullanacaksın?", Modifier.padding(top = DoomTokens.space2Xl), style = MaterialTheme.typography.titleMedium)
            Text(
                "Seçtiğin süre bu oturumun sınırı. Telefonu kilitlediğinde veya başka uygulamaya geçtiğinde sayaç durur; dönünce kaldığın yerden devam eder.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = DoomTokens.spaceSm),
            )
            entry?.let {
                Text("Seçtiğin aktif süre bitince ${entryDuration(it.cooldownMillis)} mola başlar.",
                    modifier = Modifier.padding(top = DoomTokens.spaceSm),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            scheduleNotice?.let {
                Text(it, Modifier.padding(top = DoomTokens.spaceSm), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            blocked?.let {
                Text(it, Modifier.padding(top = DoomTokens.spaceSm), color = MaterialTheme.colorScheme.error)
            }
            if (existingUsageMillis > 0) Text(
                "Önceki girişinden ${((existingUsageMillis + 999) / 1000) / 60} dk ${((existingUsageMillis + 999) / 1000) % 60} sn kullanım kaldı. Yeni süre eklenmez.",
                Modifier.padding(top = DoomTokens.spaceSm), color = MaterialTheme.colorScheme.primary,
            )
            if (existingUsageMillis <= 0) FlowRow(
                Modifier.fillMaxWidth().padding(top = DoomTokens.spaceSm),
                horizontalArrangement = Arrangement.spacedBy(DoomTokens.spaceSm),
            ) {
                choices.forEach { choice ->
                    FilterChip(
                        selected = selected == choice,
                        onClick = { selectedMinutes = choice.requestedMinutes },
                        enabled = blocked == null && !submitting,
                        label = { Text(choice.label) },
                    )
                }
            }

            if (existingUsageMillis <= 0) OutlinedTextField(
                value = reason,
                onValueChange = { reason = it.take(IntentionalAccessPolicy.maxReasonLength); error = null },
                modifier = Modifier.fillMaxWidth().padding(top = DoomTokens.spaceLg),
                label = { Text("Ne yapacağım?") },
                placeholder = { Text("Örn. bir mesaja yanıt vereceğim") },
                supportingText = { Text("Kısa ve somut bir amaç yaz.") },
                singleLine = true,
            )
            error?.let {
                Text(it, Modifier.padding(top = DoomTokens.spaceSm), color = MaterialTheme.colorScheme.error)
            }

            Button(
                onClick = {
                    submitting = true
                    scope.launch { onStart(selected?.requestedMinutes ?: selectedMinutes, reason.trim()) { error = it; submitting = false } }
                },
                enabled = !submitting && blocked == null && (existingUsageMillis > 0 ||
                    (selected != null && IntentionalAccessPolicy.canStart(selected.requestedMinutes, reason, waitSeconds))),
                modifier = Modifier.fillMaxWidth().padding(top = DoomTokens.space2Xl).heightIn(min = DoomTokens.controlHeight),
            ) {
                Text(if (submitting) "Açılıyor…" else if (existingUsageMillis > 0) "Kalan hakkımla devam et"
                    else if (waitSeconds > 0) "$waitSeconds sn düşün" else "${entryDuration(selected?.usageMillis ?: 0)} kullanım için aç")
            }
            TextButton(onClick = onCancel, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text("Şimdi açma")
            }
        }
    }
}

@Composable
private fun RemainingQuotaCard(availableMillis: Long?) {
    val colors = MaterialTheme.colorScheme
    val (background, foreground) = when {
        availableMillis == null -> colors.surfaceContainer to colors.onSurface
        availableMillis <= 0 -> colors.error to colors.onError
        availableMillis <= 60_000 -> colors.tertiary to colors.onTertiary
        else -> colors.primary to colors.onPrimary
    }
    Surface(
        color = background, contentColor = foreground, shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().padding(top = DoomTokens.spaceLg, bottom = DoomTokens.spaceSm),
    ) {
        Column(Modifier.padding(DoomTokens.spaceXl), verticalArrangement = Arrangement.spacedBy(DoomTokens.spaceXs)) {
            Text("BUGÜNKÜ KALAN HAKKIN", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            Text(availableMillis?.let(::entryDuration) ?: "Hesaplanıyor…",
                style = if (availableMillis == null) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.ExtraBold)
            Text(when {
                availableMillis == null -> "Güncel kullanım hakkın yükleniyor."
                availableMillis <= 0 -> "Bugünkü kullanım hakkın doldu."
                else -> "Günlük ve ortak kotana göre kullanabileceğin toplam süre."
            }, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
