package dev.personal.doomblocker.ui

import dev.personal.doomblocker.core.ClockReading
import dev.personal.doomblocker.core.DailyAppRecord
import dev.personal.doomblocker.core.DailyRecord
import dev.personal.doomblocker.core.ProtectionSnapshot
import dev.personal.doomblocker.core.UsageEngine
import java.util.Locale
import kotlin.math.roundToInt

/** A missing record is unknown, whereas a recorded day with zero usage is a known zero. */
internal data class HistoryDay(
    val epochDay: Long,
    val usedMillis: Long,
    val limitEvents: Int,
    val apps: List<DailyAppRecord>,
    val recorded: Boolean,
)

internal data class HistoryApp(
    val packageName: String,
    val label: String,
    val usedMillis: Long,
    val limitEvents: Int,
    val longestSessionMillis: Long,
    val daysWithLimit: Int,
    val dailyUsage: List<HistoryDay>,
)

internal data class HistoryInsight(
    val title: String,
    val body: String,
    val packageName: String? = null,
)

internal data class HistoryDashboard(
    val rangeDays: Int,
    val days: List<HistoryDay>,
    val totalMillis: Long,
    val averageMillis: Long,
    val limitEvents: Int,
    val longestSessionMillis: Long,
    val apps: List<HistoryApp>,
    val comparison: String?,
    val insight: HistoryInsight?,
)

/** Builds one consistent, period-scoped model for the chart, metrics, and app details. */
internal fun buildHistoryDashboard(
    snapshot: ProtectionSnapshot,
    now: ClockReading,
    rangeDays: Int,
): HistoryDashboard {
    require(rangeDays == 7 || rangeDays == 30) { "Only 7- and 30-day history is retained for display" }

    // The snapshot's active day owns its live counters. It replaces any stale copy in history.
    val current = DailyRecord(snapshot.activeEpochDay, snapshot.apps.map { app ->
        val view = UsageEngine.view(snapshot, app, now)
        DailyAppRecord(
            packageName = app.packageName,
            label = app.label,
            usedMillis = view.usedTodayMillis,
            limitEvents = app.limitEventsToday,
            longestSessionMillis = maxOf(app.longestSessionTodayMillis, view.sessionUsedMillis),
        )
    })
    val records = snapshot.history.associateByTo(mutableMapOf()) { it.epochDay }
    records[current.epochDay] = current

    val firstDay = snapshot.activeEpochDay - rangeDays + 1
    val days = (firstDay..snapshot.activeEpochDay).map { epochDay ->
        val record = records[epochDay]
        HistoryDay(
            epochDay = epochDay,
            usedMillis = record?.apps?.sumOf(DailyAppRecord::usedMillis) ?: 0L,
            limitEvents = record?.apps?.sumOf(DailyAppRecord::limitEvents) ?: 0,
            apps = record?.apps.orEmpty(),
            recorded = record != null,
        )
    }
    val totalMillis = days.sumOf(HistoryDay::usedMillis)
    val recordedDays = days.count(HistoryDay::recorded)
    // Unknown dates are never silently counted as zero in the daily average.
    val averageMillis = if (recordedDays == 0) 0 else totalMillis / recordedDays

    val latestHistoricalLabels = days.flatMap(HistoryDay::apps)
        .associate { it.packageName to it.label }
    val currentLabels = snapshot.apps.associate { it.packageName to it.label }
    val apps = days.flatMap(HistoryDay::apps).map(DailyAppRecord::packageName).distinct().map { packageName ->
        val label = currentLabels[packageName] ?: latestHistoricalLabels[packageName] ?: packageName
        val dailyUsage = days.map { day ->
            val recordsForApp = day.apps.filter { it.packageName == packageName }
            val appRecord = recordsForApp.takeIf { it.isNotEmpty() }?.let { recordsForDay ->
                DailyAppRecord(
                    packageName = packageName,
                    label = recordsForDay.last().label,
                    usedMillis = recordsForDay.sumOf(DailyAppRecord::usedMillis),
                    limitEvents = recordsForDay.sumOf(DailyAppRecord::limitEvents),
                    longestSessionMillis = recordsForDay.maxOf(DailyAppRecord::longestSessionMillis),
                )
            }
            HistoryDay(
                epochDay = day.epochDay,
                usedMillis = appRecord?.usedMillis ?: 0L,
                limitEvents = appRecord?.limitEvents ?: 0,
                apps = listOfNotNull(appRecord),
                // An app added later has no known zeroes before its first record.
                recorded = appRecord != null,
            )
        }
        HistoryApp(
            packageName = packageName,
            label = label,
            usedMillis = dailyUsage.sumOf(HistoryDay::usedMillis),
            limitEvents = dailyUsage.sumOf(HistoryDay::limitEvents),
            longestSessionMillis = dailyUsage.maxOfOrNull { it.apps.firstOrNull()?.longestSessionMillis ?: 0L } ?: 0L,
            daysWithLimit = dailyUsage.count { it.limitEvents > 0 },
            dailyUsage = dailyUsage,
        )
    }.sortedWith(
        compareByDescending<HistoryApp> { it.usedMillis }
            .thenByDescending { it.limitEvents }
            .thenBy { it.label.lowercase(Locale.ROOT) }
            .thenBy { it.packageName },
    )

    return HistoryDashboard(
        rangeDays = rangeDays,
        days = days,
        totalMillis = totalMillis,
        averageMillis = averageMillis,
        limitEvents = days.sumOf(HistoryDay::limitEvents),
        longestSessionMillis = days.maxOfOrNull { day ->
            day.apps.maxOfOrNull(DailyAppRecord::longestSessionMillis) ?: 0L
        } ?: 0L,
        apps = apps,
        comparison = if (rangeDays == 7) completedWeekComparison(records, snapshot.activeEpochDay) else null,
        insight = historyInsight(rangeDays, recordedDays, totalMillis, apps),
    )
}

private fun completedWeekComparison(records: Map<Long, DailyRecord>, activeDay: Long): String? {
    fun completeWeek(endDay: Long): Long? {
        val week = (endDay - 6..endDay).map { records[it] ?: return null }
        return week.sumOf { day -> day.apps.sumOf(DailyAppRecord::usedMillis) }
    }
    val latest = completeWeek(activeDay - 1) ?: return null
    val previous = completeWeek(activeDay - 8) ?: return null
    val prefix = "Son 7 tamamlanmış gün, önceki 7 tamamlanmış güne göre"
    if (latest == previous) return "$prefix aynı düzeyde."
    if (previous == 0L) return "Önceki 7 tamamlanmış günde kullanım yoktu; son 7 tamamlanmış günde ${formatHistoryDuration(latest)} kullanıldı."
    val changePercent = ((latest.toDouble() - previous.toDouble()) / previous * 100).roundToInt()
    if (changePercent == 0) return "$prefix çok yakın düzeyde."
    return "$prefix %${kotlin.math.abs(changePercent)} ${if (changePercent < 0) "azaldı" else "arttı"}."
}

private fun historyInsight(
    rangeDays: Int,
    recordedDays: Int,
    totalMillis: Long,
    apps: List<HistoryApp>,
): HistoryInsight? {
    // A sparse period gives too little context for a behavioral observation.
    if (recordedDays < (rangeDays + 1) / 2) return null

    val frequentLimit = apps.filter { app ->
        val appRecordedDays = app.dailyUsage.count(HistoryDay::recorded)
        appRecordedDays >= 3 && appRecordedDays * 2 >= recordedDays &&
            app.daysWithLimit >= 3 && app.daysWithLimit * 2 >= appRecordedDays
    }.sortedWith(compareByDescending<HistoryApp> { it.daysWithLimit }.thenByDescending { it.limitEvents }).firstOrNull()
    if (frequentLimit != null) return HistoryInsight(
        title = "${frequentLimit.label} limitine sık ulaşıyorsun",
        body = "Son $rangeDays günde ${frequentLimit.label} için kaydedilen ${frequentLimit.dailyUsage.count(HistoryDay::recorded)} günün ${frequentLimit.daysWithLimit} gününde limite ulaşıldı.",
        packageName = frequentLimit.packageName,
    )

    val usedApps = apps.filter { it.usedMillis > 0L }
    val top = usedApps.firstOrNull() ?: return null
    if (usedApps.size < 2 || totalMillis < 5 * 60_000L) return null
    val share = (top.usedMillis.toDouble() / totalMillis * 100).roundToInt()
    if (share < 35) return null
    return HistoryInsight(
        title = "${top.label} kullanımda öne çıkıyor",
        body = "Son $rangeDays günde kaydedilen sınırlı uygulama kullanımının %$share kadarı ${top.label} üzerinde.",
        packageName = top.packageName,
    )
}

/** Compact Turkish duration for history metrics; hours deliberately continue beyond 24. */
internal fun formatHistoryDuration(millis: Long): String {
    val nonnegative = millis.coerceAtLeast(0L)
    if (nonnegative < 60_000L) return "${nonnegative / 1_000L} sn"
    val roundedMinutes = nonnegative / 60_000L + if (nonnegative % 60_000L >= 30_000L) 1 else 0
    if (roundedMinutes < 60L) return "$roundedMinutes dk"
    val hours = roundedMinutes / 60L
    val minutes = roundedMinutes % 60L
    return if (minutes == 0L) "$hours sa" else "$hours sa $minutes dk"
}
