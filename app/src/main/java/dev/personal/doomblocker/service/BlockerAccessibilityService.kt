package dev.personal.doomblocker.service

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import dev.personal.doomblocker.BlockerApplication
import dev.personal.doomblocker.R
import dev.personal.doomblocker.core.BlockDecision
import dev.personal.doomblocker.core.BlockReason
import dev.personal.doomblocker.core.EngineResult
import dev.personal.doomblocker.ui.IntentGateActivity
import dev.personal.doomblocker.ui.CooldownActivity
import dev.personal.doomblocker.ui.showsCooldownScreen
import dev.personal.doomblocker.sync.SharedQuota
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class BlockerAccessibilityService : AccessibilityService() {
    private val app get() = application as BlockerApplication
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val evaluationMutex = Mutex()
    private val handler = Handler(Looper.getMainLooper())
    private var notice: Toast? = null
    private var connected = false
    private var receiverRegistered = false
    private var lastAttemptElapsed = Long.MIN_VALUE
    private var lastAttemptPackage: String? = null
    private var lastObservedPackage: String? = null
    private var attempts = 0
    private var evaluating = false
    private var foregroundCheckPending = false
    private var lastSharedBlockElapsed = Long.MIN_VALUE
    private var measurementReady = false
    private var probeRequestWall: Long? = null
    private val foregroundStabilizer = ForegroundStabilizer()
    private val limitNotifications by lazy { LimitNotificationManager(this) }
    private val scheduledTick = Runnable { processForeground(null) }
    private val scheduledRecheck = Runnable { processForeground(null) }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { processForeground(null) }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        if (connected) return
        connected = true
        app.intentGateShield = IntentGateShield(this) {
            performGlobalAction(GLOBAL_ACTION_HOME)
            reportFailure("Bilinçli giriş ekranı zamanında çizilemedi; Ana ekrana dönüldü.")
        }
        app.serviceStatus.update {
            it.copy(connected = true, connectedAtWallMillis = System.currentTimeMillis(), problem = null)
        }
        app.appScope.launch { app.diagnostics.add("SERVİS", "Erişilebilirlik servisi bağlandı.") }
        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }, RECEIVER_NOT_EXPORTED)
        receiverRegistered = true
        scope.launch {
            app.repository.state.filter { it.ready || it.error != null }.first()
            app.repository.reconcileMeasurement()
                .onFailure { reportFailure("Servis kesintisinin kullanımı tamamlanamadı; ölçüm eksik olabilir.") }
            measurementReady = true
            processForeground(null)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!connected || event == null) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) processForeground(
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) event.packageName?.toString() else null,
            event.windowId,
        )
    }

    private fun processForeground(eventPackage: String?, eventWindowId: Int = -1) {
        if (!connected || !measurementReady) return
        if (evaluating) {
            foregroundCheckPending = true
            return
        }
        foregroundCheckPending = false
        evaluating = true
        scope.launch {
            try {
            evaluationMutex.withLock {
                handler.removeCallbacks(scheduledTick)
                val interactive = getSystemService(PowerManager::class.java).isInteractive &&
                    !getSystemService(KeyguardManager::class.java).isKeyguardLocked
                val observation = if (interactive) observeWindow(eventPackage, eventWindowId) else {
                    foregroundStabilizer.clear()
                    app.intentGateShield?.dismiss()
                    WindowObservation(null, false, false)
                }
                if (!observation.provisional) app.intentGateShield?.onForeground(observation.packageName)
                if (observation.packageName != lastObservedPackage) {
                    lastObservedPackage = observation.packageName
                    app.appScope.launch { app.diagnostics.add(
                        "ÖN PLAN",
                        if (interactive) "Odak değişti." else "Ekran kilitli veya etkileşimsiz.",
                        observation.packageName,
                    ) }
                }
                app.serviceStatus.update {
                    it.copy(foregroundPackage = observation.packageName, pictureInPictureVisible = observation.pictureInPicture)
                }
                val travelActive = app.repository.state.value.snapshot?.let {
                    SharedQuota.travelActive(it, app.clock.now())
                } == true
                if (travelActive) app.intentGateShield?.dismiss()
                val probeAccepted = handleProtectionProbe(observation.packageName)
                val sharedBlock = observation.packageName?.let { pkg ->
                    app.repository.state.value.snapshot?.let {
                        SharedQuota.blockMessage(app.deviceSync.state.value, it, pkg, app.clock.now())
                    }
                }
                if (interactive && sharedBlock != null && probeAccepted != true) {
                    val elapsed = SystemClock.elapsedRealtime()
                    if (lastSharedBlockElapsed == Long.MIN_VALUE || elapsed - lastSharedBlockElapsed >= 600) {
                        lastSharedBlockElapsed = elapsed
                        performGlobalAction(GLOBAL_ACTION_HOME)
                        showNotice(sharedBlock)
                    }
                    app.appScope.launch { app.deviceSync.sync() }
                }
                app.repository.onForeground(observation.packageName, interactive) { result ->
                    val decision = result.decision
                    if (decision?.reason == BlockReason.INTENTION_REQUIRED && probeAccepted != true &&
                        sharedBlock == null && !SharedQuota.travelActive(result.snapshot, app.clock.now())) {
                        withContext(Dispatchers.Main.immediate) {
                            // A newer window event invalidates this launch; drain it immediately below.
                            if (connected && !foregroundCheckPending &&
                                getSystemService(PowerManager::class.java).isInteractive &&
                                !getSystemService(KeyguardManager::class.java).isKeyguardLocked) {
                                enforce(decision)
                            }
                        }
                    }
                }
                    .onSuccess {
                        app.serviceStatus.update { status ->
                            status.copy(lastEvaluationWallMillis = System.currentTimeMillis())
                        }
                        if (probeAccepted != true && !foregroundCheckPending && sharedBlock == null) handleResult(it)
                        if (observation.provisional) {
                            handler.removeCallbacks(scheduledRecheck)
                            handler.postDelayed(scheduledRecheck, 400L)
                        }
                    }
                    .onFailure { reportFailure("Kullanım durumu kaydedilemedi.") }
            }
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                reportFailure("Koruma kontrolü tamamlanamadı; otomatik yeniden deneniyor.")
            } finally {
                evaluating = false
                if (connected && foregroundCheckPending) {
                    handler.removeCallbacks(scheduledRecheck)
                    handler.post(scheduledRecheck)
                }
                // A missing window/event or a failed write must not silently stop the service.
                // Reconcile idle timers, schedules, midnight and Quick Settings changes too.
                if (connected && !handler.hasCallbacks(scheduledTick)) {
                    val screenOn = getSystemService(PowerManager::class.java).isInteractive
                    handler.postDelayed(scheduledTick, if (!screenOn) 60_000L
                        else if (app.deviceSync.state.value.linked) 1_000L else 5_000L)
                }
            }
        }
    }

    private fun handleResult(result: EngineResult) {
        handler.removeCallbacks(scheduledTick)
        val travelActive = SharedQuota.travelActive(result.snapshot, app.clock.now())
        if (travelActive) app.intentGateShield?.dismiss()
        result.decision?.takeUnless { travelActive }?.let { decision ->
            app.appScope.launch { app.diagnostics.add("ENGEL", decision.reason.name, decision.packageName) }
            // Intention gates were dispatched before the durable write.
            if (decision.reason != BlockReason.INTENTION_REQUIRED) enforce(decision)
        }
        result.nextWakeupMillis?.let {
            handler.postDelayed(scheduledTick, if (app.deviceSync.state.value.linked) it.coerceIn(1L, 1_000L)
                else it.coerceAtLeast(1))
        }
        runCatching { limitNotifications.onEngineResult(result, app.clock.now()) }
            .onFailure { reportFailure("Bildirim gösterilemedi; uygulama sınırları uygulanmaya devam ediyor.") }
    }

    private fun enforce(decision: BlockDecision) {
        if (decision.reason == BlockReason.INTENTION_REQUIRED &&
            app.intentGateShield?.isShowingFor(decision.packageName) == true) return
        val elapsed = SystemClock.elapsedRealtime()
        if (lastAttemptPackage != decision.packageName) {
            attempts = 0
            lastAttemptElapsed = Long.MIN_VALUE
        }
        if (lastAttemptElapsed != Long.MIN_VALUE && elapsed - lastAttemptElapsed < 500) {
            handler.removeCallbacks(scheduledRecheck)
            handler.postDelayed(scheduledRecheck, 500 - (elapsed - lastAttemptElapsed))
            return
        }
        lastAttemptPackage = decision.packageName
        lastAttemptElapsed = elapsed
        attempts++
        if (decision.reason == BlockReason.INTENTION_REQUIRED) {
            notice?.cancel()
            app.serviceStatus.update { it.copy(lastBlockedPackage = decision.packageName) }
            val token = runCatching { app.intentGateShield?.show(decision.packageName) ?: 0L }
                .getOrElse {
                    reportFailure("Geçiş örtüsü açılamadı; bilinçli giriş doğrudan açılıyor.")
                    0L
                }
            runCatching {
                startActivity(Intent(this, IntentGateActivity::class.java)
                    .putExtra(IntentGateActivity.EXTRA_PACKAGE, decision.packageName)
                    .putExtra(IntentGateActivity.EXTRA_LABEL, decision.label)
                    .putExtra(IntentGateActivity.EXTRA_HANDOFF_TOKEN, token)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION))
            }.onFailure {
                performGlobalAction(GLOBAL_ACTION_HOME)
                app.intentGateShield?.dismiss()
                reportFailure("Bilinçli giriş ekranı açılamadı; Ana ekrana dönüş istendi.")
            }
            handler.removeCallbacks(scheduledRecheck)
            handler.postDelayed(scheduledRecheck, EnforcementRetryPolicy.delayAfterAttempt(attempts))
            return
        }
        val accepted = performGlobalAction(GLOBAL_ACTION_HOME)
        app.serviceStatus.update {
            it.copy(
                homeRequests = it.homeRequests + 1,
                lastBlockedPackage = decision.packageName,
                lastHomeAccepted = accepted,
                problem = if (accepted) null else "Android Ana ekran isteğini kabul etmedi.",
            )
        }
        val cooldownScreen = showsCooldownScreen(decision.reason)
        if (!accepted || (attempts == 1 && !cooldownScreen)) showNotice(messageFor(decision, accepted))
        if (accepted && attempts == 1 && cooldownScreen) {
            notice?.cancel()
            handler.postDelayed({
                if (!connected || !getSystemService(PowerManager::class.java).isInteractive ||
                    getSystemService(KeyguardManager::class.java).isKeyguardLocked) return@postDelayed
                val target = app.repository.state.value.snapshot?.apps
                    ?.firstOrNull { it.packageName == decision.packageName }
                if ((target?.cooldown?.remainingMillis(app.clock.now()) ?: 0L) <= 0) return@postDelayed
                runCatching {
                    startActivity(Intent(this, CooldownActivity::class.java)
                        .putExtra(CooldownActivity.EXTRA_PACKAGE, decision.packageName)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                }.onFailure {
                    showNotice(messageFor(decision, true))
                    reportFailure("Mola ekranı açılamadı; mola engeli devam ediyor.")
                }
            }, 180L)
        }
        if (attempts >= 3) {
            reportFailure("Hedef uygulama odakta kalıyor; Doom Blocker kontrollü biçimde yeniden deniyor.")
        }
        handler.removeCallbacks(scheduledRecheck)
        handler.postDelayed(scheduledRecheck, EnforcementRetryPolicy.delayAfterAttempt(attempts))
    }

    private fun handleProtectionProbe(packageName: String?): Boolean? {
        val status = app.serviceStatus.state.value
        if (status.probeResult != ProtectionProbeResult.RUNNING) return null
        val started = status.probeStartedWallMillis ?: return null
        if (System.currentTimeMillis() - started > PROBE_TIMEOUT_MILLIS) {
            app.serviceStatus.update { it.copy(probeResult = ProtectionProbeResult.TIMED_OUT) }
            return null
        }
        if (status.probeStartedWallMillis != probeRequestWall) probeRequestWall = null
        if (probeRequestWall != null) {
            if (packageName != null && packageName != status.probePackage) {
                app.serviceStatus.update { it.copy(probeResult = ProtectionProbeResult.PASSED) }
                app.appScope.launch { app.diagnostics.add("TEST", "Home isteğinden sonra hedefin odaktan çıktığı gözlendi.") }
                showNotice("Koruma testi başarılı: hedef uygulamadan çıkıldı.")
                probeRequestWall = null
            }
            return null
        }
        if (packageName != status.probePackage) return null
        val accepted = performGlobalAction(GLOBAL_ACTION_HOME)
        if (accepted) probeRequestWall = status.probeStartedWallMillis
        app.serviceStatus.update {
            it.copy(
                homeRequests = it.homeRequests + 1,
                lastHomeAccepted = accepted,
                lastBlockedPackage = packageName,
                probeResult = if (accepted) ProtectionProbeResult.RUNNING else ProtectionProbeResult.FAILED,
                problem = if (accepted) null else "Koruma testinde Android Ana ekran isteğini reddetti.",
            )
        }
        app.appScope.launch {
            app.diagnostics.add("TEST", if (accepted) "Home isteği kabul edildi; hedefin çıkışı bekleniyor." else "Koruma testi başarısız.", packageName)
        }
        if (!accepted) showNotice(getString(R.string.home_action_failed))
        handler.removeCallbacks(scheduledRecheck)
        handler.postDelayed(scheduledRecheck, 600L)
        return accepted
    }

    private fun messageFor(decision: BlockDecision, accepted: Boolean): String {
        if (!accepted) return getString(R.string.home_action_failed)
        return when (decision.reason) {
            BlockReason.DAILY_LIMIT -> getString(
                R.string.daily_block_notice, decision.label,
                formatDuration(decision.dailyLimitMillis),
            )
            BlockReason.GROUP_DAILY_LIMIT -> getString(
                R.string.group_block_notice,
                decision.groupLabel ?: "Ortak günlük",
                formatDuration(decision.groupUsedMillis),
                formatDuration(decision.groupLimitMillis),
            )
            BlockReason.MANUAL_LOCK -> getString(R.string.manual_block_notice, decision.label)
            BlockReason.SCHEDULED_BLOCK -> getString(
                R.string.schedule_block_notice,
                decision.scheduleLabel ?: "Program",
                decision.label,
                formatRemaining(decision.scheduleRemainingMillis),
            )
            BlockReason.SESSION_LIMIT -> getString(
                R.string.session_block_notice, decision.label,
                formatDuration(decision.sessionLimitMillis),
                formatDuration(decision.usedTodayMillis),
                formatDuration(decision.dailyLimitMillis),
                formatDuration(decision.cooldownRemainingMillis),
            )
            BlockReason.COOLDOWN -> getString(
                R.string.cooldown_block_notice, decision.label,
                formatRemaining(decision.cooldownRemainingMillis),
            )
            BlockReason.FOCUS_SESSION -> getString(R.string.focus_block_notice, decision.label)
            BlockReason.INTENTION_REQUIRED -> getString(R.string.intention_block_notice, decision.label)
            BlockReason.PLANNED_SESSION_END -> getString(R.string.planned_session_end_notice, decision.label)
        }
    }

    private fun observeWindow(eventPackage: String?, eventWindowId: Int): WindowObservation = try {
        val currentWindows = windows.orEmpty()
        val applicationWindow = currentWindows.firstOrNull {
            it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isFocused
        } ?: currentWindows.firstOrNull {
            it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isActive
        }
        // Only the root package is read. Nodes and screen text are never traversed.
        val observedPackage = ForegroundWindowPolicy.eventPackage(eventPackage, eventWindowId, applicationWindow?.id)
            ?: applicationWindow?.root?.packageName?.toString()
            ?: eventPackage.takeIf { currentWindows.isEmpty() }
        val stable = foregroundStabilizer.resolve(observedPackage, SystemClock.elapsedRealtime())
        val packageName = stable.packageName
        val pictureInPicture = currentWindows.any { it.isInPictureInPictureMode }
        if (packageName != null && packageName != lastAttemptPackage) {
            attempts = 0
            lastAttemptPackage = null
        }
        WindowObservation(packageName, pictureInPicture, stable.provisional)
    } catch (_: Exception) {
        reportFailure("Pencere bilgisi okunamadı. Erişilebilirlik servisini yeniden açın.")
        val stable = foregroundStabilizer.resolve(null, SystemClock.elapsedRealtime())
        WindowObservation(stable.packageName, false, stable.provisional)
    }

    private fun showNotice(message: String) {
        runCatching {
            notice?.cancel()
            notice = Toast.makeText(this, message, Toast.LENGTH_SHORT).also { it.show() }
        }.onFailure { reportFailure("Kısa uyarı gösterilemedi; engelleme kaydı korundu.") }
    }

    private fun reportFailure(message: String) {
        val changed = app.serviceStatus.state.value.problem != message
        app.serviceStatus.update { it.copy(problem = message) }
        if (changed) app.appScope.launch { app.diagnostics.add("HATA", message) }
    }

    override fun onInterrupt() {
        notice?.cancel()
        app.serviceStatus.update { it.copy(problem = "Android erişilebilirlik geri bildirimini kesti.") }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        disconnect()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        disconnect()
        scope.cancel()
        super.onDestroy()
    }

    private fun disconnect() {
        val shouldLog = connected || receiverRegistered
        connected = false
        measurementReady = false
        foregroundCheckPending = false
        app.intentGateShield?.dismiss()
        app.intentGateShield = null
        handler.removeCallbacksAndMessages(null)
        notice?.cancel()
        notice = null
        if (receiverRegistered) {
            unregisterReceiver(screenReceiver)
            receiverRegistered = false
        }
        app.serviceStatus.update { it.copy(connected = false, foregroundPackage = null) }
        lastObservedPackage = null
        foregroundStabilizer.clear()
        if (shouldLog) app.appScope.launch { app.repository.onForeground(null, false) }
        if (shouldLog) app.appScope.launch { app.diagnostics.add("SERVİS", "Erişilebilirlik servisi ayrıldı.") }
    }

    private data class WindowObservation(
        val packageName: String?,
        val pictureInPicture: Boolean,
        val provisional: Boolean,
    )

    private companion object {
        const val PROBE_TIMEOUT_MILLIS = 60_000L
    }

    private fun formatRemaining(millis: Long): String {
        val seconds = ((millis + 999) / 1_000).coerceAtLeast(0)
        return "%02d:%02d".format(java.util.Locale.getDefault(), seconds / 60, seconds % 60)
    }

    private fun formatDuration(millis: Long): String {
        val seconds = ((millis + 999) / 1_000).coerceAtLeast(0)
        return if (seconds < 60) "$seconds sn" else {
            val minutes = seconds / 60
            val rest = seconds % 60
            if (rest == 0L) "$minutes dk" else "$minutes dk $rest sn"
        }
    }
}
