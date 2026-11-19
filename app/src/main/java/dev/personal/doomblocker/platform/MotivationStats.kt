package dev.personal.doomblocker.platform

import dev.personal.doomblocker.core.ClockReading
import dev.personal.doomblocker.core.ProtectionSnapshot
import dev.personal.doomblocker.core.UsageEngine
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/**
 * Motivation numbers computed only from recorded history (nothing is estimated):
 *
 * - **Streak:** consecutive finished days on which no protected app used up its whole daily
 *   allowance (today counts too while it still holds).
 * - **Reclaimed this month:** daily allowance you did *not* use, summed over this month's days.
 *   Allowances are today's limits, applied to the apps recorded on each day.
 * - **Weekly comparison:** total protected-app use in the last finished Monday–Sunday week vs
 *   the week before it, only when both weeks have recorded days.
 */
data class MotivationSummary(
    val streakDays: Int,
    val reclaimedThisMonthMillis: Long,
    val lastWeekMillis: Long?,
    val previousWeekMillis: Long?,
    /** Oldest → today; true = within limits, false = a daily allowance ran out, null = no record. */
    val lastSevenDays: List<Boolean?> = emptyList(),
) {
    /** Positive = less use than the week before. */
    val weeklySavedMillis: Long? get() =
        if (lastWeekMillis != null && previousWeekMillis != null) previousWeekMillis - lastWeekMillis else null
}

object MotivationStats {
    fun summary(snapshot: ProtectionSnapshot, now: ClockReading): MotivationSummary {
        val limits = snapshot.apps.associate { it.packageName to it.limits.dailyMillis }
        val today = snapshot.activeEpochDay
        val todayUsage = snapshot.apps.associate { it.packageName to UsageEngine.view(snapshot, it, now).usedTodayMillis }
        val days: Map<Long, Map<String, Long>> = snapshot.history
            .associate { day -> day.epochDay to day.apps.associate { it.packageName to it.usedMillis } } +
            (today to todayUsage)

        fun dayOk(usage: Map<String, Long>) = usage.all { (pkg, used) -> limits[pkg]?.let { used < it } ?: true }

        // Streak: today (if still fine) plus consecutive earlier days that are fine.
        var streak = 0
        if (dayOk(todayUsage)) streak++
        var day = today - 1
        while (true) {
            val usage = days[day] ?: break
            if (!dayOk(usage)) break
            streak++
            day--
        }

        val date = LocalDate.ofEpochDay(today)
        val monthStart = date.withDayOfMonth(1).toEpochDay()
        val reclaimed = days.filterKeys { it in monthStart..today }.values.sumOf { usage ->
            usage.entries.sumOf { (pkg, used) -> ((limits[pkg] ?: 0L) - used).coerceAtLeast(0) }
        }

        val thisMonday = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toEpochDay()
        fun weekTotal(start: Long): Long? {
            val recorded = (start until start + 7).mapNotNull { days[it] }
            return if (recorded.isEmpty()) null else recorded.sumOf { it.values.sum() }
        }
        return MotivationSummary(
            streakDays = streak,
            reclaimedThisMonthMillis = reclaimed,
            lastWeekMillis = weekTotal(thisMonday - 7),
            previousWeekMillis = weekTotal(thisMonday - 14),
            lastSevenDays = (today - 6..today).map { d -> days[d]?.let(::dayOk) },
        )
    }

    fun hoursMinutes(millis: Long): String {
        val minutes = (millis / 60_000).coerceAtLeast(0)
        return when {
            minutes >= 60 && minutes % 60 == 0L -> "${minutes / 60} sa"
            minutes >= 60 -> "${minutes / 60} sa ${minutes % 60} dk"
            else -> "$minutes dk"
        }
    }
}
