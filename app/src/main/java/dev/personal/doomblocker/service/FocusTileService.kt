package dev.personal.doomblocker.service

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import dev.personal.doomblocker.BlockerApplication
import dev.personal.doomblocker.R
import dev.personal.doomblocker.platform.FocusTileState
import dev.personal.doomblocker.platform.FocusFeedback
import dev.personal.doomblocker.platform.QuickSurfaceModels
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Starts a non-cancellable 30 minute focus session for all protected apps. */
class FocusTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        updateState()
    }

    override fun onClick() {
        super.onClick()
        val app = application as BlockerApplication
        app.appScope.launch {
            val protection = app.repository.state.filter { it.ready || it.error != null }.first()
            val packages = protection.snapshot?.apps.orEmpty().mapTo(linkedSetOf()) { it.packageName }
            if (packages.isEmpty()) {
                Toast.makeText(this@FocusTileService, "Önce korunan bir uygulama ekle.", Toast.LENGTH_SHORT).show()
                updateState()
                return@launch
            }
            app.repository.startFocusSession(packages, 30).fold(
                onSuccess = {
                    FocusFeedback.perform(this@FocusTileService)
                    Toast.makeText(this@FocusTileService, "30 dakikalık odak başladı.", Toast.LENGTH_SHORT).show()
                    updateState()
                },
                onFailure = {
                    Toast.makeText(this@FocusTileService, it.message ?: "Odak başlatılamadı.", Toast.LENGTH_SHORT).show()
                },
            )
        }
    }

    private fun updateState() {
        val app = application as BlockerApplication
        val snapshot = app.repository.state.value.snapshot
        val presentation = QuickSurfaceModels.focusTile(snapshot, app.clock.now(), app.serviceStatus.state.value.connected)
        qsTile?.apply {
            label = getString(R.string.focus_tile_name)
            subtitle = presentation.subtitle
            state = when (presentation.state) {
                FocusTileState.UNAVAILABLE -> Tile.STATE_UNAVAILABLE
                FocusTileState.ACTIVE -> Tile.STATE_ACTIVE
                FocusTileState.INACTIVE -> Tile.STATE_INACTIVE
            }
            updateTile()
        }
    }
}
