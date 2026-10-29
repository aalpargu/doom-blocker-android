package dev.personal.doomblocker.data

import dev.personal.doomblocker.core.ClockReading
import dev.personal.doomblocker.core.ProtectionSnapshot
import dev.personal.doomblocker.core.TravelException
import dev.personal.doomblocker.core.UsageEngine
import java.time.LocalDate
import java.time.ZoneId

/** The one-time bus trip bundled with the app. Persisted cancellations remain authoritative. */
internal object BundledTravelExceptionPlan {
    private val zone = ZoneId.of("Europe/Istanbul")
    val startWallMillis: Long = LocalDate.of(2026, 9, 26).atTime(22, 0)
        .atZone(zone).toInstant().toEpochMilli()
    /** Exclusive end: all of 27 September is included. */
    val endWallMillis: Long = LocalDate.of(2026, 9, 28).atStartOfDay(zone)
        .toInstant().toEpochMilli()
    private val previousEndWallMillis: Long = LocalDate.of(2026, 9, 27).atTime(11, 0)
        .atZone(zone).toInstant().toEpochMilli()

    fun apply(snapshot: ProtectionSnapshot, now: ClockReading): ProtectionSnapshot {
        if (snapshot.travelExceptionConfigured) {
            val existing = snapshot.travelException
            // Upgrade only the exact 3.1.2 bundled window. A null value means the user canceled it.
            return if (existing?.startWallMillis == startWallMillis &&
                existing?.endWallMillis == previousEndWallMillis
            ) snapshot.copy(travelException = TravelException(startWallMillis, endWallMillis))
            else snapshot
        }
        val needsWindowForRecovery = snapshot.recoveryCursorWallMillis < endWallMillis &&
            now.wallMillis >= startWallMillis
        return snapshot.copy(
            travelException = if (UsageEngine.trustedWall(snapshot, now) < endWallMillis || needsWindowForRecovery)
                TravelException(startWallMillis, endWallMillis) else null,
            travelExceptionConfigured = true,
        )
    }
}
