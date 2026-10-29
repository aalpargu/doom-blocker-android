package dev.personal.doomblocker.service

import dev.personal.doomblocker.core.ProtectedApp
import dev.personal.doomblocker.core.AppView
import java.time.Instant
import java.time.ZoneId

internal object NotificationPolicy {
    fun sessionRemaining(app: ProtectedApp, view: AppView): Long =
        if (app.intentionalOpenEnabled) view.plannedAccessRemainingMillis
        else (app.limits.sessionMillis - view.sessionUsedMillis).coerceAtLeast(0)

    // The persisted start survives checkpoints, short background gaps, process restarts,
    // and midnight. A genuinely new session gets a new start anchor.
    fun sessionKey(app: ProtectedApp, quotaZoneId: String): String? = app.sessionStartedAt?.let {
        val startDay = Instant.ofEpochMilli(it.wallMillis).atZone(ZoneId.of(quotaZoneId))
            .toLocalDate().toEpochDay()
        "$startDay:${app.packageName}:session:${it.bootCount}:${it.wallMillis}"
    }
}
