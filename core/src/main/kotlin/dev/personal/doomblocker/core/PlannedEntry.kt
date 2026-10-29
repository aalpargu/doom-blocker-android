package dev.personal.doomblocker.core

import java.time.LocalDate
import java.time.ZoneId

/** The gate and the grant use the same quota calculation; the selected time is an active-use budget. */
data class PlannedEntry(
    val availableMillis: Long,
    val existingMillis: Long,
    val unitMillis: Long,
    val cooldownMillis: Long,
    val block: BlockDecision?,
    val nextSchedule: UpcomingSchedule?,
)

data class UpcomingSchedule(val label: String, val startsAtWallMillis: Long)

/** A deferred edit/removal must not advertise a start belonging to a superseded rule. */
internal fun nextScheduledBlock(snapshot: ProtectionSnapshot, packageName: String, wall: Long): UpcomingSchedule? {
    val zone = ZoneId.of(snapshot.quotaZoneId)
    fun effectiveAt(day: Long, time: Long) = maxOf(time, LocalDate.ofEpochDay(day).atStartOfDay(zone).toInstant().toEpochMilli())
    return snapshot.schedules.mapNotNull { schedule ->
        val pending = schedule.pendingPolicy
        val changeAt = pending?.let { effectiveAt(it.effectiveEpochDay, it.effectiveWallMillis) }
        val removalAt = schedule.pendingRemoval?.let { effectiveAt(it.effectiveEpochDay, it.effectiveWallMillis) }
        val candidates = mutableListOf(schedule.policy.nextStartWallMillis(wall, snapshot.quotaZoneId))
        if (pending != null && changeAt != null) {
            if (changeAt > wall) candidates += changeAt
            candidates += pending.policy.nextStartWallMillis(maxOf(wall, changeAt - 1), snapshot.quotaZoneId)
        }
        candidates.sorted().firstNotNullOfOrNull { start ->
            val policy = if (changeAt != null && start >= changeAt) pending!!.policy else schedule.policy
            if (start <= wall || removalAt?.let { start >= it } == true || packageName !in policy.packageNames ||
                !policy.isActive(start, snapshot.quotaZoneId)) null
            else UpcomingSchedule(policy.label, start)
        }
    }.minByOrNull { it.startsAtWallMillis }
}
