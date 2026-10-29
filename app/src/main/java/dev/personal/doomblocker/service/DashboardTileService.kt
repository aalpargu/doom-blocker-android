package dev.personal.doomblocker.service

import android.app.PendingIntent
import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dev.personal.doomblocker.BlockerApplication
import dev.personal.doomblocker.R
import dev.personal.doomblocker.ui.MainActivity

/** Convenience tile that opens the dashboard. It cannot pause or weaken protection. */
class DashboardTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        val app = application as BlockerApplication
        val protection = app.repository.state.value
        val runtime = app.serviceStatus.state.value
        qsTile?.apply {
            label = getString(R.string.app_name)
            subtitle = when {
                !runtime.connected -> "Servis kapalı"
                runtime.problem != null -> "Kontrol gerekli"
                protection.snapshot?.apps?.isEmpty() != false -> "Hedef yok"
                else -> "${protection.snapshot?.apps?.size ?: 0} hedef korunuyor"
            }
            state = when {
                !runtime.connected || runtime.problem != null -> Tile.STATE_UNAVAILABLE
                protection.snapshot?.apps?.isEmpty() != false -> Tile.STATE_INACTIVE
                else -> Tile.STATE_ACTIVE
            }
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        val intent = Intent(this, MainActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP,
        )
        val pending = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        startActivityAndCollapse(pending)
    }
}
