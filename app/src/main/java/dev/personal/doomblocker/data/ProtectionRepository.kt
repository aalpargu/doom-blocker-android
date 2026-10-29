package dev.personal.doomblocker.data

import android.content.Context
import android.util.AtomicFile
import dev.personal.doomblocker.R
import dev.personal.doomblocker.core.AppPolicy
import dev.personal.doomblocker.core.ClockReading
import dev.personal.doomblocker.core.DailyAppRecord
import dev.personal.doomblocker.core.DailyRecord
import dev.personal.doomblocker.core.EngineResult
import dev.personal.doomblocker.core.FocusSession
import dev.personal.doomblocker.core.GroupPolicy
import dev.personal.doomblocker.core.IntentionalAccessPolicy
import dev.personal.doomblocker.core.Limits
import dev.personal.doomblocker.core.OpenEvent
import dev.personal.doomblocker.core.PendingGroupPolicy
import dev.personal.doomblocker.core.PendingPolicy
import dev.personal.doomblocker.core.PendingRemoval
import dev.personal.doomblocker.core.PendingSchedulePolicy
import dev.personal.doomblocker.core.ProtectedApp
import dev.personal.doomblocker.core.ProtectedGroup
import dev.personal.doomblocker.core.ProtectedSchedule
import dev.personal.doomblocker.core.ProtectionSnapshot
import dev.personal.doomblocker.core.SchedulePolicy
import dev.personal.doomblocker.core.TimeAnchor
import dev.personal.doomblocker.core.TimedBlock
import dev.personal.doomblocker.core.TravelException
import dev.personal.doomblocker.core.UsageEngine
import dev.personal.doomblocker.platform.AppCatalog
import dev.personal.doomblocker.platform.DeviceAccess
import dev.personal.doomblocker.platform.DeviceClock
import dev.personal.doomblocker.platform.UsageHistoryReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

data class ProtectionState(
    val ready: Boolean = false,
    val snapshot: ProtectionSnapshot? = null,
    val error: String? = null,
    val measurementWarning: String? = null,
    val recoveryAvailable: Boolean = false,
    val corruptRecordName: String? = null,
)

data class ImportSummary(
    val added: Int,
    val updated: Int,
    val skipped: Int,
    val groupsAdded: Int = 0,
    val groupsUpdated: Int = 0,
    val groupsSkipped: Int = 0,
    val schedulesAdded: Int = 0,
    val schedulesUpdated: Int = 0,
    val schedulesSkipped: Int = 0,
)

data class ImportPreview(
    val appsToAdd: Int,
    val appsToUpdate: Int,
    val appsToSkip: Int,
    val groupsToAdd: Int,
    val groupsToUpdate: Int,
    val schedulesToAdd: Int,
    val schedulesToUpdate: Int,
    val enablesStrictMode: Boolean,
)

/** One process, one instance and one serialized durable writer. */
class ProtectionRepository(
    context: Context,
    private val scope: CoroutineScope,
    private val clock: DeviceClock,
    private val catalog: AppCatalog,
    private val usageHistory: UsageHistoryReader,
    private val diagnostics: DiagnosticJournal,
    private val isServiceConnected: () -> Boolean,
) {
    private val appContext = context.applicationContext
    private val measurementNotes = context.getSharedPreferences("measurement-notes", Context.MODE_PRIVATE)
    private val timeUnitMillis = context.resources.getInteger(R.integer.time_unit_millis).toLong()
    private val file = AtomicFile(File(context.filesDir, "protection-v5.json"))
    private val v4File = AtomicFile(File(context.filesDir, "protection-v4.json"))
    private val v3File = AtomicFile(File(context.filesDir, "protection-v3.json"))
    private val v2File = AtomicFile(File(context.filesDir, "protection-v2.json"))
    private val v1File = AtomicFile(File(context.filesDir, "protection-v1.json"))
    private val lastGood1 = AtomicFile(File(context.filesDir, "protection-v5-last-good-1.json"))
    private val lastGood2 = AtomicFile(File(context.filesDir, "protection-v5-last-good-2.json"))
    private val preImportFile = AtomicFile(File(context.filesDir, "protection-v5-before-import.json"))
    private val mutex = Mutex()
    private val mutable = MutableStateFlow(ProtectionState())
    private var pendingPersist: ProtectionSnapshot? = null
    private var persistJob: Job? = null
    val state = mutable.asStateFlow()

    fun initialize() {
        scope.launch(Dispatchers.IO) {
            mutex.withLock {
                val now = clock.now()
                try {
                    val (loaded, migratedFrom) = when {
                        hasData(file) -> decode(file.openRead().bufferedReader().use { it.readText() }) to null
                        hasData(v4File) -> decode(v4File.openRead().bufferedReader().use { it.readText() }) to "2.1.0"
                        hasData(v3File) -> decode(v3File.openRead().bufferedReader().use { it.readText() }) to "2.0.0"
                        hasData(v2File) -> migrateV2(v2File.openRead().bufferedReader().use { it.readText() }) to "1.0.0"
                        hasData(v1File) -> migrateV1(v1File.openRead().bufferedReader().use { it.readText() }, now) to "0.1.x"
                        else -> UsageEngine.newSnapshot(now, ZoneId.systemDefault().id) to null
                    }
                    val previousForeground = loaded.apps.firstOrNull { it.foregroundSince != null }?.packageName
                    var snapshot = UsageEngine.restore(BundledTravelExceptionPlan.apply(loaded, now), now)
                    var warning: String? = measurementNotes.getString("last-gap", null)
                    if (snapshot.apps.isNotEmpty() && now.wallMillis > loaded.recoveryCursorWallMillis) {
                        if (DeviceAccess.hasUsageAccess(appContext)) {
                            val recovery = runCatching {
                                usageHistory.read(
                                    snapshot.apps.map { it.packageName }.toSet(),
                                    loaded.recoveryCursorWallMillis,
                                    now.wallMillis,
                                    previousForeground,
                                )
                            }.getOrElse {
                                warning = "Kesinti sırasındaki kullanım geçmişi okunamadı; ölçüm boşluğu tanılamaya kaydedildi."
                                diagnostics.add("UYARI", "UsageEvents kurtarması başarısız: ${it.javaClass.simpleName}")
                                null
                            }
                            if (recovery != null) {
                                warning = recovery.coverageWarning ?: warning
                                snapshot = UsageEngine.recoverUsage(
                                    snapshot,
                                    recovery.intervals,
                                    loaded.recoveryCursorWallMillis,
                                    previousForeground,
                                    recovery.finalForegroundPackage,
                                    now,
                                )
                                diagnostics.add(
                                    "KURTARMA",
                                    "${recovery.intervals.size} foreground aralığı işlendi.",
                                )
                            } else snapshot = snapshot.copy(recoveryCursorWallMillis = now.wallMillis)
                        } else {
                            warning = "Kullanım erişimi kapalıyken oluşan ölçüm boşluğu tamamlanamadı. Canlı koruma erişilebilirlik servisiyle devam eder."
                            snapshot = snapshot.copy(recoveryCursorWallMillis = now.wallMillis)
                            diagnostics.add("UYARI", "Usage Access kapalı; kurtarma aralığı atlandı.")
                        }
                    }
                    persist(snapshot)
                    warning?.let { measurementNotes.edit().putString("last-gap", it).apply() }
                    mutable.value = ProtectionState(true, snapshot, measurementWarning = warning)
                    migratedFrom?.let { diagnostics.add("VERİ", "$it kaydı yeni veri şemasına taşındı.") }
                    diagnostics.add("BAŞLATMA", "Koruma kaydı hazır: ${snapshot.apps.size} hedef.")
                } catch (error: Exception) {
                    val quarantined = quarantineCurrentRecord()
                    val recovered = readLastGood()?.let {
                        runCatching { restoreWithMeasuredUsage(BundledTravelExceptionPlan.apply(it, now), now) }.getOrNull()
                    }
                    if (recovered != null) {
                        persist(recovered, rotateBackup = false)
                        mutable.value = ProtectionState(
                            ready = true,
                            snapshot = recovered,
                            measurementWarning = "Ana kayıt okunamadı; son sağlam kayıt otomatik geri yüklendi.",
                            recoveryAvailable = true,
                            corruptRecordName = quarantined?.name,
                        )
                        diagnostics.add("KURTARMA", "Bozuk ana kayıt yerine son sağlam kayıt yüklendi.")
                    } else {
                        mutable.value = ProtectionState(
                            error = "Koruma kaydı okunamadı. Bozuk kayıt korundu; kurtarma veya sıfırlama gerekiyor.",
                            recoveryAvailable = hasData(lastGood1) || hasData(lastGood2),
                            corruptRecordName = quarantined?.name,
                        )
                        diagnostics.add("HATA", "Repository başlatılamadı: ${error.javaClass.simpleName}")
                    }
                }
            }
        }
    }

    suspend fun add(packageName: String, label: String, policy: AppPolicy): Result<Unit> = mutate { snapshot, now ->
        require(catalog.canProtect(packageName)) { "Bu uygulama güvenli hedef listesinde değil veya artık yüklü değil." }
        val normalized = policy.withUnit(timeUnitMillis)
        val prepared = UsageEngine.tick(snapshot, now).snapshot
        val initial = if (DeviceAccess.hasUsageAccess(appContext)) {
            usageHistory.read(setOf(packageName), UsageEngine.dayStartWall(prepared), now.wallMillis)
                .usageByPackage[packageName] ?: 0
        } else 0
        UsageEngine.addApp(prepared, packageName, label, normalized, initial, now)
    }.also { result ->
        if (result.isSuccess) diagnostics.add("AYAR", "Hedef eklendi.", packageName)
    }

    suspend fun add(packageName: String, label: String, limits: Limits): Result<Unit> =
        add(packageName, label, AppPolicy(limits))

    suspend fun updatePolicy(packageName: String, policy: AppPolicy): Result<Unit> = mutate { snapshot, now ->
        UsageEngine.updatePolicy(snapshot, packageName, policy.withUnit(timeUnitMillis), now)
    }.also { if (it.isSuccess) diagnostics.add("AYAR", "Kullanım politikası düzenlendi.", packageName) }

    suspend fun updateLimits(packageName: String, limits: Limits): Result<Unit> =
        updatePolicy(packageName, AppPolicy(limits))

    suspend fun setTravelException(startWallMillis: Long, endWallMillis: Long): Result<Unit> =
        mutate { snapshot, now -> UsageEngine.setTravelException(snapshot, startWallMillis, endWallMillis, now) }
            .also { if (it.isSuccess) diagnostics.add("YOLCULUK", "Tek seferlik yolculuk istisnası ayarlandı.") }

    suspend fun clearTravelException(): Result<Unit> = mutate { snapshot, now ->
        UsageEngine.clearTravelException(snapshot, now)
    }.also { if (it.isSuccess) diagnostics.add("YOLCULUK", "Yolculuk istisnası iptal edildi.") }

    suspend fun setIntentionalOpen(packageName: String, enabled: Boolean): Result<Unit> = mutate { snapshot, now ->
        UsageEngine.setIntentionalOpen(snapshot, packageName, enabled, now)
    }.also {
        if (it.isSuccess) diagnostics.add(
            "NİYET",
            if (enabled) "Bilinçli giriş açıldı." else "Bilinçli giriş kapatıldı.",
            packageName,
        )
    }

    suspend fun startPlannedAccess(packageName: String, minutes: Int, reason: String,
                                   sharedCeilingMillis: Long = Long.MAX_VALUE): Result<Unit> =
        mutate { snapshot, now ->
            require(minutes in IntentionalAccessPolicy.allowedMinutes) { "Kısa giriş 1, 5, 10 veya 15 dakika olmalıdır." }
            require(sharedCeilingMillis > 0) { "Ortak günlük kullanım hakkın doldu." }
            UsageEngine.startPlannedAccess(snapshot, packageName, minOf(minutes * timeUnitMillis, sharedCeilingMillis), reason, now)
        }.also {
            if (it.isSuccess) diagnostics.add("NİYET", "Kalan günlük ve ortak kota içinde bilinçli giriş başladı: ${reason.trim()}", packageName)
        }

    suspend fun applyPolicyToApps(packageNames: Set<String>, policy: AppPolicy): Result<Int> = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching {
                val current = requireSnapshot()
                val targets = current.apps.filter { it.packageName in packageNames }
                require(targets.isNotEmpty()) { "Profilde korunabilen bir uygulama bulunamadı." }
                val normalized = policy.withUnit(timeUnitMillis)
                val updated = targets.fold(current) { state, target ->
                    UsageEngine.updatePolicy(state, target.packageName, normalized, clock.now())
                }
                publishIfChanged(current, updated)
                diagnostics.add("PROFİL", "Toplu limit profili ${targets.size} uygulamaya uygulandı.")
                targets.size
            }
        }
    }

    suspend fun requestRemoval(packageName: String): Result<Unit> = mutate { snapshot, now ->
        UsageEngine.requestRemoval(snapshot, packageName, now)
    }.also { if (it.isSuccess) diagnostics.add("AYAR", "Koruma kaldırma isteği kaydedildi.", packageName) }

    suspend fun cancelRemoval(packageName: String): Result<Unit> = mutate { snapshot, now ->
        UsageEngine.cancelRemoval(snapshot, packageName, now)
    }.also { if (it.isSuccess) diagnostics.add("AYAR", "Kaldırma isteği iptal edildi.", packageName) }

    suspend fun addGroup(label: String, packageNames: Set<String>, dailyMinutes: Int): Result<Unit> {
        val id = UUID.randomUUID().toString()
        return mutate { snapshot, now ->
            UsageEngine.addGroup(
                snapshot,
                id,
                GroupPolicy(label.trim(), packageNames, dailyMinutes, timeUnitMillis),
                now,
            )
        }.also { if (it.isSuccess) diagnostics.add("ORTAK LİMİT", "Ortak günlük limit eklendi: ${label.trim()}.") }
    }

    suspend fun updateGroup(id: String, label: String, packageNames: Set<String>, dailyMinutes: Int): Result<Unit> =
        mutate { snapshot, now ->
            UsageEngine.updateGroup(
                snapshot,
                id,
                GroupPolicy(label.trim(), packageNames, dailyMinutes, timeUnitMillis),
                now,
            )
        }.also { if (it.isSuccess) diagnostics.add("ORTAK LİMİT", "Ortak günlük limit düzenlendi: ${label.trim()}.") }

    suspend fun requestGroupRemoval(id: String): Result<Unit> = mutate { snapshot, now ->
        UsageEngine.requestGroupRemoval(snapshot, id, now)
    }.also { if (it.isSuccess) diagnostics.add("ORTAK LİMİT", "Ortak günlük limit kaldırma isteği kaydedildi.") }

    suspend fun cancelGroupRemoval(id: String): Result<Unit> = mutate { snapshot, now ->
        UsageEngine.cancelGroupRemoval(snapshot, id, now)
    }.also { if (it.isSuccess) diagnostics.add("ORTAK LİMİT", "Ortak limit kaldırma isteği iptal edildi.") }

    suspend fun addSchedule(
        label: String,
        packageNames: Set<String>,
        daysMask: Int,
        startMinuteOfDay: Int,
        endMinuteOfDay: Int,
    ): Result<Unit> {
        val id = UUID.randomUUID().toString()
        return mutate { snapshot, now ->
            UsageEngine.addSchedule(
                snapshot, id,
                SchedulePolicy(label.trim(), packageNames, daysMask, startMinuteOfDay, endMinuteOfDay),
                now,
            )
        }.also { if (it.isSuccess) diagnostics.add("PROGRAM", "Otomatik engelleme programı eklendi: ${label.trim()}.") }
    }

    suspend fun updateSchedule(
        id: String,
        label: String,
        packageNames: Set<String>,
        daysMask: Int,
        startMinuteOfDay: Int,
        endMinuteOfDay: Int,
    ): Result<Unit> = mutate { snapshot, now ->
        UsageEngine.updateSchedule(
            snapshot, id,
            SchedulePolicy(label.trim(), packageNames, daysMask, startMinuteOfDay, endMinuteOfDay),
            now,
        )
    }.also { if (it.isSuccess) diagnostics.add("PROGRAM", "Otomatik engelleme programı düzenlendi: ${label.trim()}.") }

    suspend fun requestScheduleRemoval(id: String): Result<Unit> = mutate { snapshot, now ->
        UsageEngine.requestScheduleRemoval(snapshot, id, now)
    }.also { if (it.isSuccess) diagnostics.add("PROGRAM", "Program kaldırma isteği kaydedildi.") }

    suspend fun cancelScheduleRemoval(id: String): Result<Unit> = mutate { snapshot, now ->
        UsageEngine.cancelScheduleRemoval(snapshot, id, now)
    }.also { if (it.isSuccess) diagnostics.add("PROGRAM", "Program kaldırma isteği iptal edildi.") }

    suspend fun enableStrictMode(): Result<Unit> = mutate { snapshot, now ->
        UsageEngine.enableStrictMode(snapshot, now)
    }.also { if (it.isSuccess) diagnostics.add("KATI MOD", "Katı mod açıldı.") }

    suspend fun requestStrictModeDisable(): Result<Unit> = mutate { snapshot, now ->
        UsageEngine.requestStrictModeDisable(snapshot, now)
    }.also { if (it.isSuccess) diagnostics.add("KATI MOD", "Kapatma için 24 saatlik bekleme başladı.") }

    suspend fun lockAllForToday(): Result<Unit> = mutate { snapshot, now ->
        UsageEngine.lockAllForToday(snapshot, now)
    }.also { if (it.isSuccess) diagnostics.add("ODAK KİLİDİ", "Tüm korunan uygulamalar bugünlük kilitlendi.") }

    suspend fun lockAppForToday(packageName: String): Result<Unit> = mutate { snapshot, now ->
        UsageEngine.lockAppForToday(snapshot, packageName, now)
    }.also { if (it.isSuccess) diagnostics.add("ODAK KİLİDİ", "Uygulama bugünlük kilitlendi.", packageName) }

    suspend fun startFocusSession(packageNames: Set<String>, minutes: Int): Result<Unit> = mutate { snapshot, now ->
        require(isServiceConnected()) { "Koruma servisi kapalı. Odak başlatmadan önce Ayarlar'dan erişilebilirlik servisini aç." }
        require(minutes in setOf(15, 30, 60)) { "Odak süresi 15, 30 veya 60 dakika olmalıdır." }
        UsageEngine.startFocusSession(snapshot, packageNames, minutes * timeUnitMillis, now)
    }.also {
        if (it.isSuccess) diagnostics.add("ODAK", "$minutes dakikalık odak modu başlatıldı: ${packageNames.size} uygulama.")
    }

    suspend fun requestEmergencyAccess(packageName: String, reason: String): Result<Unit> = mutate { snapshot, now ->
        UsageEngine.requestEmergencyAccess(snapshot, packageName, reason, now)
    }.also {
        if (it.isSuccess) diagnostics.add("ACİL ERİŞİM", "60 saniyelik bekleme başlatıldı: $reason", packageName)
    }

    suspend fun onForeground(
        packageName: String?,
        interactive: Boolean,
        beforePersist: suspend (EngineResult) -> Unit = {},
    ): Result<EngineResult> = engineMutation(beforePersist) { snapshot, now ->
        UsageEngine.onForeground(snapshot, packageName, interactive, now)
    }

    /** Rebinding a service in the same process needs recovery too, not only process startup. */
    suspend fun reconcileMeasurement(): Result<Unit> = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching {
                val current = requireSnapshot()
                val now = clock.now()
                if (now.wallMillis <= current.recoveryCursorWallMillis || current.apps.isEmpty()) return@runCatching
                val recovery = usageHistory.read(current.apps.mapTo(mutableSetOf()) { it.packageName },
                    current.recoveryCursorWallMillis, now.wallMillis)
                val restored = UsageEngine.restore(current, now)
                val updated = UsageEngine.recoverUsage(restored, recovery.intervals, current.recoveryCursorWallMillis,
                    current.apps.firstOrNull { it.foregroundSince != null }?.packageName, recovery.finalForegroundPackage, now)
                publishIfChanged(current, updated)
                recovery.coverageWarning?.let {
                    measurementNotes.edit().putString("last-gap", it).apply()
                    mutable.value = mutable.value.copy(measurementWarning = it)
                }
                Unit
            }.onFailure {
                val warning = "Servis kesintisinde kullanım tamamlanamadı; süreler eksik olabilir."
                measurementNotes.edit().putString("last-gap", warning).apply()
                mutable.value = mutable.value.copy(measurementWarning = warning)
            }
        }
    }

    suspend fun tick(): Result<EngineResult> = engineMutation { snapshot, now -> UsageEngine.tick(snapshot, now) }

    suspend fun refresh(): Result<Unit> = mutate { snapshot, now -> UsageEngine.tick(snapshot, now).snapshot }

    suspend fun exportRules(): Result<String> = withContext(Dispatchers.IO) {
        mutex.withLock { runCatching { encodeRules(requireSnapshot()) } }
    }

    suspend fun importRules(raw: String): Result<ImportSummary> = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching {
                val bundle = decodeRules(raw)
                val now = clock.now()
                var snapshot = UsageEngine.tick(requireSnapshot(), now).snapshot
                var added = 0
                var updated = 0
                var skipped = 0
                var groupsAdded = 0
                var groupsUpdated = 0
                var groupsSkipped = 0
                var schedulesAdded = 0
                var schedulesUpdated = 0
                var schedulesSkipped = 0
                bundle.rules.forEach { rule ->
                    val normalized = rule.policy.withUnit(timeUnitMillis)
                    if (snapshot.apps.any { it.packageName == rule.packageName }) {
                        snapshot = UsageEngine.updatePolicy(snapshot, rule.packageName, normalized, now)
                        rule.intentionalOpen?.let { enabled ->
                            snapshot = UsageEngine.setIntentionalOpen(snapshot, rule.packageName, enabled, now)
                        }
                        updated++
                    } else if (catalog.canProtect(rule.packageName)) {
                        val initial = if (DeviceAccess.hasUsageAccess(appContext)) {
                            usageHistory.read(setOf(rule.packageName), UsageEngine.dayStartWall(snapshot), now.wallMillis)
                                .usageByPackage[rule.packageName] ?: 0
                        } else 0
                        snapshot = UsageEngine.addApp(snapshot, rule.packageName, rule.label, normalized, initial, now)
                        rule.intentionalOpen?.let { enabled ->
                            snapshot = UsageEngine.setIntentionalOpen(snapshot, rule.packageName, enabled, now)
                        }
                        added++
                    } else skipped++
                }
                bundle.groups.forEach { rule ->
                    val normalized = rule.policy.withUnit(timeUnitMillis)
                    val attempt = runCatching {
                        if (snapshot.groups.any { it.id == rule.id }) {
                            snapshot = UsageEngine.updateGroup(snapshot, rule.id, normalized, now)
                            groupsUpdated++
                        } else {
                            snapshot = UsageEngine.addGroup(snapshot, rule.id, normalized, now)
                            groupsAdded++
                        }
                    }
                    if (attempt.isFailure) groupsSkipped++
                }
                bundle.schedules.forEach { rule ->
                    val attempt = runCatching {
                        if (snapshot.schedules.any { it.id == rule.id }) {
                            snapshot = UsageEngine.updateSchedule(snapshot, rule.id, rule.policy, now)
                            schedulesUpdated++
                        } else {
                            snapshot = UsageEngine.addSchedule(snapshot, rule.id, rule.policy, now)
                            schedulesAdded++
                        }
                    }
                    if (attempt.isFailure) schedulesSkipped++
                }
                if (bundle.strictMode) snapshot = UsageEngine.enableStrictMode(snapshot, now)
                val old = requireSnapshot()
                writeAtomic(preImportFile, encode(old).toByteArray(Charsets.UTF_8))
                publishIfChanged(old, snapshot)
                ImportSummary(
                    added, updated, skipped, groupsAdded, groupsUpdated, groupsSkipped,
                    schedulesAdded, schedulesUpdated, schedulesSkipped,
                ).also {
                    diagnostics.add(
                        "YEDEK",
                        "Kurallar içe aktarıldı: $added yeni, $updated güncel, $skipped atlandı; " +
                            "$groupsAdded ortak limit eklendi, $groupsUpdated güncellendi, " +
                            "$groupsSkipped atlandı; $schedulesAdded program eklendi, " +
                            "$schedulesUpdated güncellendi, $schedulesSkipped atlandı.",
                    )
                }
            }
        }
    }

    suspend fun previewRules(raw: String): Result<ImportPreview> = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching {
                val bundle = decodeRules(raw)
                val snapshot = requireSnapshot()
                ImportPreview(
                    appsToAdd = bundle.rules.count { rule ->
                        snapshot.apps.none { it.packageName == rule.packageName } && catalog.canProtect(rule.packageName)
                    },
                    appsToUpdate = bundle.rules.count { rule -> snapshot.apps.any { it.packageName == rule.packageName } },
                    appsToSkip = bundle.rules.count { rule ->
                        snapshot.apps.none { it.packageName == rule.packageName } && !catalog.canProtect(rule.packageName)
                    },
                    groupsToAdd = bundle.groups.count { rule -> snapshot.groups.none { it.id == rule.id } },
                    groupsToUpdate = bundle.groups.count { rule -> snapshot.groups.any { it.id == rule.id } },
                    schedulesToAdd = bundle.schedules.count { rule -> snapshot.schedules.none { it.id == rule.id } },
                    schedulesToUpdate = bundle.schedules.count { rule -> snapshot.schedules.any { it.id == rule.id } },
                    enablesStrictMode = bundle.strictMode && !snapshot.strictModeEnabled,
                )
            }
        }
    }

    fun hasImportUndo(): Boolean = hasData(preImportFile)

    suspend fun undoLastImport(): Result<Unit> = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching {
                val current = requireSnapshot()
                val restored = dev.personal.doomblocker.core.RuleRestore.apply(current,
                    decode(preImportFile.openRead().bufferedReader().use { it.readText() }), clock.now())
                publishIfChanged(current, restored)
                preImportFile.delete()
                diagnostics.add("YEDEK", "Son kural içe aktarma işlemi geri alındı.")
            }
        }
    }

    suspend fun validateRules(raw: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { decodeRules(raw); Unit }
    }

    suspend fun recoverLastGood(): Result<Unit> = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching {
                val restored = restoreWithMeasuredUsage(checkNotNull(readLastGood()) { "Kurtarılabilir sağlam kayıt bulunamadı." }, clock.now())
                persistJob?.cancel()
                pendingPersist = null
                persist(restored, rotateBackup = false)
                mutable.value = ProtectionState(ready = true, snapshot = restored, measurementWarning = measurementNotes.getString("last-gap", null))
                diagnostics.add("KURTARMA", "Son sağlam kayıt kullanıcı isteğiyle geri yüklendi.")
            }
        }
    }

    suspend fun exportCorruptRecord(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val named = mutable.value.corruptRecordName?.let { File(appContext.filesDir, it) }
            val source = named?.takeIf(File::isFile)
                ?: appContext.filesDir.listFiles()?.filter { it.name.startsWith(CORRUPT_PREFIX) }?.maxByOrNull(File::lastModified)
                ?: error("Dışa aktarılabilecek bozuk kayıt bulunamadı.")
            source.readText(Charsets.UTF_8)
        }
    }

    suspend fun resetStorage(): Result<Unit> = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching {
                quarantineCurrentRecord()
                persistJob?.cancel()
                pendingPersist = null
                val fresh = UsageEngine.newSnapshot(clock.now(), ZoneId.systemDefault().id)
                persist(fresh, rotateBackup = false)
                mutable.value = ProtectionState(ready = true, snapshot = fresh)
                diagnostics.add("KURTARMA", "Koruma kaydı kullanıcı isteğiyle sıfırlandı.")
            }
        }
    }

    private suspend fun mutate(
        transform: (ProtectionSnapshot, ClockReading) -> ProtectionSnapshot,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching {
                val current = requireSnapshot()
                publishIfChanged(current, transform(current, clock.now()))
            }
        }
    }

    private suspend fun engineMutation(
        beforePersist: suspend (EngineResult) -> Unit = {},
        transform: (ProtectionSnapshot, ClockReading) -> EngineResult,
    ): Result<EngineResult> = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching {
                val current = requireSnapshot()
                val result = transform(current, clock.now())
                // The authoritative decision may cover the target before fsync/backup rotation.
                // Grants and settings still publish only after their durable write completes.
                beforePersist(result)
                val previousForeground = current.apps.firstOrNull { it.foregroundSince != null }?.packageName
                val nextForeground = result.snapshot.apps.firstOrNull { it.foregroundSince != null }?.packageName
                val urgent = PersistencePolicy.shouldPersistImmediately(
                    previousForeground,
                    nextForeground,
                    result.decision != null,
                )
                runCatching { publishIfChanged(current, result.snapshot, persistImmediately = urgent) }.onFailure {
                    // Storage failure must not prevent a computed Home decision from reaching the service.
                    mutable.value = mutable.value.copy(snapshot = result.snapshot,
                        measurementWarning = "Disk kaydı yazılamadı. Koruma bellekte sürüyor; yeniden başlatmada ölçüm kaybı olabilir.")
                }
                result
            }
        }
    }

    private fun requireSnapshot(): ProtectionSnapshot {
        check(mutable.value.ready) { "Koruma kaydı henüz hazır değil." }
        return checkNotNull(mutable.value.snapshot)
    }

    private fun restoreWithMeasuredUsage(saved: ProtectionSnapshot, now: ClockReading): ProtectionSnapshot {
        val restored = UsageEngine.restore(saved, now)
        if (saved.apps.isEmpty() || now.wallMillis <= saved.recoveryCursorWallMillis) return restored
        val recovery = runCatching { usageHistory.read(saved.apps.mapTo(mutableSetOf()) { it.packageName },
            saved.recoveryCursorWallMillis, now.wallMillis) }.getOrNull()
        val warning = recovery?.coverageWarning ?: if (recovery == null) "Sağlam kayıt geri geldi fakat aradaki kullanım tamamlanamadı." else null
        warning?.let { measurementNotes.edit().putString("last-gap", it).apply() }
        return if (recovery == null) restored.copy(recoveryCursorWallMillis = now.wallMillis)
        else UsageEngine.recoverUsage(restored, recovery.intervals, saved.recoveryCursorWallMillis,
            saved.apps.firstOrNull { it.foregroundSince != null }?.packageName, recovery.finalForegroundPackage, now)
    }

    private fun publishIfChanged(
        old: ProtectionSnapshot,
        updated: ProtectionSnapshot,
        persistImmediately: Boolean = true,
    ) {
        if (updated == old) return
        if (persistImmediately) {
            persistJob?.cancel()
            persistJob = null
            pendingPersist = null
            persist(updated)
            mutable.value = mutable.value.copy(snapshot = updated, error = null)
        } else {
            pendingPersist = updated
            mutable.value = mutable.value.copy(snapshot = updated, error = null)
            if (persistJob?.isActive != true) {
                persistJob = scope.launch(Dispatchers.IO) {
                    delay(PERSIST_INTERVAL_MILLIS)
                    mutex.withLock {
                        val pending = pendingPersist
                        if (pending != null) {
                            runCatching { persist(pending) }
                                .onSuccess { pendingPersist = null }
                                .onFailure {
                                    mutable.value = mutable.value.copy(
                                        measurementWarning = "Sayaç bellekte güncel ancak son disk kaydı yazılamadı.",
                                    )
                                    diagnostics.add("HATA", "Gecikmeli sayaç kaydı yazılamadı: ${it.javaClass.simpleName}")
                                }
                        }
                        persistJob = null
                    }
                }
            }
        }
    }

    private fun persist(snapshot: ProtectionSnapshot, rotateBackup: Boolean = true) {
        val bytes = encode(snapshot).toByteArray(Charsets.UTF_8)
        if (rotateBackup) rotateLastGood()
        writeAtomic(file, bytes)
    }

    private fun writeAtomic(target: AtomicFile, bytes: ByteArray) {
        val stream = target.startWrite()
        try {
            stream.write(bytes)
            target.finishWrite(stream)
        } catch (error: Exception) {
            target.failWrite(stream)
            throw error
        }
    }

    private fun rotateLastGood() {
        runCatching {
            if (hasData(lastGood1)) writeAtomic(lastGood2, lastGood1.openRead().use { it.readBytes() })
            if (hasData(file)) {
                val current = file.openRead().use { it.readBytes() }
                decode(current.toString(Charsets.UTF_8))
                writeAtomic(lastGood1, current)
            }
        }.onFailure {
            scope.launch(Dispatchers.IO) { diagnostics.add("UYARI", "Son sağlam kayıt kopyası güncellenemedi.") }
        }
    }

    private fun readLastGood(): ProtectionSnapshot? = sequenceOf(lastGood1, lastGood2)
        .filter(::hasData)
        .mapNotNull { target -> runCatching { decode(target.openRead().bufferedReader().use { it.readText() }) }.getOrNull() }
        .firstOrNull()

    private fun quarantineCurrentRecord(): File? = runCatching {
        if (!hasData(file)) return@runCatching null
        val destination = File(appContext.filesDir, "$CORRUPT_PREFIX${System.currentTimeMillis()}.json")
        file.openRead().use { input -> destination.outputStream().use(input::copyTo) }
        destination
    }.getOrNull()

    private fun hasData(target: AtomicFile) = target.baseFile.exists() || File(target.baseFile.path + ".bak").exists()

    private fun encode(snapshot: ProtectionSnapshot) = JSONObject()
        .put("version", 5)
        .put("zone", snapshot.quotaZoneId)
        .put("day", snapshot.activeEpochDay)
        .put("clock", snapshot.clockAnchor.json())
        .put("cursorWall", snapshot.recoveryCursorWallMillis)
        .put("strict", snapshot.strictModeEnabled)
        .put("strictDisableWall", snapshot.strictModeDisableAtWallMillis)
        .put("travelExceptionConfigured", snapshot.travelExceptionConfigured)
        .put("travelException", snapshot.travelException?.let {
            JSONObject().put("startWall", it.startWallMillis).put("endWall", it.endWallMillis)
        })
        .put("apps", JSONArray().apply { snapshot.apps.forEach { put(it.json()) } })
        .put("groups", JSONArray().apply { snapshot.groups.forEach { put(it.json()) } })
        .put("schedules", JSONArray().apply { snapshot.schedules.forEach { put(it.json()) } })
        .put("history", JSONArray().apply { snapshot.history.forEach { put(it.json()) } })
        .put("focusSession", snapshot.focusSession?.let { session ->
            JSONObject()
                .put("packages", JSONArray(session.packageNames.sorted()))
                .put("start", session.block.startedAt.json())
                .put("duration", session.block.durationMillis)
        })
        .put("openEvents", JSONArray().apply { snapshot.openEvents.forEach { event ->
            put(JSONObject().put("package", event.packageName).put("wall", event.wallMillis))
        } })
        .toString()

    private fun decode(raw: String): ProtectionSnapshot {
        val root = JSONObject(raw)
        require(root.getInt("version") in 3..5) { "Unsupported storage version" }
        val apps = root.getJSONArray("apps").objects().map { it.app() }
        require(apps.distinctBy { it.packageName }.size == apps.size)
        require(apps.count { it.foregroundSince != null } <= 1)
        val groups = root.optJSONArray("groups")?.objects()?.map { it.group() }.orEmpty()
        val appPackages = apps.mapTo(mutableSetOf()) { it.packageName }
        require(groups.distinctBy { it.id }.size == groups.size)
        require(groups.flatMap { it.policy.packageNames }.distinct().size == groups.sumOf { it.policy.packageNames.size })
        require(groups.flatMap { it.policy.packageNames }.all(appPackages::contains))
        val schedules = root.optJSONArray("schedules")?.objects()?.map { it.schedule() }.orEmpty()
        require(schedules.distinctBy { it.id }.size == schedules.size)
        require(schedules.flatMap { it.policy.packageNames }.all(appPackages::contains))
        return ProtectionSnapshot(
            quotaZoneId = root.getString("zone"),
            activeEpochDay = root.getLong("day"),
            clockAnchor = root.getJSONObject("clock").anchor(),
            recoveryCursorWallMillis = root.getLong("cursorWall"),
            apps = apps,
            history = root.optJSONArray("history")?.objects()?.map { it.dailyRecord() }.orEmpty().takeLast(UsageEngine.HISTORY_DAYS),
            strictModeEnabled = root.optBoolean("strict", false),
            strictModeDisableAtWallMillis = root.nullableLong("strictDisableWall"),
            groups = groups,
            schedules = schedules,
            focusSession = root.optJSONObject("focusSession")?.let { item ->
                val packages = item.getJSONArray("packages").let { array ->
                    (0 until array.length()).mapTo(linkedSetOf()) { array.getString(it) }
                }.intersect(appPackages)
                if (packages.isEmpty()) null else FocusSession(
                    packages,
                    TimedBlock(item.getJSONObject("start").anchor(), item.getLong("duration")),
                )
            },
            openEvents = root.optJSONArray("openEvents")?.objects()?.map {
                OpenEvent(it.getString("package"), it.getLong("wall"))
            }.orEmpty().filter { it.packageName in appPackages }.takeLast(UsageEngine.MAX_OPEN_EVENTS),
            travelException = root.optJSONObject("travelException")?.let {
                TravelException(it.getLong("startWall"), it.getLong("endWall"))
            },
            travelExceptionConfigured = root.optBoolean("travelExceptionConfigured", false),
        )
    }

    private fun migrateV2(raw: String): ProtectionSnapshot {
        val root = JSONObject(raw)
        require(root.getInt("version") == 2)
        val zone = root.getString("zone")
        val day = root.getLong("day")
        val apps = root.getJSONArray("apps").objects().map { item ->
            val limits = item.getJSONObject("limits").v2Limits()
            val pending = item.optJSONObject("pending")?.let {
                val effectiveDay = it.getLong("effectiveDay")
                PendingPolicy(AppPolicy(it.getJSONObject("limits").v2Limits()), effectiveDay, dayWall(effectiveDay, zone))
            }
            val removalDay = item.nullableLong("pendingRemovalDay")
            ProtectedApp(
                packageName = item.getString("package"),
                label = item.getString("label"),
                limits = limits,
                policy = AppPolicy(limits),
                pendingPolicy = pending,
                pendingRemoval = removalDay?.let { PendingRemoval(it, dayWall(it, zone)) },
                usedTodayMillis = item.getLong("used"),
                sessionUsedMillis = item.getLong("session"),
                foregroundSince = item.optJSONObject("foreground")?.anchor(),
                backgroundSince = item.optJSONObject("background")?.anchor(),
                cooldown = item.optJSONObject("cooldown")?.let {
                    TimedBlock(it.getJSONObject("start").anchor(), it.getLong("duration"))
                },
                dailyBlocked = item.getBoolean("dailyBlocked"),
                needsLimitReview = item.optBoolean("needsReview", false),
            )
        }
        return ProtectionSnapshot(
            root.getString("zone"), day, root.getJSONObject("clock").anchor(), root.getLong("cursorWall"), apps,
        )
    }

    private fun migrateV1(raw: String, now: ClockReading): ProtectionSnapshot {
        val root = JSONObject(raw)
        require(root.getInt("version") == 1)
        val base = UsageEngine.newSnapshot(now, ZoneId.systemDefault().id)
        val apps = root.getJSONArray("apps").objects().map { item ->
            val limits = productionLimits()
            ProtectedApp(item.getString("package"), item.getString("label"), limits, needsLimitReview = true)
        }
        return base.copy(recoveryCursorWallMillis = UsageEngine.dayStartWall(base), apps = apps.distinctBy { it.packageName })
    }

    private fun ProtectedApp.json() = JSONObject()
        .put("package", packageName).put("label", label).put("limits", limits.json()).put("policy", policy.json())
        .put("pendingPolicy", pendingPolicy?.let {
            JSONObject().put("day", it.effectiveEpochDay).put("wall", it.effectiveWallMillis).put("policy", it.policy.json())
        })
        .put("pendingRemoval", pendingRemoval?.let {
            JSONObject().put("day", it.effectiveEpochDay).put("wall", it.effectiveWallMillis)
        })
        .put("used", usedTodayMillis).put("session", sessionUsedMillis)
        .put("sessionStartedAt", sessionStartedAt?.json())
        .put("foreground", foregroundSince?.json()).put("background", backgroundSince?.json())
        .put("cooldown", cooldown?.let { JSONObject().put("start", it.startedAt.json()).put("duration", it.durationMillis) })
        .put("dailyBlocked", dailyBlocked).put("manualBlocked", manualBlocked)
        .put("limitEvents", limitEventsToday).put("needsReview", needsLimitReview)
        .put("emergencyRequest", emergencyRequest?.let {
            JSONObject().put("start", it.startedAt.json()).put("duration", it.durationMillis)
        })
        .put("emergencyAccess", emergencyAccess?.let {
            JSONObject().put("start", it.startedAt.json()).put("duration", it.durationMillis)
        })
        .put("emergencyDay", emergencyUsedEpochDay)
        .put("emergencyReason", emergencyReason)
        .put("longestSession", longestSessionTodayMillis)
        .put("intentionalOpen", intentionalOpenEnabled)
        .put("intentionalDisableWall", intentionalDisableAtWallMillis)
        .put("plannedAccess", plannedAccess?.let {
            JSONObject().put("start", it.startedAt.json()).put("duration", it.durationMillis)
        })
        .put("plannedUsageRemaining", plannedUsageRemainingMillis)
        .put("plannedReason", plannedReason)

    private fun JSONObject.app(): ProtectedApp = ProtectedApp(
        packageName = getString("package"),
        label = getString("label"),
        limits = getJSONObject("limits").limits(),
        policy = getJSONObject("policy").policy(),
        pendingPolicy = optJSONObject("pendingPolicy")?.let {
            PendingPolicy(it.getJSONObject("policy").policy(), it.getLong("day"), it.getLong("wall"))
        },
        pendingRemoval = optJSONObject("pendingRemoval")?.let { PendingRemoval(it.getLong("day"), it.getLong("wall")) },
        usedTodayMillis = getLong("used"),
        sessionUsedMillis = getLong("session"),
        sessionStartedAt = optJSONObject("sessionStartedAt")?.anchor(),
        foregroundSince = optJSONObject("foreground")?.anchor(),
        backgroundSince = optJSONObject("background")?.anchor(),
        cooldown = optJSONObject("cooldown")?.let { TimedBlock(it.getJSONObject("start").anchor(), it.getLong("duration")) },
        dailyBlocked = getBoolean("dailyBlocked"),
        manualBlocked = optBoolean("manualBlocked", false),
        limitEventsToday = optInt("limitEvents", 0),
        needsLimitReview = optBoolean("needsReview", false),
        emergencyRequest = optJSONObject("emergencyRequest")?.let {
            TimedBlock(it.getJSONObject("start").anchor(), it.getLong("duration"))
        },
        emergencyAccess = optJSONObject("emergencyAccess")?.let {
            TimedBlock(it.getJSONObject("start").anchor(), it.getLong("duration"))
        },
        emergencyUsedEpochDay = nullableLong("emergencyDay"),
        emergencyReason = if (!has("emergencyReason") || isNull("emergencyReason")) null else getString("emergencyReason"),
        longestSessionTodayMillis = optLong("longestSession", 0),
        intentionalOpenEnabled = optBoolean("intentionalOpen", false),
        intentionalDisableAtWallMillis = nullableLong("intentionalDisableWall"),
        plannedAccess = optJSONObject("plannedAccess")?.let {
            TimedBlock(it.getJSONObject("start").anchor(), it.getLong("duration"))
        },
        plannedUsageRemainingMillis = nullableLong("plannedUsageRemaining"),
        plannedReason = if (!has("plannedReason") || isNull("plannedReason")) null else getString("plannedReason"),
    )

    private fun ProtectedGroup.json() = JSONObject()
        .put("id", id)
        .put("policy", policy.json())
        .put("pendingPolicy", pendingPolicy?.let {
            JSONObject().put("day", it.effectiveEpochDay).put("wall", it.effectiveWallMillis)
                .put("policy", it.policy.json())
        })
        .put("pendingRemoval", pendingRemoval?.let {
            JSONObject().put("day", it.effectiveEpochDay).put("wall", it.effectiveWallMillis)
        })

    private fun JSONObject.group() = ProtectedGroup(
        id = getString("id"),
        policy = getJSONObject("policy").groupPolicy(),
        pendingPolicy = optJSONObject("pendingPolicy")?.let {
            PendingGroupPolicy(it.getJSONObject("policy").groupPolicy(), it.getLong("day"), it.getLong("wall"))
        },
        pendingRemoval = optJSONObject("pendingRemoval")?.let {
            PendingRemoval(it.getLong("day"), it.getLong("wall"))
        },
    )

    private fun ProtectedSchedule.json() = JSONObject()
        .put("id", id)
        .put("policy", policy.json())
        .put("pendingPolicy", pendingPolicy?.let {
            JSONObject().put("day", it.effectiveEpochDay).put("wall", it.effectiveWallMillis)
                .put("policy", it.policy.json())
        })
        .put("pendingRemoval", pendingRemoval?.let {
            JSONObject().put("day", it.effectiveEpochDay).put("wall", it.effectiveWallMillis)
        })

    private fun JSONObject.schedule() = ProtectedSchedule(
        id = getString("id"),
        policy = getJSONObject("policy").schedulePolicy(),
        pendingPolicy = optJSONObject("pendingPolicy")?.let {
            PendingSchedulePolicy(it.getJSONObject("policy").schedulePolicy(), it.getLong("day"), it.getLong("wall"))
        },
        pendingRemoval = optJSONObject("pendingRemoval")?.let {
            PendingRemoval(it.getLong("day"), it.getLong("wall"))
        },
    )

    private fun DailyRecord.json() = JSONObject().put("day", epochDay).put("apps", JSONArray().apply {
        apps.forEach { put(JSONObject().put("package", it.packageName).put("label", it.label)
            .put("used", it.usedMillis).put("events", it.limitEvents).put("longestSession", it.longestSessionMillis)) }
    })

    private fun JSONObject.dailyRecord() = DailyRecord(getLong("day"), getJSONArray("apps").objects().map {
        DailyAppRecord(
            it.getString("package"), it.getString("label"), it.getLong("used"), it.getInt("events"),
            it.optLong("longestSession", 0),
        )
    })

    private fun encodeRules(snapshot: ProtectionSnapshot) = JSONObject()
        .put("format", "doom-blocker-rules")
        .put("version", 4)
        .put("strict", snapshot.strictModeEnabled)
        .put("apps", JSONArray().apply { snapshot.apps.forEach { app ->
            put(JSONObject().put("package", app.packageName).put("label", app.label)
                .put("policy", (app.pendingPolicy?.policy ?: app.policy).json())
                .put("intentionalOpen", app.intentionalOpenEnabled))
        } })
        .put("groups", JSONArray().apply { snapshot.groups.forEach { group ->
            put(JSONObject().put("id", group.id).put("policy", (group.pendingPolicy?.policy ?: group.policy).json()))
        } })
        .put("schedules", JSONArray().apply { snapshot.schedules.forEach { schedule ->
            put(JSONObject().put("id", schedule.id)
                .put("policy", (schedule.pendingPolicy?.policy ?: schedule.policy).json()))
        } }).toString(2)

    private data class Rule(
        val packageName: String,
        val label: String,
        val policy: AppPolicy,
        val intentionalOpen: Boolean?,
    )
    private data class GroupRule(val id: String, val policy: GroupPolicy)
    private data class ScheduleRule(val id: String, val policy: SchedulePolicy)
    private data class RuleBundle(
        val strictMode: Boolean,
        val rules: List<Rule>,
        val groups: List<GroupRule>,
        val schedules: List<ScheduleRule>,
    )

    private fun decodeRules(raw: String): RuleBundle {
        require(raw.toByteArray(Charsets.UTF_8).size <= MAX_IMPORT_BYTES) { "Yedek dosyası 1 MB sınırını aşıyor." }
        val root = JSONObject(raw)
        val version = root.getInt("version")
        require(root.getString("format") == "doom-blocker-rules" && version in 1..4) {
            "Bu dosya Doom Blocker kural yedeği değil."
        }
        val appArray = root.getJSONArray("apps")
        require(appArray.length() <= MAX_IMPORTED_APPS) { "Yedek en fazla $MAX_IMPORTED_APPS uygulama içerebilir." }
        val rules = appArray.objects().map {
            val label = it.getString("label")
            require(label.isNotBlank() && label.length <= MAX_LABEL_LENGTH) { "Uygulama adı geçersiz veya çok uzun." }
            Rule(
                it.getString("package"),
                label,
                it.getJSONObject("policy").policy(),
                if (version >= 4) it.optBoolean("intentionalOpen", false) else null,
            )
        }.distinctBy { it.packageName }
        val groupArray = root.optJSONArray("groups")
        require((groupArray?.length() ?: 0) <= MAX_IMPORTED_GROUPS) { "Yedek en fazla $MAX_IMPORTED_GROUPS ortak limit içerebilir." }
        val groups = if (version >= 2) groupArray?.objects()?.map {
            GroupRule(it.getString("id"), it.getJSONObject("policy").groupPolicy().also { policy ->
                require(policy.label.length <= MAX_LABEL_LENGTH) { "Ortak limit adı çok uzun." }
            })
        }.orEmpty().distinctBy { it.id } else emptyList()
        val scheduleArray = root.optJSONArray("schedules")
        require((scheduleArray?.length() ?: 0) <= MAX_IMPORTED_SCHEDULES) { "Yedek en fazla $MAX_IMPORTED_SCHEDULES program içerebilir." }
        val schedules = if (version >= 3) scheduleArray?.objects()?.map {
            ScheduleRule(it.getString("id"), it.getJSONObject("policy").schedulePolicy().also { policy ->
                require(policy.label.length <= MAX_LABEL_LENGTH) { "Program adı çok uzun." }
            })
        }.orEmpty().distinctBy { it.id } else emptyList()
        return RuleBundle(root.optBoolean("strict", false), rules, groups, schedules)
    }

    private companion object {
        const val PERSIST_INTERVAL_MILLIS = 30_000L
        const val MAX_IMPORT_BYTES = 1_048_576
        const val MAX_IMPORTED_APPS = 200
        const val MAX_IMPORTED_GROUPS = 100
        const val MAX_IMPORTED_SCHEDULES = 100
        const val MAX_LABEL_LENGTH = 80
        const val CORRUPT_PREFIX = "protection-corrupt-"
    }

    private fun AppPolicy.json() = JSONObject().put("weekday", weekdayLimits.json()).put("weekend", weekendLimits?.json())
    private fun JSONObject.policy() = AppPolicy(getJSONObject("weekday").limits(), optJSONObject("weekend")?.limits())
    private fun GroupPolicy.json() = JSONObject().put("label", label).put("daily", dailyMinutes)
        .put("unit", unitMillis).put("packages", JSONArray(packageNames.sorted()))
    private fun JSONObject.groupPolicy() = GroupPolicy(
        getString("label"),
        getJSONArray("packages").let { array -> (0 until array.length()).mapTo(linkedSetOf()) { array.getString(it) } },
        getInt("daily"),
        optLong("unit", 60_000L),
    )
    private fun SchedulePolicy.json() = JSONObject().put("label", label)
        .put("packages", JSONArray(packageNames.sorted())).put("days", daysMask)
        .put("startMinute", startMinuteOfDay).put("endMinute", endMinuteOfDay)
    private fun JSONObject.schedulePolicy() = SchedulePolicy(
        getString("label"),
        getJSONArray("packages").let { array -> (0 until array.length()).mapTo(linkedSetOf()) { array.getString(it) } },
        getInt("days"), getInt("startMinute"), getInt("endMinute"),
    )
    private fun Limits.json() = JSONObject().put("daily", dailyMinutes).put("session", sessionMinutes)
        .put("cooldown", cooldownMinutes).put("unit", unitMillis)
    private fun JSONObject.limits() = Limits(getInt("daily"), getInt("session"), getInt("cooldown"), optLong("unit", 60_000L))
    private fun JSONObject.v2Limits() = Limits(getInt("daily"), getInt("session"), getInt("cooldown"), 60_000L)
    private fun TimeAnchor.json() = JSONObject().put("wall", wallMillis).put("elapsed", elapsedMillis).put("boot", bootCount)
    private fun JSONObject.anchor() = TimeAnchor(getLong("wall"), getLong("elapsed"), getInt("boot"))
    private fun JSONObject.nullableLong(name: String) = if (!has(name) || isNull(name)) null else getLong(name)
    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
    private fun dayWall(day: Long, zoneId: String) = LocalDate.ofEpochDay(day).atStartOfDay(ZoneId.of(zoneId)).toInstant().toEpochMilli()
    private fun productionLimits() = Limits(unitMillis = timeUnitMillis)
    private fun AppPolicy.withUnit(unit: Long) = AppPolicy(
        weekdayLimits.copy(unitMillis = unit), weekendLimits?.copy(unitMillis = unit),
    )
    private fun GroupPolicy.withUnit(unit: Long) = copy(unitMillis = unit)
}
