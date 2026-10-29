package dev.personal.doomblocker.ui

import dev.personal.doomblocker.core.DailyAppRecord
import dev.personal.doomblocker.core.DailyRecord
import dev.personal.doomblocker.core.ProtectionSnapshot
import dev.personal.doomblocker.core.ClockReading
import dev.personal.doomblocker.core.UsageEngine
import java.time.LocalDate

internal data class UsageHeatCell(
    val epochDay: Long,
    val usedMillis: Long,
)

internal fun usageHeatmapCells(
    records: Map<Long, DailyRecord>,
    activeEpochDay: Long,
    rangeDays: Int,
): List<UsageHeatCell?> {
    require(rangeDays > 0) { "rangeDays must be positive" }
    val firstEpochDay = activeEpochDay - (rangeDays - 1)
    val leadingEmptyCells = LocalDate.ofEpochDay(firstEpochDay).dayOfWeek.value - 1
    val usageCells = (firstEpochDay..activeEpochDay).map { epochDay ->
        UsageHeatCell(
            epochDay = epochDay,
            usedMillis = records[epochDay]?.apps?.sumOf(DailyAppRecord::usedMillis) ?: 0L,
        )
    }
    val cells: List<UsageHeatCell?> = List(leadingEmptyCells) { null } + usageCells
    val trailingEmptyCells = (7 - cells.size % 7) % 7
    return cells + List(trailingEmptyCells) { null }
}

internal fun hasRecordedUsage(records: Collection<DailyRecord>): Boolean =
    records.any { day -> day.apps.any { it.usedMillis > 0L } }

/** A shared pool contributes once, bounded by its members' individual balances. */
internal fun remainingDailyBudget(snapshot: ProtectionSnapshot, now: ClockReading): Long {
    val used = snapshot.apps.associate { it.packageName to UsageEngine.view(snapshot, it, now).usedTodayMillis }
    val grouped = snapshot.groups.flatMap { it.policy.packageNames }.toSet()
    val individual = snapshot.apps.filter { it.packageName !in grouped }.sumOf {
        (it.limits.dailyMillis - used.getValue(it.packageName)).coerceAtLeast(0)
    }
    return individual + snapshot.groups.sumOf { group ->
        val members = snapshot.apps.filter { it.packageName in group.policy.packageNames }
        minOf((group.policy.dailyMillis - members.sumOf { used.getValue(it.packageName) }).coerceAtLeast(0),
            members.sumOf { (it.limits.dailyMillis - used.getValue(it.packageName)).coerceAtLeast(0) })
    }
}

internal fun totalDailyBudget(snapshot: ProtectionSnapshot): Long {
    val grouped = snapshot.groups.flatMap { it.policy.packageNames }.toSet()
    return snapshot.apps.filter { it.packageName !in grouped }.sumOf { it.limits.dailyMillis } +
        snapshot.groups.sumOf { group -> minOf(group.policy.dailyMillis,
            snapshot.apps.filter { it.packageName in group.policy.packageNames }.sumOf { it.limits.dailyMillis }) }
}

internal fun calendarRecords(records: Collection<DailyRecord>, endDay: Long, days: Int): List<DailyRecord> =
    records.filter { it.epochDay in (endDay - days + 1)..endDay }.sortedBy { it.epochDay }
