package dev.personal.doomblocker.core

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

enum class InsightKind { LIMIT_FREQUENCY, LIMIT_SUGGESTION, WEEKLY_TREND, PEAK_OPENING, SUNDAY_PATTERN, UNUSED_PLAN }

data class LocalInsight(
    val kind: InsightKind,
    val title: String,
    val body: String,
    val packageName: String? = null,
    val suggestedDailyMinutes: Int? = null,
)

object LocalInsights {
    fun pick(snapshot: ProtectionSnapshot, nowWallMillis: Long): LocalInsight? =
        candidates(snapshot, nowWallMillis).firstOrNull()

    fun candidates(snapshot: ProtectionSnapshot, nowWallMillis: Long): List<LocalInsight> {
        val current = DailyRecord(snapshot.activeEpochDay, snapshot.apps.map {
            DailyAppRecord(it.packageName, it.label, it.usedTodayMillis, it.limitEventsToday, it.longestSessionTodayMillis)
        })
        val records = (snapshot.history.filterNot { it.epochDay == snapshot.activeEpochDay } + current)
            .sortedBy { it.epochDay }
        val results = mutableListOf<LocalInsight>()

        snapshot.apps.forEach { app ->
            val recentFive = (snapshot.activeEpochDay - 4..snapshot.activeEpochDay).map { day ->
                records.firstOrNull { it.epochDay == day }?.apps?.firstOrNull { it.packageName == app.packageName }
            }
            val reached = recentFive.count { (it?.limitEvents ?: 0) > 0 }
            if (reached >= 4) results += LocalInsight(
                InsightKind.LIMIT_FREQUENCY,
                "${app.label} sınırına sık ulaşıyorsun",
                "Son 5 günün $reached gününde sınıra ulaşıldı.",
                app.packageName,
            )
        }

        snapshot.apps.forEach { app ->
            val recent = (snapshot.activeEpochDay - 7 until snapshot.activeEpochDay).mapNotNull { day ->
                records.firstOrNull { it.epochDay == day }?.apps?.firstOrNull { it.packageName == app.packageName }
            }
            if (recent.size == 7 && recent.count { it.usedMillis > 0 } >= 3) {
                val averageMinutes = recent.sumOf { it.usedMillis }.toDouble() / 7 / app.limits.unitMillis
                val suggestion = (ceil(averageMinutes / 5.0) * 5).toInt().coerceAtLeast(5)
                if (suggestion <= app.limits.dailyMinutes - 5 && suggestion <= app.limits.dailyMinutes * 0.75) {
                    results += LocalInsight(
                        InsightKind.LIMIT_SUGGESTION,
                        "${app.label} limiti yüksek kalıyor olabilir",
                        "Son haftana göre ${app.limits.dailyMinutes} dk yerine $suggestion dk deneyebilirsin.",
                        app.packageName,
                        suggestion,
                    )
                }
            }
        }

        snapshot.apps.forEach { app ->
            fun total(from: Long, to: Long) = records.asSequence()
                .filter { it.epochDay in from..to }
                .sumOf { day -> day.apps.firstOrNull { it.packageName == app.packageName }?.usedMillis ?: 0 }
            val currentWeek = total(snapshot.activeEpochDay - 7, snapshot.activeEpochDay - 1)
            val previousWeek = total(snapshot.activeEpochDay - 14, snapshot.activeEpochDay - 8)
            val completeDays = records.count { day -> day.epochDay in (snapshot.activeEpochDay - 14) until snapshot.activeEpochDay &&
                day.apps.any { it.packageName == app.packageName } }
            if (completeDays == 14 && previousWeek >= app.limits.unitMillis * 5) {
                val percent = (((currentWeek - previousWeek).toDouble() / previousWeek) * 100).roundToInt()
                if (abs(percent) >= 20) results += LocalInsight(
                    InsightKind.WEEKLY_TREND,
                    "${app.label} kullanımı ${if (percent > 0) "arttı" else "azaldı"}",
                    "Son 7 tamamlanmış günde önceki 7 güne göre %${abs(percent)} ${if (percent > 0) "daha fazla" else "daha az"} zaman harcadın.",
                    app.packageName,
                )
            }
        }

        val recentOpenings = snapshot.openEvents.filter { it.wallMillis in (nowWallMillis - 14L * 24 * 60 * 60_000)..nowWallMillis }
        if (recentOpenings.size >= 6) {
            val zone = ZoneId.of(snapshot.quotaZoneId)
            val busiest = recentOpenings.groupingBy {
                Instant.ofEpochMilli(it.wallMillis).atZone(zone).hour / 2
            }.eachCount().maxByOrNull { it.value }
            busiest?.let { (bucket, count) ->
                val start = bucket * 2
                val end = (start + 2) % 24
                results += LocalInsight(
                    InsightKind.PEAK_OPENING,
                    "Refleks açılış saatin belli",
                    "Son iki haftada en çok ${"%02d.00".format(start)}–${"%02d.00".format(end)} arasında açtın ($count kez).",
                )
            }
        }

        val sundayTotals = records.filter { it.epochDay < snapshot.activeEpochDay && LocalDate.ofEpochDay(it.epochDay).dayOfWeek == DayOfWeek.SUNDAY }
            .map { it.apps.sumOf(DailyAppRecord::usedMillis) }
        val weekdayTotals = records.filter { it.epochDay < snapshot.activeEpochDay && LocalDate.ofEpochDay(it.epochDay).dayOfWeek.value in 1..5 }
            .map { it.apps.sumOf(DailyAppRecord::usedMillis) }
        if (sundayTotals.size >= 2 && weekdayTotals.size >= 5) {
            val sundayAverage = sundayTotals.average()
            val weekdayAverage = weekdayTotals.average()
            if (weekdayAverage > 0 && sundayAverage >= weekdayAverage * 1.25) {
                val percent = (((sundayAverage - weekdayAverage) / weekdayAverage) * 100).roundToInt()
                results += LocalInsight(
                    InsightKind.SUNDAY_PATTERN,
                    "Pazar günleri kullanım artıyor",
                    "Pazar ortalaman hafta içine göre %$percent daha yüksek.",
                )
            }
        }

        if (records.size >= 3) snapshot.schedules.firstOrNull { schedule ->
            records.takeLast(7).sumOf { day ->
                day.apps.filter { it.packageName in schedule.policy.packageNames }.sumOf(DailyAppRecord::usedMillis)
            } == 0L
        }?.let { schedule ->
            results += LocalInsight(
                InsightKind.UNUSED_PLAN,
                "${schedule.policy.label}: kullanım kaydı yok",
                "Mevcut kayıtlarda bu programın uygulamalarında kullanım görünmüyor; bu, programın gereksiz olduğu anlamına gelmez.",
            )
        }

        return results.distinctBy { it.kind to it.packageName }
    }
}
