package dev.personal.doomblocker.sync

import dev.personal.doomblocker.core.ClockReading
import dev.personal.doomblocker.core.ProtectionSnapshot
import dev.personal.doomblocker.core.PlannedEntry
import dev.personal.doomblocker.core.UsageEngine

data class SharedApp(
    val packageName: String, val label: String, val dailyMillis: Long,
    val usedMillis: Long, val deviceUsedMillis: Long, val cooldownMillis: Long,
    val pendingDailyMillis: Long? = null, val pendingDay: String? = null,
)

data class SharedQuotaState(
    val linked: Boolean = false, val email: String? = null,
    val day: Long? = null, val zone: String? = null,
    val receivedElapsed: Long = 0, val receivedBoot: Int = -1,
    val cooldownReceivedElapsed: Long = receivedElapsed,
    val apps: List<SharedApp> = emptyList(), val devices: List<String> = emptyList(),
    val error: String? = null, val busy: Boolean = false,
)

object SharedQuota {
    const val FRESH_MILLIS = 15_000L

    fun travelActive(snapshot: ProtectionSnapshot, now: ClockReading): Boolean =
        snapshot.travelException?.isActive(UsageEngine.trustedWall(snapshot, now)) == true

    fun fresh(state: SharedQuotaState, snapshot: ProtectionSnapshot, now: ClockReading): Boolean =
        state.day == snapshot.activeEpochDay && state.zone == snapshot.quotaZoneId &&
            state.receivedBoot == now.bootCount && now.elapsedMillis - state.receivedElapsed in 0..FRESH_MILLIS

    fun remaining(app: SharedApp, localUsed: Long): Long =
        (app.dailyMillis - app.usedMillis - (localUsed - app.deviceUsedMillis).coerceAtLeast(0)).coerceAtLeast(0)

    fun capEntry(entry: PlannedEntry?, state: SharedQuotaState, snapshot: ProtectionSnapshot,
                 packageName: String, now: ClockReading): PlannedEntry? {
        if (entry == null) return null
        if (travelActive(snapshot, now)) return entry
        if (!state.linked) return entry
        val local = snapshot.apps.firstOrNull { it.packageName == packageName }
        val shared = state.apps.firstOrNull { it.packageName == packageName }
        val available = if (fresh(state, snapshot, now) && local != null && shared != null)
            remaining(shared, UsageEngine.view(snapshot, local, now).usedTodayMillis) else 0L
        return entry.copy(availableMillis = minOf(entry.availableMillis, available),
            existingMillis = minOf(entry.existingMillis, available))
    }

    fun cooldown(app: SharedApp, state: SharedQuotaState, now: ClockReading): Long =
        (app.cooldownMillis - (now.elapsedMillis - state.cooldownReceivedElapsed).coerceAtLeast(0)).coerceAtLeast(0)

    fun blockMessage(state: SharedQuotaState, snapshot: ProtectionSnapshot, packageName: String, now: ClockReading): String? {
        if (travelActive(snapshot, now)) return null
        if (!state.linked) return null
        val local = snapshot.apps.firstOrNull { it.packageName == packageName }
        val shared = state.apps.firstOrNull { it.packageName == packageName }
        if (local == null && shared == null) return null
        if (!fresh(state, snapshot, now)) return "Ortak kota güncelleniyor. Bağlantını kontrol edip yeniden dene."
        if (local == null) return "Bu cihazda koruma kuralları hazırlanıyor. Doom Blocker'ı aç."
        if (shared == null) return "Bu uygulamanın ortak kotası hazırlanıyor. Birkaç saniye sonra yeniden dene."
        val used = UsageEngine.view(snapshot, local, now).usedTodayMillis
        if (remaining(shared, used) <= 0) return "Telefon ve tabletteki toplam günlük hakkın doldu."
        if (cooldown(shared, state, now) > 0) return "Ortak mola devam ediyor. Telefon ve tablette aynı mola geçerli."
        return null
    }
}
