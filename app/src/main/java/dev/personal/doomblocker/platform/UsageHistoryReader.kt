package dev.personal.doomblocker.platform

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import dev.personal.doomblocker.core.UsageInterval

data class UsageRecovery(
    val usageByPackage: Map<String, Long>,
    val intervals: List<UsageInterval>,
    val finalForegroundPackage: String?,
    val coverageWarning: String? = null,
)

/** Reads package transitions with a lookback to establish screen and activity state. */
class UsageHistoryReader(private val context: Context) {
    fun read(
        packageNames: Set<String>,
        startWallMillis: Long,
        endWallMillis: Long,
        @Suppress("UNUSED_PARAMETER") initialForegroundPackage: String? = null,
    ): UsageRecovery {
        if (packageNames.isEmpty() || endWallMillis <= startWallMillis) return UsageRecovery(emptyMap(), emptyList(), null)
        check(DeviceAccess.hasUsageAccess(context)) { "Kullanım erişimi kapalı." }
        // Android retains events for only a few days. Never extend an old saved foreground
        // across an unobservable gap; only observed transitions establish usage.
        val start = maxOf(startWallMillis, endWallMillis - 2 * DAY_MILLIS)
        val events = context.getSystemService(UsageStatsManager::class.java)
            .queryEvents((start - DAY_MILLIS).coerceAtLeast(0), endWallMillis)
            ?: error("Kullanım olayları cihaz kilitliyken kullanılamıyor.")
        val accumulator = UsageEventAccumulator(packageNames, start, endWallMillis)
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val kind = when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> UsageEventKind.RESUMED
                UsageEvents.Event.ACTIVITY_PAUSED -> UsageEventKind.PAUSED
                UsageEvents.Event.ACTIVITY_STOPPED -> UsageEventKind.STOPPED
                UsageEvents.Event.SCREEN_INTERACTIVE -> UsageEventKind.SCREEN_ON
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> UsageEventKind.SCREEN_OFF
                UsageEvents.Event.KEYGUARD_SHOWN -> UsageEventKind.LOCKED
                UsageEvents.Event.KEYGUARD_HIDDEN -> UsageEventKind.UNLOCKED
                UsageEvents.Event.DEVICE_SHUTDOWN -> UsageEventKind.SHUTDOWN
                UsageEvents.Event.DEVICE_STARTUP -> UsageEventKind.STARTUP
                else -> UsageEventKind.OTHER
            }
            accumulator.accept(event.timeStamp, kind, event.packageName, event.className)
        }
        val result = accumulator.finish()
        return if (start != startWallMillis) result.copy(coverageWarning =
            "Uzun servis kesintisi: yalnız son 48 saatin erişilebilir olayları işlendi; eski aralıklar eksik olabilir.") else result
    }

    private companion object { const val DAY_MILLIS = 86_400_000L }
}
