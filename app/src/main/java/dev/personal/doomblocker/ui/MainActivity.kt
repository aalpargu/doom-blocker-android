package dev.personal.doomblocker.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.graphics.createBitmap
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.personal.doomblocker.BlockerApplication
import dev.personal.doomblocker.R
import dev.personal.doomblocker.core.AppAvailability
import dev.personal.doomblocker.core.AppPolicy
import dev.personal.doomblocker.core.AppView
import dev.personal.doomblocker.core.DailyAppRecord
import dev.personal.doomblocker.core.DailyRecord
import dev.personal.doomblocker.core.Limits
import dev.personal.doomblocker.core.ProtectedApp
import dev.personal.doomblocker.core.ProtectedGroup
import dev.personal.doomblocker.core.ProtectedSchedule
import dev.personal.doomblocker.core.TravelException
import dev.personal.doomblocker.core.UsageEngine
import dev.personal.doomblocker.data.DiagnosticEvent
import dev.personal.doomblocker.data.ImportPreview
import dev.personal.doomblocker.data.RoutineProfile
import dev.personal.doomblocker.platform.DeviceAccess
import dev.personal.doomblocker.platform.FocusFeedback
import dev.personal.doomblocker.platform.InstalledApp
import dev.personal.doomblocker.platform.PermissionState
import dev.personal.doomblocker.platform.QuickActionCatalog
import dev.personal.doomblocker.service.ProtectionProbeResult
import dev.personal.doomblocker.service.LimitNotificationManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil

class MainActivity : ComponentActivity() {
    private val uiModel by viewModels<DoomBlockerViewModel>()
    internal val pendingShortcutAction = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingShortcutAction.value = intent?.action?.takeIf { it != Intent.ACTION_MAIN }
        enableEdgeToEdge()
        setContent {
            val preferences by (application as BlockerApplication).uiPreferences.state.collectAsStateWithLifecycle()
            DoomBlockerTheme(preferences.amoledBlack, preferences.accent) { DoomBlockerScreen(uiModel, this) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingShortcutAction.value = intent.action?.takeIf { it != Intent.ACTION_MAIN }
    }

    internal fun consumeShortcutAction() {
        pendingShortcutAction.value = null
        intent?.action = Intent.ACTION_MAIN
    }

    companion object {
        const val ACTION_FOCUS_30 = QuickActionCatalog.ACTION_FOCUS_30
        const val ACTION_LOCK_TODAY = QuickActionCatalog.ACTION_LOCK_TODAY
        const val ACTION_ADD_APP = QuickActionCatalog.ACTION_ADD_APP
    }
}

private enum class HomeSection(val label: String) {
    TODAY("Bugün"), PLANS("Planlar"), SETTINGS("Ayarlar")
}

private enum class TargetSort(val label: String) {
    SMART("Akıllı"), LEAST_REMAINING("En az kalan"), MOST_USED("En çok kullanılan"), BLOCKED("Engellenen")
}

private const val MAX_BACKUP_BYTES = 1_048_576
private val tripZone = ZoneId.of("Europe/Istanbul")
private val tripStartWallMillis = LocalDate.of(2026, 9, 26).atTime(22, 0).atZone(tripZone).toInstant().toEpochMilli()
private val tripEndWallMillis = LocalDate.of(2026, 9, 28).atStartOfDay(tripZone).toInstant().toEpochMilli()

private data class PolicyEditorTarget(val installed: InstalledApp?, val protected: ProtectedApp?)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DoomBlockerScreen(uiModel: DoomBlockerViewModel, activity: MainActivity) {
    val context = LocalContext.current
    val app = context.applicationContext as BlockerApplication
    val labMode = remember { context.resources.getBoolean(R.bool.lab_mode) }
    val timeUnitMillis = remember { context.resources.getInteger(R.integer.time_unit_millis).toLong() }
    val protection by app.repository.state.collectAsStateWithLifecycle()
    val cloud by app.deviceSync.state.collectAsStateWithLifecycle()
    val runtime by app.serviceStatus.state.collectAsStateWithLifecycle()
    val diagnostics by app.diagnostics.events.collectAsStateWithLifecycle()
    val routines by app.routines.profiles.collectAsStateWithLifecycle()
    val uiPreferences by app.uiPreferences.state.collectAsStateWithLifecycle()
    val pendingShortcutAction by activity.pendingShortcutAction.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    var permissions by remember { mutableStateOf(DeviceAccess.permissions(app)) }
    var installed by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
    var refreshApps by remember { mutableIntStateOf(0) }
    var now by remember { mutableStateOf(app.clock.now()) }
    var showPicker by rememberSaveable { mutableStateOf(false) }
    var editorTarget by remember { mutableStateOf<PolicyEditorTarget?>(null) }
    var editorPackageKey by rememberSaveable { mutableStateOf<String?>(null) }
    var removeTarget by remember { mutableStateOf<ProtectedApp?>(null) }
    var strictAction by remember { mutableStateOf<Boolean?>(null) }
    var focusLockConfirm by rememberSaveable { mutableStateOf(false) }
    var focusLockTarget by remember { mutableStateOf<ProtectedApp?>(null) }
    var showGroupEditor by rememberSaveable { mutableStateOf(false) }
    var editingGroup by remember { mutableStateOf<ProtectedGroup?>(null) }
    var editingGroupId by rememberSaveable { mutableStateOf<String?>(null) }
    var removeGroup by remember { mutableStateOf<ProtectedGroup?>(null) }
    var showScheduleEditor by rememberSaveable { mutableStateOf(false) }
    var editingSchedule by remember { mutableStateOf<ProtectedSchedule?>(null) }
    var editingScheduleId by rememberSaveable { mutableStateOf<String?>(null) }
    var removeSchedule by remember { mutableStateOf<ProtectedSchedule?>(null) }
    var showRoutineEditor by rememberSaveable { mutableStateOf(false) }
    var editingRoutine by remember { mutableStateOf<RoutineProfile?>(null) }
    var editingRoutineId by rememberSaveable { mutableStateOf<String?>(null) }
    var removeRoutine by remember { mutableStateOf<RoutineProfile?>(null) }
    var emergencyTarget by remember { mutableStateOf<ProtectedApp?>(null) }
    var showFocusSession by rememberSaveable { mutableStateOf(false) }
    var showDiagnostics by rememberSaveable { mutableStateOf(false) }
    var showHistory by rememberSaveable { mutableStateOf(false) }
    var showAdvancedPlans by rememberSaveable { mutableStateOf(false) }
    var confirmTravelCancel by rememberSaveable { mutableStateOf(false) }
    var targetSort by rememberSaveable { mutableStateOf(TargetSort.SMART) }
    val selectedSectionIndex by uiModel.selectedSectionIndex.collectAsStateWithLifecycle()
    var message by remember { mutableStateOf<String?>(null) }
    var exportPayload by remember { mutableStateOf<String?>(null) }
    var diagnosticsPayload by remember { mutableStateOf<String?>(null) }
    var historyPayload by remember { mutableStateOf<String?>(null) }
    var corruptPayload by remember { mutableStateOf<String?>(null) }
    var pendingImportRaw by remember { mutableStateOf<String?>(null) }
    var importPreview by remember { mutableStateOf<ImportPreview?>(null) }
    var pendingImportRoutineCount by remember { mutableIntStateOf(0) }
    var importUndoAvailable by remember {
        mutableStateOf(app.repository.hasImportUndo() || app.routines.hasImportUndo())
    }
    var confirmStorageReset by rememberSaveable { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val appVersion = remember {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }
    var notificationsEnabled by remember {
        mutableStateOf(LimitNotificationManager.isEnabled(context) && LimitNotificationManager.hasPermission(context))
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        LimitNotificationManager.setEnabled(context, granted)
        notificationsEnabled = granted
        message = if (granted) "Limit uyarıları açıldı." else "Bildirim izni verilmedi; uyarılar kapalı kaldı."
    }

    val rulesExporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val payload = exportPayload
        exportPayload = null
        if (uri != null && payload != null) scope.launch(Dispatchers.IO) {
            runCatching {
                val output = context.contentResolver.openOutputStream(uri)
                    ?: error("Çıktı akışı açılamadı.")
                output.bufferedWriter().use { it.write(payload) }
            }
                .onSuccess { withContext(Dispatchers.Main) { message = "Kural yedeği kaydedildi." } }
                .onFailure { withContext(Dispatchers.Main) { message = "Yedek dosyası yazılamadı." } }
        }
    }
    val diagnosticsExporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        val payload = diagnosticsPayload
        diagnosticsPayload = null
        if (uri != null && payload != null) scope.launch(Dispatchers.IO) {
            runCatching {
                val output = context.contentResolver.openOutputStream(uri)
                    ?: error("Çıktı akışı açılamadı.")
                output.bufferedWriter().use { it.write(payload) }
            }
                .onSuccess { withContext(Dispatchers.Main) { message = "Tanılama günlüğü kaydedildi." } }
                .onFailure { withContext(Dispatchers.Main) { message = "Tanılama dosyası yazılamadı." } }
        }
    }
    val historyExporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv"),
    ) { uri ->
        val payload = historyPayload
        historyPayload = null
        if (uri != null && payload != null) scope.launch(Dispatchers.IO) {
            runCatching {
                val output = context.contentResolver.openOutputStream(uri)
                    ?: error("Çıktı akışı açılamadı.")
                output.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            }
                .onSuccess { withContext(Dispatchers.Main) { message = "Kullanım geçmişi CSV olarak kaydedildi." } }
                .onFailure { withContext(Dispatchers.Main) { message = "CSV dosyası yazılamadı." } }
        }
    }
    val corruptExporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val payload = corruptPayload
        corruptPayload = null
        if (uri != null && payload != null) scope.launch(Dispatchers.IO) {
            runCatching {
                val output = context.contentResolver.openOutputStream(uri)
                    ?: error("Çıktı akışı açılamadı.")
                output.bufferedWriter().use { it.write(payload) }
            }
                .onSuccess { withContext(Dispatchers.Main) { message = "Bozuk kayıt kopyası kaydedildi." } }
                .onFailure { withContext(Dispatchers.Main) { message = "Bozuk kayıt dışa aktarılamadı." } }
        }
    }
    val rulesImporter = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val raw = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        val bytes = input.readNBytes(MAX_BACKUP_BYTES + 1)
                        require(bytes.size <= MAX_BACKUP_BYTES) { "Yedek dosyası 1 MB sınırını aşıyor." }
                        bytes.toString(Charsets.UTF_8)
                    }
                }.getOrNull()
            }
            if (raw == null) message = "Yedek dosyası okunamadı."
            else app.repository.previewRules(raw).fold(
                onSuccess = { preview ->
                    app.routines.validateRulesBackup(raw).fold(
                        onSuccess = { routineCount ->
                            pendingImportRaw = raw
                            pendingImportRoutineCount = routineCount
                            importPreview = preview
                        },
                        onFailure = { message = it.message ?: "Plan profilleri doğrulanamadı." },
                    )
                },
                onFailure = { message = it.message ?: "Yedek doğrulanamadı." },
            )
        }
    }
    val recoveryRulesImporter = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val raw = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        val bytes = input.readNBytes(MAX_BACKUP_BYTES + 1)
                        require(bytes.size <= MAX_BACKUP_BYTES) { "Yedek dosyası 1 MB sınırını aşıyor." }
                        bytes.toString(Charsets.UTF_8)
                    }
                }.getOrNull()
            }
            if (raw == null) message = "Yedek dosyası okunamadı."
            else app.repository.validateRules(raw).fold(
                onSuccess = {
                    app.routines.validateRulesBackup(raw).fold(
                        onSuccess = {
                            app.repository.resetStorage().getOrThrow()
                            val result = app.repository.importRules(raw).getOrThrow()
                            val profiles = app.routines.importRulesBackup(raw)
                            if (profiles.isFailure) {
                                app.repository.undoLastImport()
                                message = profiles.exceptionOrNull()?.message ?: "Plan profilleri geri yüklenemedi; kural değişiklikleri geri alındı."
                            } else {
                                importUndoAvailable = true
                                message = "Kayıt sıfırlandı ve yedek geri yüklendi: ${result.added} uygulama, ${profiles.getOrThrow()} profil eklendi."
                            }
                        },
                        onFailure = { message = it.message ?: "Plan profilleri doğrulanamadı." },
                    )
                },
                onFailure = { message = it.message ?: "Yedek doğrulanamadı." },
            )
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permissions = DeviceAccess.permissions(app)
                refreshApps++
                scope.launch { app.repository.refresh() }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(refreshApps) {
        installed = withContext(Dispatchers.IO) { app.catalog.installedApps() }
    }
    LaunchedEffect(Unit) {
        while (isActive) {
            now = app.clock.now()
            delay(1_000)
        }
    }
    LaunchedEffect(message) {
        val current = message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(current)
        message = null
    }
    LaunchedEffect(runtime.probeStartedWallMillis, runtime.probeResult) {
        if (runtime.probeResult == ProtectionProbeResult.RUNNING) {
            val remaining = 60_000L - (System.currentTimeMillis() - (runtime.probeStartedWallMillis ?: 0L))
            if (remaining > 0) delay(remaining)
            if (app.serviceStatus.state.value.probeResult == ProtectionProbeResult.RUNNING) {
                app.serviceStatus.update { it.copy(probeResult = ProtectionProbeResult.TIMED_OUT) }
            }
        }
    }

    LaunchedEffect(pendingShortcutAction, protection.ready) {
        val action = pendingShortcutAction ?: return@LaunchedEffect
        if (!protection.ready) return@LaunchedEffect
        when (action) {
            MainActivity.ACTION_FOCUS_30 -> {
                val packages = protection.snapshot?.apps.orEmpty().mapTo(linkedSetOf()) { it.packageName }
                if (packages.isEmpty()) message = "Odak modu için önce bir uygulama ekle."
                else app.repository.startFocusSession(packages, 30)
                    .onSuccess {
                        FocusFeedback.perform(context)
                        message = "30 dakikalık odak başladı."
                    }
                    .onFailure { message = it.message ?: "Odak modu başlatılamadı." }
                uiModel.selectSection(HomeSection.TODAY.ordinal)
            }
            MainActivity.ACTION_LOCK_TODAY -> {
                if (protection.snapshot?.apps?.isEmpty() != false) message = "Kilitlemek için önce bir uygulama ekle."
                else app.repository.lockAllForToday()
                    .onSuccess { message = "Tüm uygulamalar bugünlük kilitlendi." }
                    .onFailure { message = it.message ?: "Uygulamalar kilitlenemedi." }
                uiModel.selectSection(HomeSection.TODAY.ordinal)
            }
            MainActivity.ACTION_ADD_APP -> {
                uiModel.selectSection(HomeSection.TODAY.ordinal)
                showPicker = true
            }
        }
        activity.consumeShortcutAction()
    }

    val snapshot = protection.snapshot
    val readyForProtection = permissions.usageAccess && permissions.accessibilityEnabled
    val selectedSection = HomeSection.entries[selectedSectionIndex.coerceIn(HomeSection.entries.indices)]
    val todayListState = rememberLazyListState()
    val plansListState = rememberLazyListState()
    val settingsListState = rememberLazyListState()
    val listState = when (selectedSection) {
        HomeSection.TODAY -> todayListState
        HomeSection.PLANS -> plansListState
        HomeSection.SETTINGS -> settingsListState
    }
    val pageEntrance = rememberPageEntrance(selectedSection to showDiagnostics)
    val setupComplete = readyForProtection && runtime.connected && snapshot?.apps?.isNotEmpty() == true
    val installedByPackage = installed.associateBy { it.packageName }
    val resolvedEditorTarget = editorTarget ?: editorPackageKey?.let { packageName ->
        PolicyEditorTarget(
            installedByPackage[packageName],
            snapshot?.apps?.firstOrNull { it.packageName == packageName },
        )
    }
    val resolvedEditingGroup = editingGroup ?: editingGroupId?.let { id -> snapshot?.groups?.firstOrNull { it.id == id } }
    val resolvedEditingSchedule = editingSchedule ?: editingScheduleId?.let { id -> snapshot?.schedules?.firstOrNull { it.id == id } }
    val resolvedEditingRoutine = editingRoutine ?: editingRoutineId?.let { id -> routines.firstOrNull { it.id == id } }
    val orderedTargets = snapshot?.apps.orEmpty().sortedWith(targetComparator(snapshot, now, targetSort))
    BackHandler(enabled = showDiagnostics) { showDiagnostics = false }
    LaunchedEffect(showDiagnostics) { settingsListState.scrollToItem(0) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            DoomNavigationBar(selectedSection) { section ->
                showDiagnostics = false
                uiModel.selectSection(section.ordinal)
            }
        },
    ) { insets ->
        Box(Modifier.fillMaxSize().padding(insets)) {
            LazyColumn(
                state = listState,
                modifier = Modifier.align(Alignment.TopCenter).widthIn(max = DoomTokens.maxContentWidth)
                    .fillMaxHeight().fillMaxWidth().graphicsLayer {
                        alpha = pageEntrance.value
                        translationY = (1f - pageEntrance.value) * 12.dp.toPx()
                    },
                contentPadding = PaddingValues(
                    horizontal = DoomTokens.spaceXl,
                    vertical = if (selectedSection == HomeSection.TODAY) DoomTokens.spaceLg else DoomTokens.space2Xl,
                ),
                verticalArrangement = Arrangement.spacedBy(
                    if (selectedSection == HomeSection.TODAY) DoomTokens.spaceMd else DoomTokens.spaceLg,
                ),
            ) {
                item {
                    AppHeader(
                        section = selectedSection,
                        labMode = labMode,
                        strictMode = snapshot?.strictModeEnabled == true,
                        setupComplete = setupComplete,
                        protectionProblem = runtime.problem != null || protection.error != null,
                        diagnosticsOpen = showDiagnostics,
                        onBack = { showDiagnostics = false },
                    )
                }

                if (labMode) item {
                    NoticeCard("Laboratuvar modu açık · 1 birim 10 saniye olarak ölçülür.", NoticeTone.Info)
                }
                protection.error?.let { item { NoticeCard(it, NoticeTone.Error) } }
                protection.measurementWarning?.let { item { NoticeCard(it, NoticeTone.Warning) } }
                runtime.problem?.takeIf { protection.error == null }?.let { problem ->
                    item {
                        NoticeCard(problem, NoticeTone.Error, actionLabel = "Kontrol et") {
                            uiModel.selectSection(HomeSection.SETTINGS.ordinal)
                        }
                    }
                }
                if (protection.error != null) item {
                    RecoveryCard(
                        recoveryAvailable = protection.recoveryAvailable,
                        corruptAvailable = protection.corruptRecordName != null,
                        onRecover = {
                            scope.launch {
                                app.repository.recoverLastGood()
                                    .onSuccess { message = "Son sağlam kayıt geri yüklendi." }
                                    .onFailure { message = it.message ?: "Sağlam kayıt geri yüklenemedi." }
                            }
                        },
                        onRestoreRules = { recoveryRulesImporter.launch(arrayOf("application/json", "text/plain")) },
                        onExportCorrupt = {
                            scope.launch {
                                app.repository.exportCorruptRecord()
                                    .onSuccess {
                                        corruptPayload = it
                                        corruptExporter.launch("doom-blocker-bozuk-kayit.json")
                                    }
                                    .onFailure { message = it.message ?: "Bozuk kayıt okunamadı." }
                            }
                        },
                        onReset = { confirmStorageReset = true },
                    )
                }

                when (selectedSection) {
                    HomeSection.TODAY -> {
                        if (!setupComplete) item {
                            SetupCard(
                                permissions = permissions,
                                serviceConnected = runtime.connected,
                                hasTarget = snapshot?.apps?.isNotEmpty() == true,
                                lastEvaluationWall = runtime.lastEvaluationWallMillis,
                                nowWall = now.wallMillis,
                                app = app,
                            )
                        }

                        if (!protection.ready && protection.error == null) {
                            item { LoadingCard() }
                        } else if (snapshot != null) {
                            if (snapshot.travelException?.isActive(now.wallMillis) == true) item {
                                NoticeCard(
                                    "Yolculuk istisnası açık · Tüm korunan uygulamalar 27 Eylül sonuna kadar kullanılabilir.",
                                    NoticeTone.Info,
                                    actionLabel = "Planı gör",
                                ) { uiModel.selectSection(HomeSection.PLANS.ordinal) }
                            }
                            if (snapshot.apps.isNotEmpty()) item {
                                TodaySummaryCard(snapshot, now)
                            }
                            if (cloud.linked) item {
                                NoticeCard("Aşağıdaki istatistikler bu cihaza aittir. Telefon ve tablet toplamını Cihazlarım ekranından görebilirsin.", NoticeTone.Info)
                            }
                            item {
                                SettingsActionRow("Cihazlarım · ortak günlük kota",
                                    "Telefon ve tabletteki toplam kullanımı gör.") {
                                    context.startActivity(Intent(context, DeviceSyncActivity::class.java))
                                }
                            }
                            if (snapshot.apps.isNotEmpty()) item {
                                FocusModeCard(
                                    activeRemainingMillis = snapshot.focusSession?.block?.remainingMillis(now) ?: 0,
                                    activeLabels = snapshot.apps.filter { it.packageName in snapshot.focusSession?.packageNames.orEmpty() }.map { it.label },
                                    enabled = readyForProtection && runtime.connected,
                                    onStartAll = { minutes ->
                                        scope.launch {
                                            app.repository.startFocusSession(
                                                snapshot.apps.mapTo(linkedSetOf()) { it.packageName },
                                                minutes,
                                            ).onSuccess {
                                                FocusFeedback.perform(context)
                                                message = "$minutes dakikalık odak başladı."
                                            }.onFailure { message = it.message ?: "Odak modu başlatılamadı." }
                                        }
                                    },
                                    onChooseApps = { showFocusSession = true },
                                )
                            }
                            item {
                                SectionHeading(
                                    title = "Korunan uygulamalar",
                                    supporting = if (orderedTargets.isEmpty()) "İlk uygulamanı ekleyerek odak planını oluştur."
                                    else null,
                                )
                            }
                            if (orderedTargets.size > 1) item {
                                TargetSortMenu(selected = targetSort, onSelect = { targetSort = it })
                            }
                            if (orderedTargets.isEmpty()) {
                                item { EmptyCard(readyForProtection) { showPicker = true } }
                            } else {
                                items(orderedTargets, key = { it.packageName }) { target ->
                                    if (cloud.linked) {
                                        val shared = cloud.apps.firstOrNull { it.packageName == target.packageName }
                                        val fresh = dev.personal.doomblocker.sync.SharedQuota.fresh(cloud, snapshot, now)
                                        val status = dev.personal.doomblocker.sync.SharedQuota.blockMessage(cloud, snapshot, target.packageName, now)
                                        Text(if (fresh && shared != null) status ?: "${target.label} · Ortak kalan: ${
                                            (dev.personal.doomblocker.sync.SharedQuota.remaining(shared,
                                                UsageEngine.view(snapshot, target, now).usedTodayMillis) / 1000).let { "${it / 60} dk ${it % 60} sn" }}"
                                            else "${target.label} · Ortak kota güncelleniyor…",
                                            style = MaterialTheme.typography.labelLarge)
                                    }
                                    TargetCard(
                                        modifier = Modifier.animateItem(),
                                        target = target,
                                        installed = installedByPackage[target.packageName],
                                        view = UsageEngine.view(snapshot, target, now),
                                        zoneId = snapshot.quotaZoneId,
                                        onIntentionalOpenChange = { enabled ->
                                            scope.launch {
                                                app.repository.setIntentionalOpen(target.packageName, enabled)
                                                    .onSuccess { message = if (enabled) "Bilinçli giriş açıldı." else "Bilinçli giriş kapatıldı." }
                                                    .onFailure { message = it.message ?: "Bilinçli giriş ayarı kaydedilemedi." }
                                            }
                                        },
                                        onEdit = {
                                            editorPackageKey = target.packageName
                                            editorTarget = PolicyEditorTarget(installedByPackage[target.packageName], target)
                                        },
                                        onLockToday = { focusLockTarget = target },
                                        emergencyAvailable = target.emergencyUsedEpochDay != snapshot.activeEpochDay,
                                        onEmergency = { emergencyTarget = target },
                                        onRemove = { removeTarget = target },
                                        onCancelRemoval = {
                                            scope.launch {
                                                app.repository.cancelRemoval(target.packageName)
                                                    .onFailure { message = it.message ?: "Kaldırma iptal edilemedi." }
                                            }
                                        },
                                    )
                                }
                                item {
                                    AddAppButton(
                                        enabled = readyForProtection,
                                        onAdd = { showPicker = true },
                                    )
                                }
                            }

                            item {
                                SettingsActionRow("Kullanım geçmişi", "Günlük ayrıntılar ve haftalık eğilimler") { showHistory = true }
                            }
                        }
                    }

                    HomeSection.PLANS -> {
                        if (!protection.ready && protection.error == null) item { LoadingCard() }
                        else if (snapshot != null) {
                            if (now.wallMillis < tripEndWallMillis) item {
                                PlanSection(
                                    title = "Yolculuk istisnası",
                                    supporting = "26 Eylül 22.00 – 27 Eylül 23.59 · Türkiye saati",
                                ) {
                                    TravelExceptionPanel(
                                        exception = snapshot.travelException,
                                        nowWallMillis = now.wallMillis,
                                        onSchedule = {
                                            scope.launch {
                                                app.repository.setTravelException(tripStartWallMillis, tripEndWallMillis)
                                                    .onSuccess { message = "Yolculuk istisnası planlandı." }
                                                    .onFailure { message = it.message ?: "Yolculuk istisnası planlanamadı." }
                                            }
                                        },
                                        onCancel = { confirmTravelCancel = true },
                                    )
                                }
                            }
                            item {
                                PlanSection(
                                    title = "Çalışma saatlerim",
                                    supporting = "Seçili uygulamaları belirli saatlerde otomatik kısıtla.",
                                ) {
                                    SchedulesPanel(
                                        snapshot = snapshot,
                                        now = now,
                                        onAdd = { editingSchedule = null; editingScheduleId = null; showScheduleEditor = true },
                                        onEdit = { editingSchedule = it; editingScheduleId = it.id; showScheduleEditor = true },
                                        onRemove = { removeSchedule = it },
                                        onCancelRemoval = { schedule ->
                                            scope.launch {
                                                app.repository.cancelScheduleRemoval(schedule.id)
                                                    .onFailure { message = it.message ?: "Kaldırma iptal edilemedi." }
                                            }
                                        },
                                    )
                                }
                            }
                            item {
                                PlanSection(
                                    title = "Sosyal medya için günlük toplam süre",
                                    supporting = "Birden fazla uygulama aynı günlük süreyi paylaşsın.",
                                ) {
                                    GroupsPanel(
                                        snapshot = snapshot,
                                        now = now,
                                        onAdd = { editingGroup = null; editingGroupId = null; showGroupEditor = true },
                                        onEdit = { editingGroup = it; editingGroupId = it.id; showGroupEditor = true },
                                        onRemove = { removeGroup = it },
                                        onCancelRemoval = { group ->
                                            scope.launch {
                                                app.repository.cancelGroupRemoval(group.id)
                                                    .onFailure { message = it.message ?: "Kaldırma iptal edilemedi." }
                                            }
                                        },
                                    )
                                }
                            }
                            item {
                                DisclosureRow("Gelişmiş kurallar", "Hazır limit düzenleri ve bugünlük kilit", showAdvancedPlans) {
                                    showAdvancedPlans = !showAdvancedPlans
                                }
                            }
                            if (showAdvancedPlans) {
                            item {
                                PlanSection(
                                    title = "Plan profilleri",
                                    supporting = "Farklı günler için hazır limit düzenleri oluştur.",
                                ) {
                                    RoutineProfilesPanel(
                                        profiles = routines,
                                        apps = snapshot.apps,
                                        unitMillis = timeUnitMillis,
                                        onAdd = { editingRoutine = null; editingRoutineId = null; showRoutineEditor = true },
                                        onEdit = { editingRoutine = it; editingRoutineId = it.id; showRoutineEditor = true },
                                        onApply = { profile ->
                                            scope.launch {
                                                val policy = AppPolicy(
                                                    Limits(
                                                        profile.dailyMinutes,
                                                        profile.sessionMinutes,
                                                        profile.cooldownMinutes,
                                                        timeUnitMillis,
                                                    ),
                                                )
                                                app.repository.applyPolicyToApps(profile.packageNames, policy)
                                                    .onSuccess { message = "${profile.label} profili $it uygulamaya uygulandı." }
                                                    .onFailure { message = it.message ?: "Profil uygulanamadı." }
                                            }
                                        },
                                        onRemove = { removeRoutine = it },
                                    )
                                }
                            }
                                if (snapshot.apps.isNotEmpty()) item {
                                    FocusLockCard(enabled = snapshot.apps.any { !it.manualBlocked }, onLock = { focusLockConfirm = true })
                                }
                            }
                        }
                    }

                    HomeSection.SETTINGS -> {
                        if (showDiagnostics) {
                            if (!protection.ready && protection.error == null) item { LoadingCard() }
                            else if (snapshot != null) {
                                item {
                                    SectionHeading(
                                        "Teknik ayrıntılar",
                                        "Gelişmiş sorun giderme bilgileri yalnızca bu ekranda tutulur.",
                                    )
                                }
                                item {
                                    SectionSurface {
                                        DiagnosticsPanel(
                                            events = diagnostics,
                                            connected = runtime.connected,
                                            foreground = runtime.foregroundPackage,
                                            lastEvaluationWall = runtime.lastEvaluationWallMillis,
                                            homeRequests = runtime.homeRequests,
                                            lastBlocked = runtime.lastBlockedPackage,
                                            problem = runtime.problem,
                                            pictureInPicture = runtime.pictureInPictureVisible,
                                            zoneId = snapshot.quotaZoneId,
                                            onClear = { scope.launch { app.diagnostics.clear() } },
                                            onExport = {
                                                diagnosticsPayload = app.diagnostics.exportText(snapshot.quotaZoneId)
                                                diagnosticsExporter.launch("doom-blocker-tanilama.txt")
                                            },
                                        )
                                    }
                                }
                            }
                        } else {
                            if (!setupComplete) item {
                                SetupCard(
                                    permissions = permissions,
                                    serviceConnected = runtime.connected,
                                    hasTarget = snapshot?.apps?.isNotEmpty() == true,
                                    lastEvaluationWall = runtime.lastEvaluationWallMillis,
                                    nowWall = now.wallMillis,
                                    app = app,
                                )
                            }
                            if (!protection.ready && protection.error == null) item { LoadingCard() }
                            else if (snapshot != null) {
                                item {
                                    SettingsGroup("Koruma") {
                                        ProtectionStatusSummary(
                                            ready = permissions.usageAccess && permissions.accessibilityEnabled && runtime.connected,
                                            problem = runtime.problem,
                                        )
                                        SubtleDivider()
                                        ProtectionTestPanel(
                                            runtimeResult = runtime.probeResult,
                                            runtimeLabel = runtime.probeLabel,
                                            target = snapshot.apps.firstOrNull { installedByPackage[it.packageName] != null },
                                            enabled = permissions.accessibilityEnabled && runtime.connected,
                                            onRun = { target ->
                                                val launchIntent = context.packageManager.getLaunchIntentForPackage(target.packageName)
                                                if (launchIntent == null) message = "${target.label} açılamadı."
                                                else {
                                                    app.serviceStatus.beginProbe(target.packageName, target.label)
                                                    context.startActivity(launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                                }
                                            },
                                            onClear = app.serviceStatus::clearProbe,
                                        )
                                        SubtleDivider()
                                        StrictModePanel(
                                            snapshot.strictModeEnabled,
                                            snapshot.strictModeDisableAtWallMillis,
                                            snapshot.quotaZoneId,
                                            onAction = { strictAction = it },
                                        )
                                        SubtleDivider()
                                        NotificationSettingsPanel(
                                            enabled = notificationsEnabled,
                                            onToggle = { desired ->
                                                if (!desired) {
                                                    LimitNotificationManager.setEnabled(context, false)
                                                    notificationsEnabled = false
                                                    message = "Limit uyarıları kapatıldı."
                                                } else if (ContextCompat.checkSelfPermission(
                                                        context,
                                                        Manifest.permission.POST_NOTIFICATIONS,
                                                    ) == PackageManager.PERMISSION_GRANTED
                                                ) {
                                                    LimitNotificationManager.setEnabled(context, true)
                                                    notificationsEnabled = true
                                                    message = "Limit uyarıları açıldı."
                                                } else notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                            },
                                        )
                                    }
                                }
                                item {
                                    SettingsGroup("Görünüm") {
                                        AppearanceSettings(
                                            preferences = uiPreferences,
                                            onAmoledChange = app.uiPreferences::setAmoledBlack,
                                            onAccentChange = app.uiPreferences::setAccent,
                                        )
                                    }
                                }
                                item {
                                    SettingsGroup("Veri") {
                                        SettingsActionRow("Cihazlarım ve ortak hesap",
                                            "Telefon ile tablet aynı günlük hakkı ve molayı paylaşsın.") {
                                            context.startActivity(Intent(context, DeviceSyncActivity::class.java))
                                        }
                                        SubtleDivider()
                                        SettingsActionRow(
                                            title = "Yedek oluştur",
                                            description = "Uygulamaları, limitleri ve planları bir dosyaya kaydet.",
                                        ) {
                                            scope.launch {
                                                app.repository.exportRules().onSuccess {
                                                    exportPayload = app.routines.enrichRulesBackup(it)
                                                    rulesExporter.launch("doom-blocker-kurallari.json")
                                                }.onFailure { message = "Kurallar dışa aktarılamadı." }
                                            }
                                        }
                                        SubtleDivider()
                                        SettingsActionRow(
                                            title = "Yedekten geri yükle",
                                            description = "Daha önce kaydettiğin kural ve planları içe aktar.",
                                        ) { rulesImporter.launch(arrayOf("application/json", "text/plain")) }
                                        if (importUndoAvailable) {
                                            SubtleDivider()
                                            SettingsActionRow(
                                                title = "Son geri yüklemeyi geri al",
                                                description = "Önceki kural ve planlarına dön.",
                                            ) {
                                                scope.launch {
                                                    app.repository.undoLastImport().fold(
                                                        onSuccess = {
                                                            app.routines.undoLastImport().fold(
                                                                onSuccess = {
                                                                    importUndoAvailable = false
                                                                    message = "Geri alma kaydedildi. Sayaçlar korundu; gevşetmeler bekleme süresine tabidir."
                                                                },
                                                                onFailure = { message = it.message ?: "Plan profilleri geri alınamadı." },
                                                            )
                                                        },
                                                        onFailure = { message = it.message ?: "İçe aktarma geri alınamadı." },
                                                    )
                                                }
                                            }
                                        }
                                        SubtleDivider()
                                        SettingsActionRow(
                                            title = "Kullanım geçmişini dışa aktar",
                                            description = "Son 30 günü CSV dosyası olarak kaydet.",
                                        ) {
                                            historyPayload = historyCsv(snapshot, now)
                                            historyExporter.launch("doom-blocker-kullanim-gecmisi.csv")
                                        }
                                    }
                                }
                                item {
                                    SettingsGroup("Uygulama") {
                                        SettingsInfoRow(
                                            title = "Doom Blocker",
                                            description = "Sürüm $appVersion · Veriler yalnızca bu cihazda tutulur.",
                                        )
                                        SubtleDivider()
                                        SettingsActionRow(
                                            title = "Gelişmiş ve tanılama",
                                            description = "Servis durumu, teknik olaylar ve sorun giderme.",
                                        ) { showDiagnostics = true }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showHistory && snapshot != null) {
        val historySheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { showHistory = false }, sheetState = historySheetState) {
            UsageHistorySheet(
                snapshot = snapshot,
                now = now,
                installedByPackage = installedByPackage,
                onDismiss = { showHistory = false },
            )
        }
    }
    if (confirmTravelCancel) {
        AlertDialog(
            onDismissRequest = { confirmTravelCancel = false },
            title = { Text("Yolculuk istisnası iptal edilsin mi?") },
            text = { Text("İptal edince korunan uygulamalarda normal sınırlar hemen yeniden uygulanır.") },
            confirmButton = {
                Button(onClick = {
                    confirmTravelCancel = false
                    scope.launch {
                        app.repository.clearTravelException()
                            .onSuccess { message = "Yolculuk istisnası iptal edildi." }
                            .onFailure { message = it.message ?: "Yolculuk istisnası iptal edilemedi." }
                    }
                }) { Text("İptal et") }
            },
            dismissButton = { TextButton(onClick = { confirmTravelCancel = false }) { Text("Vazgeç") } },
        )
    }
    if (showPicker) {
        AppPicker(
            apps = installed.filter { candidate -> snapshot?.apps?.none { it.packageName == candidate.packageName } == true },
            onDismiss = { showPicker = false },
            onSelect = {
                showPicker = false
                editorPackageKey = it.packageName
                editorTarget = PolicyEditorTarget(it, null)
            },
        )
    }
    if (showFocusSession && snapshot != null) {
        FocusSessionSheet(
            apps = snapshot.apps,
            activePackages = snapshot.focusSession
                ?.takeIf { it.block.remainingMillis(now) > 0 }
                ?.packageNames.orEmpty(),
            onDismiss = { showFocusSession = false },
            onStart = { packages, minutes ->
                scope.launch {
                    app.repository.startFocusSession(packages, minutes)
                        .onSuccess {
                            FocusFeedback.perform(context)
                            showFocusSession = false
                            message = "Odak güncellendi. Mevcut daha uzun süre korunur."
                        }
                        .onFailure { message = it.message ?: "Odak modu başlatılamadı." }
                }
            },
        )
    }
    resolvedEditorTarget?.let { target ->
        val defaultPolicy = if (labMode) {
            AppPolicy(Limits(6, 1, 1, timeUnitMillis))
        } else AppPolicy(Limits(unitMillis = timeUnitMillis))
        PolicyDialog(
            title = target.protected?.label ?: target.installed?.label.orEmpty(),
            initial = target.protected?.pendingPolicy?.policy ?: target.protected?.policy ?: defaultPolicy,
            isNew = target.protected == null,
            labMode = labMode,
            copyCandidates = snapshot?.apps.orEmpty().filter { it.packageName != target.protected?.packageName },
            initialIntentionalOpen = target.protected?.let { it.intentionalOpenEnabled && it.intentionalDisableAtWallMillis == null } ?: false,
            onDismiss = { editorTarget = null; editorPackageKey = null },
            onSave = { policy, intentionalOpen ->
                val selected = target.installed
                val protected = target.protected
                scope.launch {
                    val result = if (protected != null) app.repository.updatePolicy(protected.packageName, policy)
                    else if (selected != null) app.repository.add(selected.packageName, selected.label, policy)
                    else Result.failure(IllegalStateException("Uygulama bilgisi bulunamadı."))
                    result.fold(
                        onSuccess = {
                            val packageName = protected?.packageName ?: selected?.packageName
                            if (packageName == null) message = "Uygulama bilgisi bulunamadı."
                            else app.repository.setIntentionalOpen(packageName, intentionalOpen)
                                .onSuccess {
                                    editorTarget = null; editorPackageKey = null
                                    message = if (protected == null) "Koruma başladı."
                                    else "Oturum süresi hemen uygulanır. Günlük limit artışı ve mola kısaltması bekleme kuralına tabidir."
                                }
                                .onFailure { message = it.message ?: "Bilinçli giriş ayarı kaydedilemedi." }
                        },
                        onFailure = { message = it.message ?: "Sınırlar kaydedilemedi." },
                    )
                }
            },
        )
    }
    removeTarget?.let { target ->
        val immediate = snapshot?.strictModeEnabled != true && !target.manualBlocked && target.usedTodayMillis == 0L &&
            snapshot?.let { UsageEngine.view(it, target, now).availability in setOf(AppAvailability.AVAILABLE, AppAvailability.INTENTION_REQUIRED) } == true &&
            target.sessionUsedMillis == 0L && target.cooldown == null
        AlertDialog(
            onDismissRequest = { removeTarget = null },
            title = { Text("${target.label} kaldırılsın mı?") },
            text = {
                Text(
                    if (immediate) "Uygulama koruma listesinden hemen çıkarılır."
                    else if (snapshot?.strictModeEnabled == true) "Koruma 24 saatten ve bir sonraki günden önce kaldırılmaz."
                    else "Bugünkü sınırı sıfırlamamak için koruma yarın kaldırılır.",
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            app.repository.requestRemoval(target.packageName)
                                .onSuccess { removeTarget = null }
                                .onFailure { message = it.message ?: "Uygulama kaldırılamadı." }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text("Kaldır") }
            },
            dismissButton = { TextButton(onClick = { removeTarget = null }) { Text("Vazgeç") } },
        )
    }
    if (showRoutineEditor && snapshot != null) {
        RoutineDialog(
            apps = snapshot.apps,
            existing = resolvedEditingRoutine,
            labMode = labMode,
            unitMillis = timeUnitMillis,
            onDismiss = {
                showRoutineEditor = false
                editingRoutine = null
                editingRoutineId = null
            },
            onSave = { label, packages, limits ->
                app.routines.save(resolvedEditingRoutine?.id, label, packages, limits)
                    .onSuccess {
                        showRoutineEditor = false
                        editingRoutine = null
                        editingRoutineId = null
                        message = "Plan profili kaydedildi."
                    }
                    .onFailure { message = it.message ?: "Plan profili kaydedilemedi." }
            },
        )
    }
    removeRoutine?.let { profile ->
        AlertDialog(
            onDismissRequest = { removeRoutine = null },
            title = { Text("${profile.label} silinsin mi?") },
            text = { Text("Yalnız profil silinir; uygulamaların mevcut limitleri değişmez.") },
            confirmButton = {
                Button(
                    onClick = {
                        app.routines.remove(profile.id)
                            .onSuccess { removeRoutine = null; message = "Plan profili silindi." }
                            .onFailure { message = it.message ?: "Profil silinemedi." }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text("Sil") }
            },
            dismissButton = { TextButton(onClick = { removeRoutine = null }) { Text("Vazgeç") } },
        )
    }
    if (showGroupEditor && snapshot != null) {
        GroupDialog(
            apps = snapshot.apps,
            groups = snapshot.groups,
            existing = resolvedEditingGroup,
            labMode = labMode,
            onDismiss = {
                showGroupEditor = false
                editingGroup = null
                editingGroupId = null
            },
            onSave = { label, packages, daily ->
                scope.launch {
                    val current = resolvedEditingGroup
                    val result = if (current == null) app.repository.addGroup(label, packages, daily)
                    else app.repository.updateGroup(current.id, label, packages, daily)
                    result.onSuccess {
                        showGroupEditor = false
                        editingGroup = null
                        editingGroupId = null
                    }.onFailure { message = it.message ?: "Ortak limit kaydedilemedi." }
                }
            },
        )
    }
    removeGroup?.let { group ->
        val used = snapshot?.apps.orEmpty().filter { it.packageName in group.policy.packageNames }
            .sumOf { target -> snapshot?.let { UsageEngine.view(it, target, now).usedTodayMillis } ?: 0 }
        val immediate = snapshot?.strictModeEnabled != true && used == 0L
        AlertDialog(
            onDismissRequest = { removeGroup = null },
            title = { Text("${group.policy.label} kaldırılsın mı?") },
            text = {
                Text(
                    if (immediate) "Ortak limit hemen kaldırılır; uygulamaların kendi limitleri devam eder."
                    else if (snapshot?.strictModeEnabled == true) "Ortak limit 24 saatten ve bir sonraki günden önce kaldırılmaz."
                    else "Bugünkü ortak kotayı sıfırlamamak için yarın kaldırılır.",
                )
            },
            confirmButton = {
                Button(onClick = {
                    scope.launch {
                        app.repository.requestGroupRemoval(group.id)
                            .onSuccess { removeGroup = null }
                            .onFailure { message = it.message ?: "Ortak limit kaldırılamadı." }
                    }
                }) { Text("Kaldır") }
            },
            dismissButton = { TextButton(onClick = { removeGroup = null }) { Text("Vazgeç") } },
        )
    }
    if (showScheduleEditor && snapshot != null) {
        ScheduleDialog(
            apps = snapshot.apps,
            existing = resolvedEditingSchedule,
            strictMode = snapshot.strictModeEnabled,
            onDismiss = {
                showScheduleEditor = false
                editingSchedule = null
                editingScheduleId = null
            },
            onSave = { label, packages, days, start, end ->
                scope.launch {
                    val current = resolvedEditingSchedule
                    val result = if (current == null) {
                        app.repository.addSchedule(label, packages, days, start, end)
                    } else {
                        app.repository.updateSchedule(current.id, label, packages, days, start, end)
                    }
                    result.onSuccess {
                        showScheduleEditor = false
                        editingSchedule = null
                        editingScheduleId = null
                    }.onFailure { message = it.message ?: "Program kaydedilemedi." }
                }
            },
        )
    }
    removeSchedule?.let { schedule ->
        AlertDialog(
            onDismissRequest = { removeSchedule = null },
            title = { Text("${schedule.policy.label} kaldırılsın mı?") },
            text = {
                Text(
                    if (snapshot?.strictModeEnabled == true) {
                        "Program en az 24 saat ve bir sonraki gün geçmeden kaldırılmaz."
                    } else {
                        "Program bugünkü korumayı gevşetmemek için yarın kaldırılır."
                    },
                )
            },
            confirmButton = {
                Button(onClick = {
                    scope.launch {
                        app.repository.requestScheduleRemoval(schedule.id)
                            .onSuccess { removeSchedule = null }
                            .onFailure { message = it.message ?: "Program kaldırılamadı." }
                    }
                }) { Text("Kaldırmayı planla") }
            },
            dismissButton = { TextButton(onClick = { removeSchedule = null }) { Text("Vazgeç") } },
        )
    }
    strictAction?.let { enabling ->
        AlertDialog(
            onDismissRequest = { strictAction = null },
            title = { Text(if (enabling) "Katı mod açılsın mı?" else "Katı mod kapatılsın mı?") },
            text = {
                Text(
                    if (enabling) "Limit gevşetme, hedef kaldırma ve program değişiklikleri en az 24 saat bekler. Mod hemen açılır."
                    else "Kapatma 24 saat sonra gerçekleşir. Bekleme sırasında katı mod kuralları devam eder.",
                )
            },
            confirmButton = {
                Button(onClick = {
                    scope.launch {
                        val result = if (enabling) app.repository.enableStrictMode() else app.repository.requestStrictModeDisable()
                        result.onSuccess { strictAction = null }
                            .onFailure { message = it.message ?: "Katı mod değiştirilemedi." }
                    }
                }) { Text(if (enabling) "Katı modu aç" else "24 saat sonra kapat") }
            },
            dismissButton = { TextButton(onClick = { strictAction = null }) { Text("Vazgeç") } },
        )
    }
    if (focusLockConfirm) {
        AlertDialog(
            onDismissRequest = { focusLockConfirm = false },
            title = { Text("Tüm hedefler bugünlük kapatılsın mı?") },
            text = { Text("Korunan uygulamalar yarına kadar açılamaz. Bu kilidin geri alma düğmesi yoktur.") },
            confirmButton = {
                Button(onClick = {
                    scope.launch {
                        app.repository.lockAllForToday()
                            .onSuccess { focusLockConfirm = false }
                            .onFailure { message = it.message ?: "Odak kilidi açılamadı." }
                    }
                }) { Text("Yarına kadar kilitle") }
            },
            dismissButton = { TextButton(onClick = { focusLockConfirm = false }) { Text("Vazgeç") } },
        )
    }
    focusLockTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { focusLockTarget = null },
            title = { Text("${target.label} bugünlük kapatılsın mı?") },
            text = { Text("Uygulama yarına kadar açılamaz. Bu kilidin geri alma düğmesi yoktur.") },
            confirmButton = {
                Button(onClick = {
                    scope.launch {
                        app.repository.lockAppForToday(target.packageName)
                            .onSuccess { focusLockTarget = null }
                            .onFailure { message = it.message ?: "Odak kilidi açılamadı." }
                    }
                }) { Text("Yarına kadar kilitle") }
            },
            dismissButton = { TextButton(onClick = { focusLockTarget = null }) { Text("Vazgeç") } },
        )
    }
    emergencyTarget?.let { target ->
        EmergencyAccessDialog(
            target = target,
            onDismiss = { emergencyTarget = null },
            onConfirm = { reason ->
                scope.launch {
                    app.repository.requestEmergencyAccess(target.packageName, reason)
                        .onSuccess {
                            emergencyTarget = null
                            message = "Acil erişim için 60 saniyelik bekleme başladı."
                        }
                        .onFailure { message = it.message ?: "Acil erişim başlatılamadı." }
                }
            },
        )
    }
    importPreview?.let { preview ->
        AlertDialog(
            onDismissRequest = { importPreview = null; pendingImportRaw = null; pendingImportRoutineCount = 0 },
            title = { Text("Yedek içe aktarılsın mı?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(DoomTokens.spaceSm)) {
                    Text("Uygulamalar: ${preview.appsToAdd} yeni · ${preview.appsToUpdate} güncelleme · ${preview.appsToSkip} atlanacak")
                    Text("Ortak limitler: ${preview.groupsToAdd} yeni · ${preview.groupsToUpdate} güncelleme")
                    Text("Programlar: ${preview.schedulesToAdd} yeni · ${preview.schedulesToUpdate} güncelleme")
                    Text("Plan profilleri: $pendingImportRoutineCount")
                    if (preview.enablesStrictMode) Text(
                        "Yedek katı modu açacak.",
                        color = MaterialTheme.colorScheme.tertiary,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "İşlemden önce mevcut kayıt otomatik saklanır ve Ayarlar'dan geri alınabilir.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    val raw = pendingImportRaw ?: return@Button
                    scope.launch {
                        val rules = app.repository.importRules(raw)
                        if (rules.isFailure) {
                            message = rules.exceptionOrNull()?.message ?: "Kurallar içe aktarılamadı."
                            return@launch
                        }
                        val profiles = app.routines.importRulesBackup(raw)
                        if (profiles.isFailure) {
                            app.repository.undoLastImport()
                            message = profiles.exceptionOrNull()?.message ?: "Plan profilleri alınamadı; kural değişiklikleri geri alındı."
                            importPreview = null
                            pendingImportRaw = null
                            pendingImportRoutineCount = 0
                            return@launch
                        }
                        val summary = rules.getOrThrow()
                        importPreview = null
                        pendingImportRaw = null
                        pendingImportRoutineCount = 0
                        importUndoAvailable = true
                        message = "Yedek uygulandı: ${summary.added} yeni, ${summary.updated} güncel, ${summary.skipped} atlandı; ${profiles.getOrThrow()} profil alındı."
                    }
                }) { Text("İçe aktar") }
            },
            dismissButton = {
                TextButton(onClick = { importPreview = null; pendingImportRaw = null; pendingImportRoutineCount = 0 }) { Text("Vazgeç") }
            },
        )
    }
    if (confirmStorageReset) {
        AlertDialog(
            onDismissRequest = { confirmStorageReset = false },
            title = { Text("Koruma kaydı sıfırlansın mı?") },
            text = { Text("Mevcut bozuk kayıt karantinada tutulur. Hedefler ve kurallar sıfırdan oluşturulur.") },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            app.repository.resetStorage()
                                .onSuccess {
                                    confirmStorageReset = false
                                    message = "Koruma kaydı sıfırlandı."
                                }
                                .onFailure { message = it.message ?: "Kayıt sıfırlanamadı." }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text("Sıfırla") }
            },
            dismissButton = { TextButton(onClick = { confirmStorageReset = false }) { Text("Vazgeç") } },
        )
    }
}

@Composable
private fun AppHeader(
    section: HomeSection,
    labMode: Boolean,
    strictMode: Boolean,
    setupComplete: Boolean,
    protectionProblem: Boolean,
    diagnosticsOpen: Boolean,
    onBack: () -> Unit,
) {
    val title = if (diagnosticsOpen) "Tanılama" else when (section) {
        HomeSection.TODAY -> "Bugün"
        HomeSection.PLANS -> "Planlarım"
        HomeSection.SETTINGS -> "Ayarlar"
    }
    val supporting = if (diagnosticsOpen) "Korumanın teknik durumu ve yerel kayıtlar." else when (section) {
        HomeSection.TODAY -> "Sürelerini kontrol et, çalışmaya dön."
        HomeSection.PLANS -> "Çalışma saatlerin ve günlük sınırların."
        HomeSection.SETTINGS -> "Görünüm, koruma ve kişisel tercihlerin."
    }
    Column(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (diagnosticsOpen) TextButton(onClick = onBack, contentPadding = PaddingValues(horizontal = 0.dp)) {
            Text("‹ Ayarlara dön")
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (labMode) "DOOM / LAB" else "DOOM / BLOCKER", Modifier.weight(1f),
                style = MaterialTheme.typography.labelSmall, letterSpacing = 1.6.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!diagnosticsOpen) {
                val color = if (setupComplete && !protectionProblem) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary
                Surface(color = color.copy(alpha = .09f), shape = CircleShape) {
                    Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.size(6.dp).background(color, CircleShape))
                        Text(when { protectionProblem -> "Kontrol gerekli"; !setupComplete -> "Kurulum gerekli"; strictMode -> "Katı mod açık"; else -> "Koruma aktif" },
                            style = MaterialTheme.typography.labelSmall, color = color)
                    }
                }
            }
        }
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Text(supporting, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun DoomNavigationBar(selected: HomeSection, onSelect: (HomeSection) -> Unit) {
    Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 18.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
        Surface(
            modifier = Modifier.widthIn(max = DoomTokens.maxContentWidth).fillMaxWidth(),
            shape = RoundedCornerShape(26.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
            shadowElevation = 8.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .45f)),
        ) {
            Row(Modifier.padding(6.dp).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                HomeSection.entries.forEach { section ->
                    val isSelected = section == selected
                    val background by animateColorAsState(
                        if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLowest,
                        tween(240), label = "nav-background")
                    val foreground by animateColorAsState(
                        if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        tween(240), label = "nav-foreground")
                    val scale by animateFloatAsState(if (isSelected) 1.08f else 1f, tween(220), label = "nav-glyph")
                    Column(
                        Modifier.weight(1f).heightIn(min = 58.dp).clip(RoundedCornerShape(20.dp)).background(background)
                            .selectable(selected = isSelected, onClick = { if (!isSelected) onSelect(section) }, role = Role.Tab)
                            .padding(vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
                    ) {
                        Box(Modifier.graphicsLayer { scaleX = scale; scaleY = scale }) { NavGlyph(section, foreground) }
                        Text(section.label, color = foreground, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun NavGlyph(section: HomeSection, color: Color) {
    Canvas(Modifier.size(22.dp)) {
        val stroke = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round)
        when (section) {
            HomeSection.TODAY -> {
                drawCircle(color = color, radius = size.minDimension * .38f, style = stroke)
                drawLine(color, center, center.copy(y = size.height * .28f), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(color, center, center.copy(x = size.width * .68f), strokeWidth = stroke.width, cap = StrokeCap.Round)
            }
            HomeSection.PLANS -> {
                listOf(.28f, .5f, .72f).forEach { y ->
                    drawCircle(color, radius = 1.6.dp.toPx(), center = center.copy(x = size.width * .2f, y = size.height * y))
                    drawLine(
                        color,
                        start = center.copy(x = size.width * .36f, y = size.height * y),
                        end = center.copy(x = size.width * .82f, y = size.height * y),
                        strokeWidth = stroke.width,
                        cap = StrokeCap.Round,
                    )
                }
            }
            HomeSection.SETTINGS -> {
                drawCircle(color = color, radius = size.minDimension * .28f, style = stroke)
                drawCircle(color = color, radius = size.minDimension * .08f)
                listOf(0f, 90f, 180f, 270f).forEach { degrees ->
                    val radians = Math.toRadians(degrees.toDouble())
                    drawLine(
                        color,
                        start = center.copy(
                            x = center.x + kotlin.math.cos(radians).toFloat() * size.minDimension * .31f,
                            y = center.y + kotlin.math.sin(radians).toFloat() * size.minDimension * .31f,
                        ),
                        end = center.copy(
                            x = center.x + kotlin.math.cos(radians).toFloat() * size.minDimension * .43f,
                            y = center.y + kotlin.math.sin(radians).toFloat() * size.minDimension * .43f,
                        ),
                        strokeWidth = stroke.width,
                        cap = StrokeCap.Round,
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionHeading(title: String, supporting: String? = null) {
    Column(
        Modifier.fillMaxWidth().padding(top = DoomTokens.spaceXs),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        supporting?.let {
            Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun PlanSection(
    title: String,
    supporting: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    SectionSurface {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                    NavGlyph(HomeSection.PLANS, MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Text(supporting, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
        }
        SubtleDivider()
        content()
    }
}

@Composable
private fun TravelExceptionPanel(
    exception: TravelException?,
    nowWallMillis: Long,
    onSchedule: () -> Unit,
    onCancel: () -> Unit,
) {
    val active = exception?.isActive(nowWallMillis) == true
    Text(
        when {
            active -> "Şu anda açık · Tüm korunan uygulamalar 27 Eylül sonuna kadar kullanılabilir."
            exception != null -> "Planlandı · Saat 22.00'de tüm korunan uygulamalarda başlayacak."
            else -> "İptal edildi · İstersen bitiş saatinden önce yeniden planlayabilirsin."
        },
        style = MaterialTheme.typography.bodyMedium,
        color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
    )
    Text(
        "Kullanım süresi kaydedilmeye devam eder. Günlük kota gece yarısında yenilenir; istisna 28 Eylül 00.00'da biter ve normal kurallar geri gelir.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (exception == null) {
        Button(onClick = onSchedule) { Text("Yeniden planla") }
    } else {
        TextButton(onClick = onCancel) { Text("İstisnayı iptal et") }
    }
}

@Composable
private fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(DoomTokens.spaceSm)) {
        Text(
            title,
            Modifier.padding(horizontal = DoomTokens.spaceXs),
            style = MaterialTheme.typography.titleLarge,
        )
        Surface(
            Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
            shape = RoundedCornerShape(DoomTokens.radiusMd),
            tonalElevation = 1.dp,
        ) {
            Column(
                Modifier.padding(horizontal = DoomTokens.spaceLg, vertical = DoomTokens.spaceXs),
                content = content,
            )
        }
    }
}

@Composable
private fun SettingsInfoRow(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 58.dp).padding(vertical = DoomTokens.spaceSm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DoomTokens.spaceMd),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
            Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        trailing?.invoke()
    }
}

@Composable
private fun DisclosureRow(title: String, supporting: String, expanded: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).semantics {
            stateDescription = if (expanded) "Açık" else "Kapalı"
        },
        shape = RoundedCornerShape(DoomTokens.radiusMd),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(supporting, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            Text(if (expanded) "−" else "+", Modifier.padding(start = 12.dp), color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
private fun SettingsActionRow(title: String, description: String, onClick: () -> Unit) {
    SettingsInfoRow(
        title = title,
        description = description,
        modifier = Modifier.clickable(role = Role.Button, onClick = onClick),
        trailing = {
            Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.titleLarge)
        },
    )
}

@Composable
private fun AppearanceSettings(
    preferences: UiPreferenceState,
    onAmoledChange: (Boolean) -> Unit,
    onAccentChange: (AccentOption) -> Unit,
) {
    SettingsInfoRow(
        title = "AMOLED siyah",
        description = "Koyu temada arka planı gerçek siyah yapar.",
        trailing = {
            Switch(
                checked = preferences.amoledBlack,
                onCheckedChange = onAmoledChange,
            )
        },
    )
    SubtleDivider()
    Column(
        Modifier.fillMaxWidth().padding(vertical = DoomTokens.spaceMd),
        verticalArrangement = Arrangement.spacedBy(DoomTokens.spaceSm),
    ) {
        Text("Vurgu rengi", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
        Text(
            "Düğmelerde, ilerleme göstergelerinde ve durum ayrıntılarında kullanılır.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(DoomTokens.spaceSm)) {
            AccentOption.entries.forEach { accent ->
                FilterChip(
                    selected = preferences.accent == accent,
                    onClick = { onAccentChange(accent) },
                    label = { Text(accent.label) },
                    leadingIcon = {
                        val swatch = when (accent) {
                            AccentOption.MINT -> Color(0xFF246B53)
                            AccentOption.BLUE -> Color(0xFF006398)
                            AccentOption.PURPLE -> Color(0xFF67508D)
                        }
                        Box(Modifier.size(14.dp).background(swatch, CircleShape))
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun ProtectionStatusSummary(ready: Boolean, problem: String?) {
    val healthy = ready && problem == null
    SettingsInfoRow(
        title = if (healthy) "Her şey çalışıyor" else "Koruma dikkat istiyor",
        description = if (healthy) "Doom Blocker uygulama sınırlarını uygulamaya hazır."
        else problem ?: "Gerekli izinleri ve servis bağlantısını kontrol et.",
        trailing = {
            Box(
                Modifier.size(10.dp).background(
                    if (healthy) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary,
                    CircleShape,
                ),
            )
        },
    )
}

@Composable
private fun SectionSurface(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shape = RoundedCornerShape(DoomTokens.radiusLg),
        tonalElevation = 1.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.38f)),
    ) {
        Column(
            Modifier.padding(DoomTokens.spaceLg),
            verticalArrangement = Arrangement.spacedBy(DoomTokens.spaceMd),
            content = content,
        )
    }
}

@Composable
private fun LoadingCard() {
    SectionSurface {
        Text("Koruma hazırlanıyor", style = MaterialTheme.typography.titleMedium)
        Text(
            "Kayıtların güvenle okunuyor. Bu işlem genellikle birkaç saniye sürer.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        LinearProgressIndicator(
            modifier = Modifier.fillMaxWidth().height(4.dp),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceContainer,
        )
    }
}

@Composable
private fun RecoveryCard(
    recoveryAvailable: Boolean,
    corruptAvailable: Boolean,
    onRecover: () -> Unit,
    onRestoreRules: () -> Unit,
    onExportCorrupt: () -> Unit,
    onReset: () -> Unit,
) {
    SectionSurface {
        Text("Kurtarma seçenekleri", style = MaterialTheme.typography.titleLarge)
        Text(
            "Bozuk kayıt silinmedi. Önce sağlam kopyayı veya kural yedeğini deneyebilir, gerekirse sıfırdan başlayabilirsin.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = onRecover,
            enabled = recoveryAvailable,
            modifier = Modifier.fillMaxWidth().height(DoomTokens.controlHeight),
        ) { Text("Son sağlam kaydı geri yükle") }
        OutlinedButton(
            onClick = onRestoreRules,
            modifier = Modifier.fillMaxWidth().height(DoomTokens.controlHeight),
        ) { Text("Kural yedeğinden geri dön") }
        OutlinedButton(
            onClick = onExportCorrupt,
            enabled = corruptAvailable,
            modifier = Modifier.fillMaxWidth().height(DoomTokens.controlHeight),
        ) { Text("Bozuk kaydı dışa aktar") }
        TextButton(
            onClick = onReset,
            modifier = Modifier.fillMaxWidth().height(DoomTokens.controlHeight),
        ) { Text("Sıfırdan başlat", color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun ProtectionTestPanel(
    runtimeResult: ProtectionProbeResult?,
    runtimeLabel: String?,
    target: ProtectedApp?,
    enabled: Boolean,
    onRun: (ProtectedApp) -> Unit,
    onClear: () -> Unit,
) {
    Text("Korumayı test et", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
    Text(
        when (runtimeResult) {
            ProtectionProbeResult.RUNNING -> "${runtimeLabel ?: "Uygulama"} açılıyor; korumanın devreye girmesi bekleniyor."
            ProtectionProbeResult.PASSED -> "Home isteğinden sonra hedef uygulamanın odaktan çıktığı görüldü. Bu test günlük süre doğruluğunu tek başına kanıtlamaz."
            ProtectionProbeResult.FAILED -> "Koruma uygulamayı gördü ancak engelleme tamamlanamadı."
            ProtectionProbeResult.TIMED_OUT -> "Koruma yanıt vermedi. İzinleri ve pil kullanımını kontrol et."
            null -> "Doom Blocker'ın uygulama sınırlarını uygulayabildiğini doğrula."
        },
        color = when (runtimeResult) {
            ProtectionProbeResult.PASSED -> MaterialTheme.colorScheme.primary
            ProtectionProbeResult.FAILED, ProtectionProbeResult.TIMED_OUT -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        style = MaterialTheme.typography.bodySmall,
    )
    if (runtimeResult == ProtectionProbeResult.PASSED ||
        runtimeResult == ProtectionProbeResult.FAILED ||
        runtimeResult == ProtectionProbeResult.TIMED_OUT
    ) {
        TextButton(onClick = onClear, modifier = Modifier.fillMaxWidth()) { Text("Sonucu kapat") }
    } else {
        OutlinedButton(
            onClick = { target?.let(onRun) },
            enabled = enabled && target != null && runtimeResult != ProtectionProbeResult.RUNNING,
            modifier = Modifier.fillMaxWidth().height(DoomTokens.controlHeight),
        ) { Text(if (target == null) "Test için uygulama ekle" else "Korumayı test et") }
    }
}

@Composable
private fun TodaySummaryCard(
    snapshot: dev.personal.doomblocker.core.ProtectionSnapshot,
    now: dev.personal.doomblocker.core.ClockReading,
) {
    val views = snapshot.apps.map { UsageEngine.view(snapshot, it, now) }
    val totalLimit = totalDailyBudget(snapshot)
    val totalRemaining = remainingDailyBudget(snapshot, now)
    val unavailable = views.count { it.availability !in setOf(AppAvailability.AVAILABLE, AppAvailability.ACTIVE) }
    TimeBalanceCard(
        remaining = formatUsage(totalRemaining),
        used = formatUsage(views.sumOf { it.usedTodayMillis }),
        appCount = snapshot.apps.size,
        unavailable = unavailable,
        progress = if (totalLimit > 0) totalRemaining.toFloat() / totalLimit else 0f,
    )
}

@Composable
private fun FocusModeCard(
    activeRemainingMillis: Long,
    activeLabels: List<String>,
    enabled: Boolean,
    onStartAll: (Int) -> Unit,
    onChooseApps: () -> Unit,
) {
    val active = activeRemainingMillis > 0
    var duration by rememberSaveable { mutableIntStateOf(30) }
    var choosingDuration by remember { mutableStateOf(false) }
    val background by animateColorAsState(
        if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLowest,
        tween(400), label = "focus-state")
    Surface(
        Modifier.fillMaxWidth().animateContentSize(),
        color = background,
        shape = RoundedCornerShape(DoomTokens.radiusLg),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .35f)),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(if (active) "Odak sürüyor" else "Çalışmaya başla", style = MaterialTheme.typography.titleMedium)
                    Text(if (active) "${formatCountdown(activeRemainingMillis)} kaldı · Süre kısaltılamaz."
                        else "Seçtiğin süre boyunca korunan uygulamalar kapalı kalsın.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (active) {
                Text("Korunanlar: ${activeLabels.joinToString(", ")}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            } else {
                Box(Modifier.align(Alignment.End)) {
                    TextButton(onClick = { choosingDuration = true }, enabled = enabled) { Text("Süre: $duration dk · Değiştir") }
                    DropdownMenu(expanded = choosingDuration, onDismissRequest = { choosingDuration = false }) {
                        listOf(15, 30, 60).forEach { minutes ->
                            DropdownMenuItem(text = { Text("$minutes dakika") }, onClick = { duration = minutes; choosingDuration = false })
                        }
                    }
                }
                PrimaryFocusButton("Odağı başlat · $duration dk", enabled,
                    onClick = { onStartAll(duration) }, modifier = Modifier.fillMaxWidth())
            }
            TextButton(onClick = onChooseApps, enabled = enabled, modifier = Modifier.align(Alignment.CenterHorizontally),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)) {
                SelectionGlyph(MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(if (active) "Süreyi uzat veya uygulama ekle" else "Uygulama seç", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun SelectionGlyph(color: Color) {
    Canvas(Modifier.size(16.dp)) {
        val stroke = 1.6.dp.toPx()
        val dotRadius = 1.4.dp.toPx()
        listOf(.28f, .5f, .72f).forEach { y ->
            drawCircle(color, radius = dotRadius, center = center.copy(x = size.width * .18f, y = size.height * y))
            drawLine(
                color = color,
                start = center.copy(x = size.width * .38f, y = size.height * y),
                end = center.copy(x = size.width * .86f, y = size.height * y),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FocusSessionSheet(
    apps: List<ProtectedApp>,
    activePackages: Set<String>,
    onDismiss: () -> Unit,
    onStart: (Set<String>, Int) -> Unit,
) {
    val packageKey = apps.joinToString("|") { it.packageName }
    val activeKey = activePackages.sorted().joinToString("|")
    var duration by rememberSaveable { mutableIntStateOf(30) }
    var selected by remember(packageKey, activeKey) {
        mutableStateOf<Set<String>>(
            activePackages.takeIf { it.isNotEmpty() }
                ?: apps.mapTo(linkedSetOf()) { it.packageName },
        )
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 680.dp).verticalScroll(rememberScrollState()).navigationBarsPadding().padding(
                start = DoomTokens.spaceXl,
                end = DoomTokens.spaceXl,
                bottom = DoomTokens.space2Xl,
            ),
            verticalArrangement = Arrangement.spacedBy(DoomTokens.spaceMd),
        ) {
            Text("Odak uygulamaları", style = MaterialTheme.typography.headlineSmall)
            Text(
                if (activePackages.isEmpty()) "Bu süre boyunca açılmaması gereken uygulamaları seç."
                else "İstersen yeni uygulamalar ekle veya süreyi uzat. Aktif odak hedefleri çıkarılamaz.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            apps.sortedBy { it.label.lowercase(Locale.getDefault()) }.forEach { target ->
                val alreadyFocused = target.packageName in activePackages
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 52.dp).toggleable(
                        value = target.packageName in selected,
                        enabled = !alreadyFocused,
                        role = Role.Checkbox,
                        onValueChange = { checked ->
                            selected = if (checked) selected + target.packageName else selected - target.packageName
                        },
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = target.packageName in selected,
                        onCheckedChange = null,
                        enabled = !alreadyFocused,
                    )
                    Column(Modifier.padding(start = DoomTokens.spaceSm).weight(1f)) {
                        Text(target.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (alreadyFocused) Text(
                            "Aktif odakta",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
            Text(if (activePackages.isEmpty()) "Süre" else "Yeni kalan süre · Mevcut daha uzun süre korunur.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(DoomTokens.spaceSm)) {
                listOf(15, 30, 60).forEach { minutes ->
                    FilterChip(
                        selected = duration == minutes,
                        onClick = { duration = minutes },
                        modifier = Modifier.weight(1f),
                        label = { Text("$minutes dk") },
                    )
                }
            }
            PrimaryFocusButton(if (activePackages.isEmpty()) "Odağı başlat" else "Odağı güncelle",
                enabled = selected.isNotEmpty(), onClick = { onStart(selected, duration) }, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun FocusLockCard(enabled: Boolean, onLock: () -> Unit) {
    Surface(
        Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shape = RoundedCornerShape(DoomTokens.radiusMd),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
    ) {
        Row(
            Modifier.padding(start = DoomTokens.spaceMd, end = DoomTokens.spaceXs, top = DoomTokens.spaceSm, bottom = DoomTokens.spaceSm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.width(3.dp).height(30.dp).background(
                    MaterialTheme.colorScheme.primary,
                    RoundedCornerShape(DoomTokens.radiusSm),
                ),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "Bugünü tamamen kapat",
                    Modifier.padding(start = DoomTokens.spaceSm),
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    "Tüm hedefler yarına kadar kilitlenir.",
                    Modifier.padding(start = DoomTokens.spaceSm),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            TextButton(onClick = onLock, enabled = enabled) { Text("Tümünü kilitle") }
        }
    }
}

@Composable
private fun SubtleDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun CalmIllustration(modifier: Modifier = Modifier, complete: Boolean = false) {
    FocusOrbit(modifier, progress = if (complete) 1f else .78f)
}

@Composable
private fun SetupCard(
    permissions: PermissionState,
    serviceConnected: Boolean,
    hasTarget: Boolean,
    lastEvaluationWall: Long?,
    nowWall: Long,
    app: BlockerApplication,
) {
    val complete = permissions.usageAccess && permissions.accessibilityEnabled && serviceConnected && hasTarget
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(DoomTokens.radiusLg),
        color = if (complete) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLowest,
        tonalElevation = 1.dp,
    ) {
        Column(Modifier.padding(DoomTokens.spaceLg), verticalArrangement = Arrangement.spacedBy(DoomTokens.spaceMd)) {
            SetupProgress(listOf(permissions.usageAccess, permissions.accessibilityEnabled, serviceConnected, hasTarget).count { it })
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(12.dp).background(
                        if (complete) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary,
                        CircleShape,
                    ),
                )
                Spacer(Modifier.width(DoomTokens.spaceMd))
                Column(Modifier.weight(1f)) {
                    Text(if (complete) "Koruma hazır" else "Kurulumu tamamla", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (complete) "Son kontrol: ${ageText(lastEvaluationWall, nowWall)}"
                        else "İzinleri ver, bir uygulama seç; gerisini bize bırak.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            if (!complete) {
                SetupRow(1, "Kullanım erişimi", "Kesinti sonrasında yalnız uygulama geçişleri ve kullanım süresi tamamlanır.", permissions.usageAccess) {
                    app.startActivity(DeviceAccess.usageSettings(app).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                SetupRow(2, "Erişilebilirlik servisi", "Öndeki uygulamanın paket adı okunur; ekran metni ve tuşlar okunmaz.", permissions.accessibilityEnabled) {
                    app.startActivity(DeviceAccess.accessibilitySettings().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                SetupRow(3, "Servis bağlantısı", "Servis bağlandığında sayaç ve engelleme canlı çalışır.", serviceConnected, null)
                SetupRow(4, "En az bir hedef", "Korunacak ilk uygulamayı Bugün bölümünden seç.", hasTarget, null)
            }
            if (permissions.usageAccess && permissions.accessibilityEnabled && !permissions.batteryExempt) {
                OutlinedButton(
                    onClick = { app.startActivity(DeviceAccess.appSettings(app).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
                    modifier = Modifier.fillMaxWidth().height(DoomTokens.controlHeight),
                ) { Text("Pil kullanımını kısıtlanmamış yap") }
            }
        }
    }
}

@Composable
private fun SetupRow(
    number: Int,
    title: String,
    description: String,
    done: Boolean,
    action: (() -> Unit)?,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            Modifier.size(30.dp),
            shape = CircleShape,
            color = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    if (done) "✓" else number.toString(),
                    color = if (done) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
        Column(Modifier.padding(start = DoomTokens.spaceMd).weight(1f)) {
            Text(
                title,
                color = if (done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (done) FontWeight.Normal else FontWeight.Medium,
            )
            if (!done) Text(
                description,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        if (!done && action != null) TextButton(onClick = action) { Text("Ayarları aç") }
    }
}

@Composable
private fun EmptyCard(enabled: Boolean, onAdd: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = DoomTokens.spaceSm),
        verticalArrangement = Arrangement.spacedBy(DoomTokens.spaceMd),
        horizontalAlignment = Alignment.Start,
    ) {
        CalmIllustration(Modifier.fillMaxWidth().height(112.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DoomTokens.spaceMd)) {
            Surface(Modifier.size(36.dp), shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                Box(contentAlignment = Alignment.Center) {
                    Text("+", color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.titleMedium)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("İlk sınırını belirle", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Seni en çok oyalayan uygulamayı seç ve süresini belirle.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        Button(
            onClick = onAdd,
            enabled = enabled,
            modifier = Modifier.height(DoomTokens.controlHeight),
            shape = RoundedCornerShape(DoomTokens.radiusMd),
        ) {
            Text(if (enabled) "Uygulama seç" else "Önce kurulumu tamamla")
        }
    }
}

@Composable
private fun AddAppButton(enabled: Boolean, onAdd: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        OutlinedButton(
            onClick = onAdd,
            enabled = enabled,
            modifier = Modifier.heightIn(min = 48.dp),
            shape = RoundedCornerShape(DoomTokens.radiusMd),
            contentPadding = PaddingValues(horizontal = DoomTokens.spaceLg),
        ) {
            Text("＋", modifier = Modifier.padding(end = DoomTokens.spaceSm))
            Text("Uygulama ekle")
        }
    }
}

@Composable
private fun TargetSortMenu(selected: TargetSort, onSelect: (TargetSort) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) { Text("Sırala · ${selected.label}") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            TargetSort.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(if (option == selected) "✓  ${option.label}" else option.label) },
                    onClick = { expanded = false; onSelect(option) },
                )
            }
        }
    }
}

@Composable
private fun TargetCard(
    modifier: Modifier = Modifier,
    target: ProtectedApp,
    installed: InstalledApp?,
    view: AppView,
    zoneId: String,
    onIntentionalOpenChange: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onLockToday: () -> Unit,
    emergencyAvailable: Boolean,
    onEmergency: () -> Unit,
    onRemove: () -> Unit,
    onCancelRemoval: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var expanded by rememberSaveable(target.packageName) { mutableStateOf(false) }
    val individualRemaining = (target.limits.dailyMillis - view.usedTodayMillis).coerceAtLeast(0)
    val remainingMillis = if (view.groupLabel != null) minOf(individualRemaining,
        (view.groupLimitMillis - view.groupUsedMillis).coerceAtLeast(0)) else individualRemaining
    val remainingProgress = (remainingMillis.toFloat() / target.limits.dailyMillis).coerceIn(0f, 1f)
    val progressColor = when (view.availability) {
        AppAvailability.DAILY_BLOCKED, AppAvailability.GROUP_BLOCKED,
        AppAvailability.MANUAL_BLOCKED, AppAvailability.SCHEDULE_BLOCKED,
        AppAvailability.FOCUS_BLOCKED -> MaterialTheme.colorScheme.error
        AppAvailability.COOLDOWN, AppAvailability.REMOVAL_PENDING,
        AppAvailability.EMERGENCY_WAIT -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }
    val showLock = !target.manualBlocked
    val showEmergency = emergencyAvailable && view.availability in setOf(
        AppAvailability.DAILY_BLOCKED,
        AppAvailability.GROUP_BLOCKED,
        AppAvailability.MANUAL_BLOCKED,
        AppAvailability.SCHEDULE_BLOCKED,
        AppAvailability.COOLDOWN,
    )
    Surface(
        onClick = { expanded = !expanded },
        modifier = modifier.fillMaxWidth().animateContentSize().semantics {
            stateDescription = if (expanded) "Ayrıntılar açık" else "Ayrıntılar kapalı; dokunarak aç"
        },
        shape = RoundedCornerShape(DoomTokens.radiusLg),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        tonalElevation = 1.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.42f)),
    ) {
        Column(Modifier.padding(DoomTokens.spaceLg), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppProgressIcon(installed, remainingProgress, progressColor)
                Column(Modifier.padding(start = DoomTokens.spaceMd).weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        target.label,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    StatusPill(view)
                }
                if (target.pendingRemoval == null) {
                    Box {
                        IconButton(
                            onClick = { menuExpanded = true },
                            modifier = Modifier.semantics { contentDescription = "${target.label} işlemleri" },
                        ) {
                            Text("•••", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.titleMedium)
                        }
                        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                            DropdownMenuItem(
                                text = { Text(if (target.needsLimitReview) "Limitleri gözden geçir" else "Limiti düzenle") },
                                onClick = { menuExpanded = false; onEdit() },
                            )
                            if (showLock) DropdownMenuItem(
                                text = { Text("Bugün kilitle") },
                                onClick = { menuExpanded = false; onLockToday() },
                            )
                            if (showEmergency) DropdownMenuItem(
                                text = { Text("Acil erişim") },
                                onClick = { menuExpanded = false; onEmergency() },
                            )
                            DropdownMenuItem(
                                text = { Text("Korunanlardan kaldır", color = MaterialTheme.colorScheme.error) },
                                onClick = { menuExpanded = false; onRemove() },
                            )
                        }
                    }
                }
            }

            if (target.needsLimitReview) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.34f),
                    shape = RoundedCornerShape(DoomTokens.radiusSm),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = DoomTokens.spaceSm, end = DoomTokens.spaceXs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(Modifier.size(20.dp), shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
                            Box(contentAlignment = Alignment.Center) {
                                Text("i", color = MaterialTheme.colorScheme.onSecondaryContainer, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        Text(
                            "Limitlerini bir kez gözden geçir",
                            Modifier.weight(1f).padding(horizontal = DoomTokens.spaceSm, vertical = 6.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(onClick = onEdit) { Text("İncele") }
                    }
                }
            }

            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    if (remainingMillis == 0L) "Süre doldu" else formatUsage(remainingMillis),
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleLarge,
                )
                if (remainingMillis > 0L) Text(
                    "kaldı",
                    modifier = Modifier.padding(bottom = 1.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(if (expanded) "Ayrıntıları gizle ⌃" else "Ayrıntılar ⌄",
                color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
            AnimatedVisibility(expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                (if (view.usedTodayMillis == 0L) "Henüz kullanılmadı" else "${formatUsage(view.usedTodayMillis)} kullanıldı") +
                    " · Günlük limit ${formatRule(target.limits.dailyMinutes, target.limits.unitMillis)}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                (if (target.intentionalOpenEnabled) "Oturum süresini her girişte seçiyorsun · "
                    else "${formatRule(target.limits.sessionMinutes, target.limits.unitMillis)} oturum · ") +
                    "${formatRule(target.limits.cooldownMinutes, target.limits.unitMillis)} mola",
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.86f),
                style = MaterialTheme.typography.labelSmall,
            )

            if (target.policy.weekendLimits != null) {
                Text("Hafta sonu programı açık", color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
            }
            view.groupLabel?.let {
                Text(
                    "$it ortak havuzu: ${formatUsage(view.groupUsedMillis)} / ${formatUsage(view.groupLimitMillis)}",
                    color = MaterialTheme.colorScheme.secondary,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                )
            }
            if (view.scheduleRemainingMillis > 0) {
                Text(
                    "${view.scheduleLabel ?: "Program"}: ${formatCountdown(view.scheduleRemainingMillis)} sonra açılır",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                )
            }
            if (view.availability == AppAvailability.ACTIVE) {
                Text(
                    "Bu oturum ${formatUsage(view.sessionUsedMillis)} / ${formatUsage(target.limits.sessionMillis)}",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                )
            }
            if (view.cooldownRemainingMillis > 0) {
                Text(
                    "Mola ${formatCountdown(view.cooldownRemainingMillis)} sonra biter",
                    color = MaterialTheme.colorScheme.tertiary,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                )
            }
            if (view.emergencyWaitRemainingMillis > 0) {
                Text(
                    "Acil erişim ${formatCountdown(view.emergencyWaitRemainingMillis)} sonra başlayacak.",
                    color = MaterialTheme.colorScheme.tertiary,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                )
            }
            if (view.emergencyAccessRemainingMillis > 0) {
                Text(
                    "Acil erişim açık · ${formatCountdown(view.emergencyAccessRemainingMillis)} kaldı",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                )
            }
                    TextButton(onClick = onEdit) { Text("Limitleri düzenle") }
                }
            }
            IntentionalOpenToggle(target.intentionalOpenEnabled, "Değişiklik hemen uygulanır.", onIntentionalOpenChange)
            target.pendingPolicy?.let {
                Text(
                    "Yeni kurallar ${formatDateTime(it.effectiveWallMillis, zoneId)} uygulanacak.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (installed == null) Text("Bu uygulama artık yüklü görünmüyor; kayıt korunuyor.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)

            target.pendingRemoval?.let { pendingRemoval ->
                SubtleDivider()
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${formatDateTime(pendingRemoval.effectiveWallMillis, zoneId)} kaldırılacak",
                        Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                    )
                    TextButton(onClick = onCancelRemoval) { Text("İptal et") }
                }
            }
        }
    }
}

@Composable
private fun RoutineProfilesPanel(
    profiles: List<RoutineProfile>,
    apps: List<ProtectedApp>,
    unitMillis: Long,
    onAdd: () -> Unit,
    onEdit: (RoutineProfile) -> Unit,
    onApply: (RoutineProfile) -> Unit,
    onRemove: (RoutineProfile) -> Unit,
) {
    if (profiles.isEmpty()) {
        Text(
            "Henüz plan profili yok.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    profiles.forEach { profile ->
        val labels = apps.filter { it.packageName in profile.packageNames }.joinToString(" · ") { it.label }
        Surface(
            Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = RoundedCornerShape(DoomTokens.radiusMd),
        ) {
            Column(Modifier.padding(DoomTokens.spaceMd), verticalArrangement = Arrangement.spacedBy(DoomTokens.spaceSm)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(profile.label, style = MaterialTheme.typography.titleMedium)
                        Text(
                            labels.ifBlank { "Korunan hedef bulunamadı" },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Button(onClick = { onApply(profile) }, enabled = labels.isNotBlank()) { Text("Uygula") }
                }
                Text(
                    "${formatRule(profile.dailyMinutes, unitMillis)} günlük · " +
                        "${formatRule(profile.sessionMinutes, unitMillis)} oturum · " +
                        "${formatRule(profile.cooldownMinutes, unitMillis)} mola",
                    color = MaterialTheme.colorScheme.secondary,
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(DoomTokens.spaceSm, Alignment.End)) {
                    TextButton(onClick = { onEdit(profile) }) { Text("Düzenle") }
                    TextButton(onClick = { onRemove(profile) }) { Text("Sil", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
    OutlinedButton(
        onClick = onAdd,
        enabled = apps.isNotEmpty(),
        modifier = Modifier.fillMaxWidth().height(DoomTokens.controlHeight),
    ) { Text("Plan oluştur") }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RoutineDialog(
    apps: List<ProtectedApp>,
    existing: RoutineProfile?,
    labMode: Boolean,
    unitMillis: Long,
    onDismiss: () -> Unit,
    onSave: (String, Set<String>, Limits) -> Unit,
) {
    var label by rememberSaveable(existing?.id) { mutableStateOf(existing?.label ?: "Çalışma günü") }
    var selectedPackages by rememberSaveable(existing?.id) {
        mutableStateOf(existing?.packageNames?.toList() ?: apps.map { it.packageName })
    }
    var numbers by rememberSaveable(existing?.id, stateSaver = PolicyNumbersSaver) {
        mutableStateOf(
            existing?.let { PolicyNumbers(it.dailyMinutes.toString(), it.sessionMinutes.toString(), it.cooldownMinutes.toString()) }
                ?: presetLimits("Dengeli", labMode, unitMillis).numbers(),
        )
    }
    val selected = selectedPackages.toSet()
    val limits = numbers.limits(unitMillis)
    val valid = label.isNotBlank() && selected.isNotEmpty() && limits != null

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().heightIn(max = 680.dp)
                .padding(horizontal = DoomTokens.spaceXl),
            verticalArrangement = Arrangement.spacedBy(DoomTokens.spaceMd),
        ) {
            Text(
                if (existing == null) "Plan profili oluştur" else "Plan profilini düzenle",
                style = MaterialTheme.typography.headlineSmall,
            )
            LazyColumn(
                Modifier.weight(1f, fill = false),
                verticalArrangement = Arrangement.spacedBy(DoomTokens.spaceMd),
            ) {
                item {
                    OutlinedTextField(
                        value = label,
                        onValueChange = { label = it.take(40) },
                        label = { Text("Profil adı") },
                        isError = label.isBlank(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                items(listOf("Çalışma günü", "Akşam", "Hafta sonu", "Tatil", "Derin çalışma").chunked(3)) { names ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(DoomTokens.spaceXs)) {
                        names.forEach { name ->
                            FilterChip(
                                selected = label == name,
                                onClick = { label = name },
                                label = { Text(name, maxLines = 1) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        repeat(3 - names.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(DoomTokens.spaceXs)) {
                        listOf("Sıkı", "Dengeli", "Hafif").forEach { preset ->
                            OutlinedButton(
                                onClick = { numbers = presetLimits(preset, labMode, unitMillis).numbers() },
                                contentPadding = PaddingValues(DoomTokens.spaceSm),
                                modifier = Modifier.weight(1f),
                            ) { Text(preset, maxLines = 1) }
                        }
                    }
                }
                item { PolicyFields(numbers) { numbers = it } }
                item {
                    Text(
                        if (selected.isEmpty()) "En az bir uygulama seçin" else "Uygulamalar · ${selected.size} seçildi",
                        color = if (selected.isEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { selectedPackages = apps.map { it.packageName } }) { Text("Tümünü seç") }
                        TextButton(onClick = { selectedPackages = emptyList() }) { Text("Temizle") }
                    }
                }
                items(apps.sortedBy { it.label.lowercase(Locale.getDefault()) }, key = { "routine-${it.packageName}" }) { target ->
                    val checked = target.packageName in selected
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(
                            value = checked,
                            role = Role.Checkbox,
                            onValueChange = { value ->
                                selectedPackages = if (value) (selectedPackages + target.packageName).distinct()
                                else selectedPackages - target.packageName
                            },
                        ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = checked, onCheckedChange = null)
                        Text(target.label)
                    }
                }
                item {
                    Text(
                        "Profil uygulanırken sıkılaştırmalar hemen, gevşetmeler mevcut koruma kuralına göre ertelenir.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(bottom = DoomTokens.spaceLg),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) { Text("Vazgeç") }
                Button(onClick = { onSave(label.trim(), selected, limits!!) }, enabled = valid) { Text("Kaydet") }
            }
        }
    }
}

@Composable
private fun GroupsPanel(
    snapshot: dev.personal.doomblocker.core.ProtectionSnapshot,
    now: dev.personal.doomblocker.core.ClockReading,
    onAdd: () -> Unit,
    onEdit: (ProtectedGroup) -> Unit,
    onRemove: (ProtectedGroup) -> Unit,
    onCancelRemoval: (ProtectedGroup) -> Unit,
) {
    val reserved = snapshot.groups.flatMapTo(mutableSetOf()) {
        it.pendingPolicy?.policy?.packageNames ?: it.policy.packageNames
    }
    if (snapshot.groups.isEmpty()) {
        Text(
            "Henüz ortak limit yok.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    snapshot.groups.forEach { group ->
        val members = snapshot.apps.filter { it.packageName in group.policy.packageNames }
        val firstMember = members.firstOrNull()
        val view = firstMember?.let { UsageEngine.view(snapshot, it, now) }
        val used = view?.groupUsedMillis ?: members.sumOf { it.usedTodayMillis }
        val limit = group.policy.dailyMillis
        Surface(
            Modifier.fillMaxWidth().animateContentSize(),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = RoundedCornerShape(DoomTokens.radiusMd),
        ) {
            Column(Modifier.padding(DoomTokens.spaceMd), verticalArrangement = Arrangement.spacedBy(DoomTokens.spaceSm)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(group.policy.label, style = MaterialTheme.typography.titleMedium)
                        Text(
                            members.joinToString(" · ") { it.label },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    TextButton(onClick = { onEdit(group) }, enabled = group.pendingRemoval == null) { Text("Düzenle") }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Bugün ${formatUsage(used)}", fontWeight = FontWeight.SemiBold)
                    Text("${formatUsage((limit - used).coerceAtLeast(0))} kaldı", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                LinearProgressIndicator(
                    progress = { (used.toFloat() / limit).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                    color = if (used >= limit) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                )
                Text("Ortak günlük sınır: ${formatRule(group.policy.dailyMinutes, group.policy.unitMillis)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                group.pendingPolicy?.let {
                    Text(
                        "Yeni havuz kuralı ${formatDateTime(it.effectiveWallMillis, snapshot.quotaZoneId)} uygulanacak.",
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                group.pendingRemoval?.let {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${formatDateTime(it.effectiveWallMillis, snapshot.quotaZoneId)} kaldırılacak",
                            Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(onClick = { onCancelRemoval(group) }) { Text("İptal et") }
                    }
                } ?: TextButton(onClick = { onRemove(group) }, modifier = Modifier.align(Alignment.End)) {
                    Text("Ortak limiti kaldır", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
    val canAdd = snapshot.apps.count { it.packageName !in reserved } >= 2
    OutlinedButton(
        onClick = onAdd,
        enabled = canAdd,
        modifier = Modifier.fillMaxWidth().height(DoomTokens.controlHeight),
    ) {
        Text("Ortak limit oluştur")
    }
    if (!canAdd) Text(
        "Yeni bir havuz için başka bir havuzda bulunmayan en az iki korunan uygulama gerekir.",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelSmall,
    )
}

private val ScheduleDays = listOf(
    1 to "Pazartesi",
    2 to "Salı",
    4 to "Çarşamba",
    8 to "Perşembe",
    16 to "Cuma",
    32 to "Cumartesi",
    64 to "Pazar",
)

@Composable
private fun SchedulesPanel(
    snapshot: dev.personal.doomblocker.core.ProtectionSnapshot,
    now: dev.personal.doomblocker.core.ClockReading,
    onAdd: () -> Unit,
    onEdit: (ProtectedSchedule) -> Unit,
    onRemove: (ProtectedSchedule) -> Unit,
    onCancelRemoval: (ProtectedSchedule) -> Unit,
) {
    if (snapshot.schedules.isEmpty()) {
        Text(
            "Henüz odak programı yok.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    val trustedWall = UsageEngine.trustedWall(snapshot, now)
    snapshot.schedules.forEach { schedule ->
        val policy = schedule.policy
        val active = policy.isActive(trustedWall, snapshot.quotaZoneId)
        val members = snapshot.apps.filter { it.packageName in policy.packageNames }
        Surface(
            Modifier.fillMaxWidth().animateContentSize(),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = RoundedCornerShape(DoomTokens.radiusMd),
        ) {
            Column(Modifier.padding(DoomTokens.spaceMd), verticalArrangement = Arrangement.spacedBy(DoomTokens.spaceSm)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(policy.label, style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (active) "Şu anda etkin · ${formatCountdown(policy.activeRemainingMillis(trustedWall, snapshot.quotaZoneId))} kaldı"
                            else "Şu anda etkin değil",
                            color = if (active) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                    TextButton(onClick = { onEdit(schedule) }, enabled = schedule.pendingRemoval == null) {
                        Text("Düzenle")
                    }
                }
                Text(
                    members.joinToString(" · ") { it.label },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${formatScheduleDays(policy.daysMask)} · ${formatMinute(policy.startMinuteOfDay)}–${formatMinute(policy.endMinuteOfDay)}",
                    color = MaterialTheme.colorScheme.secondary,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                )
                schedule.pendingPolicy?.let {
                    Text(
                        "Düzenleme ${formatDateTime(it.effectiveWallMillis, snapshot.quotaZoneId)} uygulanacak.",
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                schedule.pendingRemoval?.let {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${formatDateTime(it.effectiveWallMillis, snapshot.quotaZoneId)} kaldırılacak",
                            Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(onClick = { onCancelRemoval(schedule) }) { Text("İptal et") }
                    }
                } ?: TextButton(onClick = { onRemove(schedule) }, modifier = Modifier.align(Alignment.End)) {
                    Text("Programı kaldır", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
    OutlinedButton(
        onClick = onAdd,
        enabled = snapshot.apps.isNotEmpty(),
        modifier = Modifier.fillMaxWidth().height(DoomTokens.controlHeight),
    ) {
        Text("Program oluştur")
    }
    if (snapshot.apps.isEmpty()) Text(
        "Program oluşturmak için önce korunan bir uygulama ekleyin.",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelSmall,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScheduleDialog(
    apps: List<ProtectedApp>,
    existing: ProtectedSchedule?,
    strictMode: Boolean,
    onDismiss: () -> Unit,
    onSave: (String, Set<String>, Int, Int, Int) -> Unit,
) {
    val initial = existing?.pendingPolicy?.policy ?: existing?.policy
    var label by rememberSaveable(existing?.id) { mutableStateOf(initial?.label ?: "Odak zamanı") }
    var selectedPackages by rememberSaveable(existing?.id) {
        mutableStateOf((initial?.packageNames ?: setOf(apps.first().packageName)).toList())
    }
    var daysMask by rememberSaveable(existing?.id) { mutableIntStateOf(initial?.daysMask ?: 31) }
    var startMinute by rememberSaveable(existing?.id) { mutableIntStateOf(initial?.startMinuteOfDay ?: 22 * 60) }
    var endMinute by rememberSaveable(existing?.id) { mutableIntStateOf(initial?.endMinuteOfDay ?: 7 * 60) }
    var editingTime by rememberSaveable(existing?.id) { mutableStateOf<String?>(null) }
    val selected = selectedPackages.toSet()
    val valid = label.isNotBlank() && selected.isNotEmpty() && daysMask != 0 && startMinute != endMinute

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().heightIn(max = 680.dp)
                .padding(horizontal = DoomTokens.spaceXl),
            verticalArrangement = Arrangement.spacedBy(DoomTokens.spaceMd),
        ) {
            Text(
                if (existing == null) "Program oluştur" else "Programı düzenle",
                style = MaterialTheme.typography.headlineSmall,
            )
            LazyColumn(
                Modifier.weight(1f, fill = false),
                verticalArrangement = Arrangement.spacedBy(DoomTokens.spaceMd),
            ) {
                if (existing == null) item {
                    Text("Hazır çalışma saatleri", style = MaterialTheme.typography.titleMedium)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            label = "Hafta içi çalışma"; daysMask = 31; startMinute = 9 * 60; endMinute = 18 * 60
                            selectedPackages = apps.map { it.packageName }
                        }, modifier = Modifier.weight(1f)) { Text("09–18 · Hafta içi") }
                        OutlinedButton(onClick = {
                            label = "Yoğun çalışma"; daysMask = 127; startMinute = 9 * 60; endMinute = 19 * 60
                            selectedPackages = apps.map { it.packageName }
                        }, modifier = Modifier.weight(1f)) { Text("09–19 · Her gün") }
                    }
                    Text("Saatleri ve uygulamaları aşağıdan değiştirebilirsin. Kaydettiğinde etkin saat aralığındaysan engel hemen başlar.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
                item {
                    OutlinedTextField(
                        value = label,
                        onValueChange = { label = it.take(40) },
                        label = { Text("Program adı") },
                        isError = label.isBlank(),
                        supportingText = if (label.isBlank()) {
                            { Text("Programa kısa bir ad verin.") }
                        } else null,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    Text(
                        if (selected.isEmpty()) "En az bir uygulama seçin" else "Engellenecek uygulamalar · ${selected.size} seçildi",
                        color = if (selected.isEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { selectedPackages = apps.map { it.packageName } }) { Text("Tümünü seç") }
                        TextButton(onClick = { selectedPackages = emptyList() }) { Text("Temizle") }
                    }
                }
                items(apps.sortedBy { it.label.lowercase(Locale.getDefault()) }, key = { "schedule-${it.packageName}" }) { target ->
                    val checked = target.packageName in selected
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(
                            value = checked,
                            role = Role.Checkbox,
                            onValueChange = { value ->
                                selectedPackages = if (value) {
                                    (selectedPackages + target.packageName).distinct()
                                } else selectedPackages - target.packageName
                            },
                        ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = null,
                        )
                        Text(target.label)
                    }
                }
                item { HorizontalDivider() }
                item {
                    Text(
                        if (daysMask == 0) "En az bir gün seçin" else "Etkin günler",
                        color = if (daysMask == 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(DoomTokens.spaceXs)) {
                        listOf("Hafta içi" to 31, "Hafta sonu" to 96, "Her gün" to 127).forEach { (name, mask) ->
                            FilterChip(
                                selected = daysMask == mask,
                                onClick = { daysMask = mask },
                                label = { Text(name, maxLines = 1) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
                items(ScheduleDays.chunked(4), key = { row -> "days-${row.first().first}" }) { rowDays ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(DoomTokens.spaceXs)) {
                        rowDays.forEach { (bit, day) ->
                            FilterChip(
                                selected = daysMask and bit != 0,
                                onClick = { daysMask = if (daysMask and bit != 0) daysMask and bit.inv() else daysMask or bit },
                                label = { Text(day.take(3)) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        repeat(4 - rowDays.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(DoomTokens.spaceSm)) {
                        OutlinedButton(
                            onClick = { editingTime = "start" },
                            modifier = Modifier.weight(1f),
                        ) { Text("Başlangıç · ${formatMinute(startMinute)}") }
                        OutlinedButton(
                            onClick = { editingTime = "end" },
                            modifier = Modifier.weight(1f),
                        ) { Text("Bitiş · ${formatMinute(endMinute)}") }
                    }
                }
                item {
                    Text(
                        if (startMinute == endMinute) "Başlangıç ve bitiş farklı olmalı."
                        else "Özet: ${formatScheduleDays(daysMask)} · ${formatMinute(startMinute)}–${formatMinute(endMinute)}" +
                            if (endMinute < startMinute) " · gece yarısını aşar" else "",
                        color = if (startMinute == endMinute) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (existing != null) item {
                    Text(
                        if (strictMode) "Düzenleme en az 24 saat ve bir sonraki gün geçince uygulanır."
                        else "Düzenleme mevcut korumayı gevşetmemek için yarın uygulanır.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(bottom = DoomTokens.spaceLg),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) { Text("Vazgeç") }
                Button(
                    onClick = { onSave(label.trim(), selected, daysMask, startMinute, endMinute) },
                    enabled = valid,
                ) { Text("Kaydet") }
            }
        }
    }
    editingTime?.let { field ->
        ClockTimePickerDialog(
            title = if (field == "start") "Başlangıç saatini seç" else "Bitiş saatini seç",
            initialMinute = if (field == "start") startMinute else endMinute,
            onDismiss = { editingTime = null },
            onConfirm = { minute ->
                if (field == "start") startMinute = minute else endMinute = minute
                editingTime = null
            },
        )
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun ClockTimePickerDialog(
    title: String,
    initialMinute: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    val state = rememberTimePickerState(
        initialHour = initialMinute / 60,
        initialMinute = initialMinute % 60,
        is24Hour = true,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { TimePicker(state = state) } },
        confirmButton = {
            Button(onClick = { onConfirm(state.hour * 60 + state.minute) }) { Text("Seç") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Vazgeç") } },
    )
}

@Composable
private fun StrictModePanel(
    enabled: Boolean,
    disableAtWall: Long?,
    zoneId: String,
    onAction: (Boolean) -> Unit,
) {
    SettingsInfoRow(
        title = "24 saatlik katı mod",
        description = when {
            disableAtWall != null -> "${formatDateTime(disableAtWall, zoneId)} kapanacak"
            enabled -> "Koruma gevşetmeleri en az 24 saat bekler."
            else -> "Gevşetmeler standart modda ertesi gün uygulanır."
        },
        trailing = {
            Switch(checked = enabled, onCheckedChange = { onAction(it) }, enabled = disableAtWall == null)
        },
    )
}

@Composable
private fun NotificationSettingsPanel(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    SettingsInfoRow(
        title = "Limit yaklaşma uyarıları",
        description = "%80, %95, son dakika ve engelleme anında bildirim al.",
        modifier = Modifier.toggleable(
            value = enabled,
            role = Role.Switch,
            onValueChange = onToggle,
        ),
        trailing = { Switch(checked = enabled, onCheckedChange = null) },
    )
}

@Composable
private fun DiagnosticsPanel(
    events: List<DiagnosticEvent>,
    connected: Boolean,
    foreground: String?,
    lastEvaluationWall: Long?,
    homeRequests: Int,
    lastBlocked: String?,
    problem: String?,
    pictureInPicture: Boolean,
    zoneId: String,
    onClear: () -> Unit,
    onExport: () -> Unit,
) {
    Surface(
        Modifier.fillMaxWidth(),
        color = if (connected && problem == null) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(DoomTokens.radiusMd),
    ) {
        Row(
            Modifier.padding(DoomTokens.spaceMd),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DoomTokens.spaceMd),
        ) {
            Box(
                Modifier.size(10.dp).background(
                    if (connected && problem == null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    CircleShape,
                ),
            )
            Column(Modifier.weight(1f)) {
                Text(
                    if (connected) "Servis bağlı" else "Servis bağlı değil",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    "Son kontrol: ${formatDateTime(lastEvaluationWall, zoneId)}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
    MetricGrid(
        "Home isteği" to homeRequests.toString(),
        "Kayıt" to events.size.toString(),
        "PiP" to if (pictureInPicture) "Açık" else "Kapalı",
    )
    Text(
        "Ön plan: ${foreground ?: "—"}",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
    )
    lastBlocked?.let {
        Text("Son engellenen paket: $it", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    }
    problem?.let {
        Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(DoomTokens.radiusSm)) {
            Text(it, Modifier.fillMaxWidth().padding(DoomTokens.spaceMd), color = MaterialTheme.colorScheme.onErrorContainer)
        }
    }
    if (pictureInPicture) Text(
        "PiP penceresi algılandı; bu görünümde koruma tam çalışmayabilir.",
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall,
    )
    SubtleDivider()
    Text("Son teknik olaylar", style = MaterialTheme.typography.titleMedium)
    if (events.isEmpty()) Text("Henüz teknik olay kaydı yok.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    events.takeLast(12).asReversed().forEach { event ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(DoomTokens.spaceSm)) {
            Box(Modifier.padding(top = 6.dp).size(6.dp).background(MaterialTheme.colorScheme.outline, CircleShape))
            Column(Modifier.weight(1f)) {
                Text("${formatEventTime(event.wallMillis, zoneId)} · ${event.kind}", style = MaterialTheme.typography.labelSmall)
                Text(
                    listOfNotNull(event.packageName, event.message).joinToString(" · "),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(DoomTokens.spaceSm)) {
        OutlinedButton(
            onClick = onExport,
            modifier = Modifier.weight(1f).height(DoomTokens.controlHeight),
        ) { Text("Günlüğü kaydet") }
        TextButton(
            onClick = onClear,
            modifier = Modifier.weight(1f).height(DoomTokens.controlHeight),
        ) { Text("Temizle") }
    }
    Text(
        "Günlük yalnız cihazda tutulur; paket adı ve teknik olay içerir, ekran metni içermez.",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelSmall,
    )
}

@Composable
private fun Metric(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(modifier, color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(DoomTokens.radiusSm)) {
        Column(Modifier.padding(DoomTokens.spaceMd), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
            Text(value, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun MetricGrid(vararg values: Pair<String, String>) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth < 340.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(DoomTokens.spaceSm)) {
                values.forEach { (label, value) -> Metric(label, value, Modifier.fillMaxWidth()) }
            }
        } else {
            val columns = if (maxWidth < 560.dp) 2 else 3
            Column(verticalArrangement = Arrangement.spacedBy(DoomTokens.spaceSm)) {
                values.asList().chunked(columns).forEach { rowValues ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(DoomTokens.spaceSm)) {
                        rowValues.forEach { (label, value) -> Metric(label, value, Modifier.weight(1f)) }
                        repeat(columns - rowValues.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

private enum class NoticeTone { Info, Success, Warning, Error }

@Composable
private fun NoticeCard(
    message: String,
    tone: NoticeTone,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val container = when (tone) {
        NoticeTone.Info, NoticeTone.Success -> MaterialTheme.colorScheme.primaryContainer
        NoticeTone.Warning -> MaterialTheme.colorScheme.tertiaryContainer
        NoticeTone.Error -> MaterialTheme.colorScheme.errorContainer
    }
    val foreground = when (tone) {
        NoticeTone.Info, NoticeTone.Success -> MaterialTheme.colorScheme.onPrimaryContainer
        NoticeTone.Warning -> MaterialTheme.colorScheme.onTertiaryContainer
        NoticeTone.Error -> MaterialTheme.colorScheme.onErrorContainer
    }
    Surface(
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        color = container,
        shape = RoundedCornerShape(DoomTokens.radiusMd),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = DoomTokens.spaceLg, end = DoomTokens.spaceSm, top = DoomTokens.spaceMd, bottom = DoomTokens.spaceMd),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(7.dp).background(foreground, CircleShape))
            Spacer(Modifier.width(DoomTokens.spaceMd))
            Text(message, Modifier.weight(1f), color = foreground, style = MaterialTheme.typography.bodySmall)
            if (actionLabel != null && onAction != null) {
                TextButton(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}

@Composable
private fun AppPicker(apps: List<InstalledApp>, onDismiss: () -> Unit, onSelect: (InstalledApp) -> Unit) {
    var search by rememberSaveable { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(DoomTokens.radiusXl),
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
            tonalElevation = 3.dp,
        ) {
            Column(
                Modifier.fillMaxWidth().fillMaxHeight(0.88f).padding(DoomTokens.spaceXl),
                verticalArrangement = Arrangement.spacedBy(DoomTokens.spaceMd),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(DoomTokens.spaceXs)) {
                    Text("Uygulama seç", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "Sınır koymak istediğin uygulamayı bul.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                OutlinedTextField(
                    search,
                    { search = it },
                    label = { Text("Uygulamalarda ara") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Telefon, klavye, ana ekran ve kritik sistem uygulamaları güvenlik için listelenmez.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                val filtered = apps.filter { it.label.contains(search, true) || it.packageName.contains(search, true) }
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(DoomTokens.spaceXs)) {
                    if (filtered.isEmpty()) item {
                        Surface(
                            Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            shape = RoundedCornerShape(DoomTokens.radiusMd),
                        ) {
                            Text(
                                if (search.isBlank()) "Eklenebilecek uygulama bulunamadı." else "“$search” ile eşleşen uygulama yok.",
                                Modifier.padding(DoomTokens.spaceLg),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    items(filtered, key = { it.packageName }) { item ->
                        TextButton(
                            onClick = { onSelect(item) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
                            shape = RoundedCornerShape(DoomTokens.radiusMd),
                            contentPadding = PaddingValues(DoomTokens.spaceSm),
                        ) {
                            AppIcon(item)
                            Column(Modifier.padding(start = DoomTokens.spaceMd).weight(1f), horizontalAlignment = Alignment.Start) {
                                Text(item.label, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium)
                                Text(item.packageName, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp),
                ) { Text("Vazgeç") }
            }
        }
    }
}

private data class PolicyNumbers(
    val daily: String,
    val session: String,
    val cooldown: String,
) {
    fun limits(unitMillis: Long, chosenAtEntry: Boolean = false): Limits? {
        val d = daily.toIntOrNull() ?: return null
        val s = if (chosenAtEntry) (session.toIntOrNull() ?: 5).coerceAtLeast(1).coerceAtMost(d)
            else session.toIntOrNull() ?: return null
        val c = cooldown.toIntOrNull() ?: return null
        return runCatching { Limits(d, s, c, unitMillis) }.getOrNull()
    }
}

private val PolicyNumbersSaver = listSaver<PolicyNumbers, String>(
    save = { listOf(it.daily, it.session, it.cooldown) },
    restore = { PolicyNumbers(it[0], it[1], it[2]) },
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupDialog(
    apps: List<ProtectedApp>,
    groups: List<ProtectedGroup>,
    existing: ProtectedGroup?,
    labMode: Boolean,
    onDismiss: () -> Unit,
    onSave: (String, Set<String>, Int) -> Unit,
) {
    val initial = existing?.pendingPolicy?.policy ?: existing?.policy
    var label by rememberSaveable(existing?.id) { mutableStateOf(initial?.label ?: "Sosyal medya") }
    var daily by rememberSaveable(existing?.id) {
        mutableStateOf((initial?.dailyMinutes ?: if (labMode) 12 else 30).toString())
    }
    var selectedPackages by rememberSaveable(existing?.id) { mutableStateOf(initial?.packageNames.orEmpty().toList()) }
    val selected = selectedPackages.toSet()
    val reservedByOthers = groups.asSequence().filter { it.id != existing?.id }
        .flatMap { (it.pendingPolicy?.policy?.packageNames ?: it.policy.packageNames).asSequence() }
        .toSet()
    val dailyValue = daily.toIntOrNull()
    val valid = label.isNotBlank() && selected.size >= 2 && dailyValue != null && dailyValue in 1..1_440
    val unit = if (labMode) "birim · 1 birim = 10 sn" else "dakika"

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().heightIn(max = 680.dp)
                .padding(horizontal = DoomTokens.spaceXl),
            verticalArrangement = Arrangement.spacedBy(DoomTokens.spaceMd),
        ) {
            Text(
                if (existing == null) "Ortak limit oluştur" else "Ortak limiti düzenle",
                style = MaterialTheme.typography.headlineSmall,
            )
            LazyColumn(
                Modifier.weight(1f, fill = false),
                verticalArrangement = Arrangement.spacedBy(DoomTokens.spaceMd),
            ) {
                item {
                    OutlinedTextField(
                        value = label,
                        onValueChange = { label = it.take(40) },
                        label = { Text("Havuz adı") },
                        isError = label.isBlank(),
                        supportingText = if (label.isBlank()) {
                            { Text("Ortak limite kısa bir ad verin.") }
                        } else null,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    OutlinedTextField(
                        value = daily,
                        onValueChange = { daily = cleanNumber(it) },
                        label = { Text("Ortak günlük sınır · $unit") },
                        isError = dailyValue == null || dailyValue !in 1..1_440,
                        supportingText = if (dailyValue == null || dailyValue !in 1..1_440) {
                            { Text("1–1440 arasında bir değer girin.") }
                        } else null,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    Text(
                        if (selected.size < 2) "En az iki uygulama seçin · ${selected.size}/2" else "Uygulamalar · ${selected.size} seçildi",
                        color = if (selected.size == 1) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                items(apps.sortedBy { it.label.lowercase(Locale.getDefault()) }, key = { it.packageName }) { target ->
                    val available = target.packageName !in reservedByOthers
                    val checked = target.packageName in selected
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(
                            value = checked,
                            enabled = available,
                            role = Role.Checkbox,
                            onValueChange = { value ->
                                selectedPackages = if (value) {
                                    (selectedPackages + target.packageName).distinct()
                                } else selectedPackages - target.packageName
                            },
                        ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = null,
                            enabled = available,
                        )
                        Column(Modifier.weight(1f)) {
                            Text(target.label, color = if (available) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                            if (!available) Text("Başka bir ortak limitte", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                if (existing != null) item {
                    Text(
                        "Limiti azaltmak veya uygulama eklemek hemen uygulanır. Limiti artırmak ya da uygulama çıkarmak ertelenir.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(bottom = DoomTokens.spaceLg),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) { Text("Vazgeç") }
                Button(
                    onClick = { onSave(label.trim(), selected, dailyValue!!) },
                    enabled = valid,
                ) { Text("Kaydet") }
            }
        }
    }
}

@Composable
private fun IntentionalOpenToggle(enabled: Boolean, description: String, onChange: (Boolean) -> Unit) {
    Surface(
        color = if (enabled) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 64.dp)
                .toggleable(value = enabled, role = Role.Switch, onValueChange = onChange)
                .padding(horizontal = DoomTokens.spaceMd, vertical = DoomTokens.spaceSm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DoomTokens.spaceMd),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Bilinçli giriş · ${if (enabled) "Açık" else "Kapalı"}",
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(description, style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = enabled, onCheckedChange = null)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PolicyDialog(
    title: String,
    initial: AppPolicy,
    isNew: Boolean,
    labMode: Boolean,
    copyCandidates: List<ProtectedApp>,
    initialIntentionalOpen: Boolean,
    onDismiss: () -> Unit,
    onSave: (AppPolicy, Boolean) -> Unit,
) {
    var weekday by rememberSaveable(title, stateSaver = PolicyNumbersSaver) {
        mutableStateOf(initial.weekdayLimits.numbers())
    }
    var weekendEnabled by rememberSaveable(title) { mutableStateOf(initial.weekendLimits != null) }
    var weekend by rememberSaveable(title, stateSaver = PolicyNumbersSaver) {
        mutableStateOf((initial.weekendLimits ?: initial.weekdayLimits).numbers())
    }
    var advanced by rememberSaveable(title) { mutableStateOf(false) }
    var intentionalOpen by rememberSaveable(title) { mutableStateOf(initialIntentionalOpen) }
    val weekdayLimits = weekday.limits(initial.weekdayLimits.unitMillis, intentionalOpen)
    val weekendLimits = weekend.limits(initial.weekdayLimits.unitMillis, intentionalOpen)
    val valid = weekdayLimits != null && (!weekendEnabled || weekendLimits != null)
    val suffix = if (labMode) "birim · 1 birim = 10 sn" else "dakika"

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().imePadding().navigationBarsPadding().heightIn(max = 680.dp)
                .padding(horizontal = DoomTokens.spaceXl),
            verticalArrangement = Arrangement.spacedBy(DoomTokens.spaceMd),
        ) {
            Column {
                Text(if (isNew) "Sınır belirle" else "Kuralları düzenle", style = MaterialTheme.typography.headlineSmall)
                Text(title, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            }
            LazyColumn(
                Modifier.weight(1f, fill = false),
                verticalArrangement = Arrangement.spacedBy(DoomTokens.spaceMd),
            ) {
                item {
                    Text(if (weekendEnabled) "Hafta içi günlük süre · $suffix" else "Günlük süre · $suffix", style = MaterialTheme.typography.labelLarge)
                    PolicyFields(weekday, showSessionAndCooldown = false) { weekday = it }
                    Text((if (intentionalOpen) "Oturum süresini her girişte seçiyorsun" else "${weekday.session} ${if (labMode) "birim" else "dk"} oturum") +
                        " · ${weekday.cooldown} ${if (labMode) "birim" else "dk"} mola" +
                        (if (weekendEnabled) " · Hafta sonu farklı" else "") +
                        (if (intentionalOpen) " · Bilinçli giriş açık" else ""),
                        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
                item {
                    IntentionalOpenToggle(intentionalOpen,
                        "Her girişte amacını ve süreni seç. Kaydettiğinde hemen uygulanır.") { intentionalOpen = it }
                }
                item {
                    DisclosureRow("Gelişmiş", "Oturum, mola, hafta sonu ve hazır ayarlar", advanced) { advanced = !advanced }
                    if (!valid && !advanced) Text("Süreleri kontrol etmek için Gelişmiş bölümünü aç.",
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                if (advanced) {
                item {
                    Text("Hazır profil", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                }
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(DoomTokens.spaceXs)) {
                        listOf("Sıkı", "Dengeli", "Hafif").forEach { profile ->
                            OutlinedButton(
                                onClick = {
                                    val limits = presetLimits(profile, labMode, initial.weekdayLimits.unitMillis)
                                    weekday = limits.numbers()
                                    weekend = limits.numbers()
                                    weekendEnabled = false
                                },
                                contentPadding = PaddingValues(horizontal = DoomTokens.spaceSm, vertical = DoomTokens.spaceSm),
                                modifier = Modifier.weight(1f),
                            ) { Text(profile, maxLines = 1) }
                        }
                    }
                }
                if (copyCandidates.isNotEmpty()) {
                    item {
                        Text("Başka uygulamadan kopyala", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                    }
                    items(copyCandidates.sortedBy { it.label.lowercase(Locale.getDefault()) }, key = { "copy-${it.packageName}" }) { source ->
                        val sourcePolicy = source.pendingPolicy?.policy ?: source.policy
                        TextButton(
                            onClick = {
                                weekday = sourcePolicy.weekdayLimits.numbers()
                                weekendEnabled = sourcePolicy.weekendLimits != null
                                weekend = (sourcePolicy.weekendLimits ?: sourcePolicy.weekdayLimits).numbers()
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("${source.label} ayarlarını kullan", Modifier.fillMaxWidth())
                        }
                    }
                    item { HorizontalDivider() }
                }
                item { Text(if (weekendEnabled) "Hafta içi · $suffix" else "Oturum ve mola · $suffix", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium) }
                item { PolicyFields(weekday, showDaily = false, showSession = !intentionalOpen) { weekday = it } }
                item {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(
                            value = weekendEnabled,
                            role = Role.Checkbox,
                            onValueChange = { weekendEnabled = it },
                        ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = weekendEnabled, onCheckedChange = null)
                        Text("Hafta sonu için farklı sınırlar")
                    }
                }
                if (weekendEnabled) {
                    item { Text("Cumartesi ve pazar · $suffix", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium) }
                    item { PolicyFields(weekend, showSession = !intentionalOpen) { weekend = it } }
                }
                }
                if (!isNew) item {
                    Text("Oturum süresi değişikliği ve bilinçli girişi açıp kapatma hemen uygulanır. Günlük süreyi artırmak veya molayı kısaltmak standart modda ertesi gün, katı modda en az 24 saat sonra devreye girer.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(bottom = DoomTokens.spaceLg),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) { Text("Vazgeç") }
                Button(
                onClick = {
                    onSave(
                        AppPolicy(weekdayLimits!!, if (weekendEnabled) weekendLimits else null),
                        intentionalOpen,
                    )
                },
                enabled = valid,
                ) { Text(if (isNew) "Korumayı başlat" else "Kaydet") }
            }
        }
    }
}

@Composable
private fun EmergencyAccessDialog(
    target: ProtectedApp,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val reasons = listOf("Ulaşım veya harita", "Önemli arama veya mesaj", "Sağlık veya güvenlik", "Zorunlu işlem")
    var selectedReason by rememberSaveable(target.packageName) { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${target.label} için acil erişim") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(DoomTokens.spaceMd)) {
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    shape = RoundedCornerShape(DoomTokens.radiusMd),
                ) {
                    Text(
                        "Bu hak günde bir kez kullanılabilir. 60 saniye bekledikten sonra uygulama 5 dakika açılır; sayaçlar sıfırlanmaz.",
                        Modifier.padding(DoomTokens.spaceMd),
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text("Neden", style = MaterialTheme.typography.labelLarge)
                reasons.forEach { reason ->
                    FilterChip(
                        selected = selectedReason == reason,
                        onClick = { selectedReason = reason },
                        label = { Text(reason) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { selectedReason?.let(onConfirm) },
                enabled = selectedReason != null,
            ) { Text("60 saniyelik beklemeyi başlat") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Vazgeç") } },
    )
}

@Composable
private fun PolicyFields(values: PolicyNumbers, showDaily: Boolean = true, showSessionAndCooldown: Boolean = true, showSession: Boolean = true, update: (PolicyNumbers) -> Unit) {
    val daily = values.daily.toIntOrNull()
    val session = values.session.toIntOrNull()
    val cooldown = values.cooldown.toIntOrNull()
    val dailyInvalid = daily == null || daily !in 1..1_440
    val sessionInvalid = session == null || daily == null || session !in 1..daily
    val cooldownInvalid = cooldown == null || cooldown !in 1..1_440

    if (showDaily) NumberField(
        label = "Günlük toplam",
        value = values.daily,
        isError = dailyInvalid,
        errorText = "1–1440 arasında olmalı.",
    ) { update(values.copy(daily = cleanNumber(it))) }
    if (!showSessionAndCooldown) return
    Spacer(Modifier.height(DoomTokens.spaceSm))
    if (showSession) NumberField(
        label = "Tek oturum",
        value = values.session,
        isError = sessionInvalid,
        errorText = "1 ile günlük toplam arasında olmalı.",
    ) { update(values.copy(session = cleanNumber(it))) }
    else Text("Oturum süresini her girişte seçiyorsun.", style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(DoomTokens.spaceSm))
    NumberField(
        label = "Zorunlu mola",
        value = values.cooldown,
        isError = cooldownInvalid,
        errorText = "1–1440 arasında olmalı.",
    ) { update(values.copy(cooldown = cleanNumber(it))) }
}

@Composable
private fun NumberField(
    label: String,
    value: String,
    isError: Boolean,
    errorText: String,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        isError = isError,
        supportingText = if (isError) ({ Text(errorText) }) else null,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun AppProgressIcon(
    app: InstalledApp?,
    progress: Float,
    color: Color,
) {
    val animatedProgress by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 450),
        label = "app-progress",
    )
    val animatedColor by animateColorAsState(
        targetValue = color,
        animationSpec = tween(durationMillis = 240),
        label = "app-progress-color",
    )
    val trackColor = MaterialTheme.colorScheme.surfaceContainer
    Box(
        Modifier.size(56.dp).semantics {
            stateDescription = "Günlük sürenin yüzde ${(progress * 100).toInt()} kadarı kaldı"
        },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawArc(
                color = trackColor,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round),
            )
            if (animatedProgress > 0f) drawArc(
                color = animatedColor,
                startAngle = -90f,
                sweepAngle = animatedProgress * 360f,
                useCenter = false,
                style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round),
            )
        }
        if (app != null) AppIcon(app) else MissingIcon()
    }
}

@Composable
private fun AppIcon(app: InstalledApp) {
    val bitmap = remember(app.packageName) {
        createBitmap(96, 96).also {
            app.icon.setBounds(0, 0, 96, 96)
            app.icon.draw(Canvas(it))
        }.asImageBitmap()
    }
    Image(
        bitmap,
        contentDescription = null,
        modifier = Modifier.size(42.dp).clip(RoundedCornerShape(DoomTokens.radiusSm)),
    )
}

@Composable
private fun MissingIcon() {
    Surface(
        Modifier.size(42.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(DoomTokens.radiusSm),
    ) {
        Box(contentAlignment = Alignment.Center) { Text("?", color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold) }
    }
}

private fun Limits.numbers() = PolicyNumbers(dailyMinutes.toString(), sessionMinutes.toString(), cooldownMinutes.toString())
private fun presetLimits(name: String, labMode: Boolean, unitMillis: Long): Limits {
    val values = if (labMode) when (name) {
        "Sıkı" -> Triple(6, 1, 3)
        "Hafif" -> Triple(24, 6, 3)
        else -> Triple(12, 3, 6)
    } else when (name) {
        "Sıkı" -> Triple(10, 3, 15)
        "Hafif" -> Triple(45, 10, 5)
        else -> Triple(20, 5, 10)
    }
    return Limits(values.first, values.second, values.third, unitMillis)
}
private fun cleanNumber(raw: String) = raw.filter(Char::isDigit).take(4)
private fun formatMinute(minuteOfDay: Int) = "%02d:%02d".format(
    Locale.getDefault(), minuteOfDay / 60, minuteOfDay % 60,
)
private fun formatScheduleDays(mask: Int): String = when (mask) {
    31 -> "Hafta içi"
    96 -> "Hafta sonu"
    127 -> "Her gün"
    else -> ScheduleDays.filter { mask and it.first != 0 }.joinToString(" · ") { it.second.take(3) }
}

private fun targetComparator(
    snapshot: dev.personal.doomblocker.core.ProtectionSnapshot?,
    now: dev.personal.doomblocker.core.ClockReading,
    sort: TargetSort,
): Comparator<ProtectedApp> {
    if (snapshot == null) return compareBy { it.label.lowercase(Locale.getDefault()) }
    fun view(target: ProtectedApp) = UsageEngine.view(snapshot, target, now)
    fun priority(target: ProtectedApp): Int = when (view(target).availability) {
        AppAvailability.FOCUS_BLOCKED, AppAvailability.MANUAL_BLOCKED,
        AppAvailability.DAILY_BLOCKED, AppAvailability.GROUP_BLOCKED,
        AppAvailability.SCHEDULE_BLOCKED -> 0
        AppAvailability.COOLDOWN -> 1
        else -> 2
    }
    fun smartRank(target: ProtectedApp): Int = when (view(target).availability) {
        AppAvailability.DAILY_BLOCKED -> 0
        AppAvailability.FOCUS_BLOCKED -> 1
        AppAvailability.MANUAL_BLOCKED -> 2
        AppAvailability.GROUP_BLOCKED -> 3
        AppAvailability.SCHEDULE_BLOCKED -> 4
        AppAvailability.COOLDOWN -> 5
        AppAvailability.EMERGENCY_WAIT -> 6
        AppAvailability.EMERGENCY_ACCESS, AppAvailability.PLANNED_ACCESS, AppAvailability.ACTIVE -> 7
        AppAvailability.REMOVAL_PENDING -> 8
        AppAvailability.INTENTION_REQUIRED, AppAvailability.AVAILABLE -> 9
    }
    val secondary = Comparator<ProtectedApp> { left, right ->
        when (sort) {
            TargetSort.SMART -> smartRank(left).compareTo(smartRank(right))
            TargetSort.LEAST_REMAINING -> {
                val leftRemaining = (left.limits.dailyMillis - view(left).usedTodayMillis).coerceAtLeast(0)
                val rightRemaining = (right.limits.dailyMillis - view(right).usedTodayMillis).coerceAtLeast(0)
                leftRemaining.compareTo(rightRemaining)
            }
            TargetSort.MOST_USED -> view(right).usedTodayMillis.compareTo(view(left).usedTodayMillis)
            TargetSort.BLOCKED -> smartRank(left).compareTo(smartRank(right))
        }
    }
    return compareBy<ProtectedApp> { priority(it) }
        .then(secondary)
        .thenBy { it.label.lowercase(Locale.getDefault()) }
}

private fun statusText(view: AppView) = when (view.availability) {
    AppAvailability.AVAILABLE -> "Şimdi kullanılabilir"
    AppAvailability.ACTIVE -> "Şu anda kullanılıyor"
    AppAvailability.EMERGENCY_WAIT -> "Acil erişim bekliyor · ${formatCountdown(view.emergencyWaitRemainingMillis)}"
    AppAvailability.EMERGENCY_ACCESS -> "Acil erişim · ${formatCountdown(view.emergencyAccessRemainingMillis)}"
    AppAvailability.COOLDOWN -> "Molada · ${formatCountdown(view.cooldownRemainingMillis)}"
    AppAvailability.DAILY_BLOCKED -> "Yarına kadar engelli"
    AppAvailability.GROUP_BLOCKED -> "${view.groupLabel ?: "Ortak limit"} doldu"
    AppAvailability.MANUAL_BLOCKED -> "Odak kilidi · yarına kadar kapalı"
    AppAvailability.SCHEDULE_BLOCKED -> "${view.scheduleLabel ?: "Program"} · ${formatCountdown(view.scheduleRemainingMillis)}"
    AppAvailability.FOCUS_BLOCKED -> "Odak modu · ${formatCountdown(view.focusRemainingMillis)}"
    AppAvailability.INTENTION_REQUIRED -> "Bilinçli giriş açık"
    AppAvailability.PLANNED_ACCESS -> "Planlı kullanım · ${formatCountdown(view.plannedAccessRemainingMillis)} kaldı"
    AppAvailability.REMOVAL_PENDING -> "Kaldırma bekliyor"
}

@Composable
private fun StatusPill(view: AppView) {
    val targetColor = when (view.availability) {
        AppAvailability.AVAILABLE, AppAvailability.ACTIVE, AppAvailability.EMERGENCY_ACCESS,
        AppAvailability.PLANNED_ACCESS, AppAvailability.INTENTION_REQUIRED -> MaterialTheme.colorScheme.primary
        AppAvailability.COOLDOWN, AppAvailability.REMOVAL_PENDING, AppAvailability.EMERGENCY_WAIT -> MaterialTheme.colorScheme.tertiary
        AppAvailability.DAILY_BLOCKED, AppAvailability.GROUP_BLOCKED,
        AppAvailability.MANUAL_BLOCKED, AppAvailability.SCHEDULE_BLOCKED,
        AppAvailability.FOCUS_BLOCKED -> MaterialTheme.colorScheme.error
    }
    val color by animateColorAsState(
        targetValue = targetColor,
        animationSpec = tween(durationMillis = 240),
        label = "status-color",
    )
    val symbol = when (view.availability) {
        AppAvailability.AVAILABLE -> "✓"
        AppAvailability.ACTIVE, AppAvailability.PLANNED_ACCESS, AppAvailability.EMERGENCY_ACCESS -> "●"
        AppAvailability.COOLDOWN, AppAvailability.EMERGENCY_WAIT -> "◷"
        AppAvailability.INTENTION_REQUIRED -> "?"
        AppAvailability.REMOVAL_PENDING -> "–"
        else -> "×"
    }
    Surface(
        color = color.copy(alpha = 0.12f),
        contentColor = color,
        shape = RoundedCornerShape(DoomTokens.radiusXl),
    ) {
        Row(
            Modifier.padding(horizontal = DoomTokens.spaceSm, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(symbol, color = color, style = MaterialTheme.typography.labelSmall)
            Text(statusText(view), color = color, style = MaterialTheme.typography.labelSmall)
        }
    }
}

private fun formatRule(value: Int, unitMillis: Long) =
    if (unitMillis == 60_000L) "$value dk" else "${value * unitMillis / 1_000} sn"

private fun formatUsage(millis: Long): String {
    val seconds = (millis / 1_000).coerceAtLeast(0)
    return when {
        seconds < 60 -> "$seconds sn"
        seconds % 60 == 0L -> "${seconds / 60} dk"
        else -> "${seconds / 60} dk ${seconds % 60} sn"
    }
}

private fun formatCountdown(millis: Long): String {
    val seconds = ceil(millis.coerceAtLeast(0) / 1_000.0).toLong()
    return "%02d:%02d".format(Locale.getDefault(), seconds / 60, seconds % 60)
}

private fun formatDateTime(wallMillis: Long?, zoneId: String): String {
    if (wallMillis == null) return "—"
    val formatter = DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.forLanguageTag("tr"))
    return Instant.ofEpochMilli(wallMillis).atZone(ZoneId.of(zoneId)).format(formatter)
}

private fun formatEventTime(wallMillis: Long, zoneId: String): String {
    val formatter = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.forLanguageTag("tr"))
    return Instant.ofEpochMilli(wallMillis).atZone(ZoneId.of(zoneId)).format(formatter)
}

private fun formatDay(epochDay: Long): String {
    val formatter = DateTimeFormatter.ofPattern("EEE d", Locale.forLanguageTag("tr"))
    return LocalDate.ofEpochDay(epochDay).format(formatter)
}

private fun historyCsv(
    snapshot: dev.personal.doomblocker.core.ProtectionSnapshot,
    now: dev.personal.doomblocker.core.ClockReading,
): String {
    val current = DailyRecord(snapshot.activeEpochDay, snapshot.apps.map { app ->
        DailyAppRecord(
            app.packageName,
            app.label,
            UsageEngine.view(snapshot, app, now).usedTodayMillis,
            app.limitEventsToday,
            app.longestSessionTodayMillis,
        )
    })
    val days = (snapshot.history + current).associateBy { it.epochDay }.values
        .sortedBy { it.epochDay }.takeLast(30)
    return buildString {
        append('\uFEFF')
        append("date,package,label,usage_seconds,limit_events,longest_session_seconds\r\n")
        days.forEach { day ->
            day.apps.sortedBy { it.label.lowercase(Locale.getDefault()) }.forEach { record ->
                append(LocalDate.ofEpochDay(day.epochDay)).append(',')
                append(csvCell(record.packageName)).append(',')
                append(csvCell(record.label)).append(',')
                append(String.format(Locale.US, "%.3f", record.usedMillis / 1_000.0)).append(',')
                append(record.limitEvents).append(',')
                append(String.format(Locale.US, "%.3f", record.longestSessionMillis / 1_000.0)).append("\r\n")
            }
        }
    }
}

private fun csvCell(value: String) = "\"${value.replace("\"", "\"\"")}\""

private fun ageText(wallMillis: Long?, nowWall: Long): String {
    if (wallMillis == null) return "henüz yok"
    val seconds = ((nowWall - wallMillis).coerceAtLeast(0) / 1_000)
    return when {
        seconds < 5 -> "az önce"
        seconds < 60 -> "$seconds sn önce"
        else -> "${seconds / 60} dk önce"
    }
}
