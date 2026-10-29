package dev.personal.doomblocker

import android.app.Application
import android.content.ComponentName
import android.service.quicksettings.TileService
import dev.personal.doomblocker.data.ProtectionRepository
import dev.personal.doomblocker.data.RoutineProfileStore
import dev.personal.doomblocker.data.DiagnosticJournal
import dev.personal.doomblocker.platform.AppCatalog
import dev.personal.doomblocker.platform.DeviceClock
import dev.personal.doomblocker.platform.UsageHistoryReader
import dev.personal.doomblocker.platform.AppQuickActions
import dev.personal.doomblocker.service.ServiceStatus
import dev.personal.doomblocker.service.DashboardTileService
import dev.personal.doomblocker.service.FocusTileService
import dev.personal.doomblocker.widget.DoomWidgetProvider
import dev.personal.doomblocker.ui.UiPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class BlockerApplication : Application() {
    internal var intentGateShield: dev.personal.doomblocker.service.IntentGateShield? = null
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val clock by lazy { DeviceClock(this) }
    val catalog by lazy { AppCatalog(this) }
    val usageHistory by lazy { UsageHistoryReader(this) }
    val diagnostics by lazy { DiagnosticJournal(this) }
    val routines by lazy { RoutineProfileStore(this) }
    val repository by lazy { ProtectionRepository(this, appScope, clock, catalog, usageHistory, diagnostics,
        isServiceConnected = { serviceStatus.state.value.connected }) }
    val serviceStatus = ServiceStatus()
    val deviceSync by lazy { dev.personal.doomblocker.sync.DeviceSync(this, appScope, repository, clock) }
    val uiPreferences by lazy { UiPreferences(this) }

    override fun onCreate() {
        super.onCreate()
        repository.initialize()
        deviceSync.start()
        AppQuickActions.install(this)
        appScope.launch {
            combine(repository.state, serviceStatus.state) { protection, runtime -> protection to runtime }
                .map { (protection, runtime) ->
                    val now = clock.now()
                    Triple(dev.personal.doomblocker.platform.QuickSurfaceModels.widget(protection.snapshot, runtime, now),
                        dev.personal.doomblocker.platform.QuickSurfaceModels.focusTile(protection.snapshot, now, runtime.connected), runtime.connected)
                }.distinctUntilChanged().collect {
                val protection = repository.state.value
                val runtime = serviceStatus.state.value
                runCatching {
                TileService.requestListeningState(
                    this@BlockerApplication,
                    ComponentName(this@BlockerApplication, DashboardTileService::class.java),
                )
                TileService.requestListeningState(
                    this@BlockerApplication,
                    ComponentName(this@BlockerApplication, FocusTileService::class.java),
                )
                DoomWidgetProvider.updateAll(this@BlockerApplication, protection.snapshot, runtime)
                }.onFailure { diagnostics.add("UYARI", "Widget veya hızlı ayar görünümü güncellenemedi.") }
            }
        }
    }
}
