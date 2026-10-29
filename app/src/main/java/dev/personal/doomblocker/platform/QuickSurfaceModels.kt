package dev.personal.doomblocker.platform

import dev.personal.doomblocker.core.AppAvailability
import dev.personal.doomblocker.core.ClockReading
import dev.personal.doomblocker.core.ProtectionSnapshot
import dev.personal.doomblocker.core.UsageEngine
import dev.personal.doomblocker.service.RuntimeStatus
import kotlin.math.ceil

internal enum class FocusTileState { UNAVAILABLE, INACTIVE, ACTIVE }

internal data class FocusTileModel(
    val state: FocusTileState,
    val subtitle: String,
)

internal data class WidgetTargetModel(
    val packageName: String,
    val label: String,
    val status: String,
)

internal data class WidgetModel(
    val status: String,
    val targets: List<WidgetTargetModel>,
)

/** Pure presentation rules shared by widgets, quick settings and their JVM tests. */
internal object QuickSurfaceModels {
    fun focusTile(snapshot: ProtectionSnapshot?, now: ClockReading, connected: Boolean = true): FocusTileModel {
        val remaining = snapshot?.focusSession?.block?.remainingMillis(now) ?: 0
        return when {
            !connected -> FocusTileModel(FocusTileState.UNAVAILABLE, "Koruma servisi kapalı")
            snapshot?.apps?.isEmpty() != false -> FocusTileModel(FocusTileState.UNAVAILABLE, "Hedef yok")
            remaining > 0 -> FocusTileModel(FocusTileState.ACTIVE, "${duration(remaining)} kaldı")
            else -> FocusTileModel(FocusTileState.INACTIVE, "Dokun ve başlat")
        }
    }

    fun widget(snapshot: ProtectionSnapshot?, runtime: RuntimeStatus, now: ClockReading): WidgetModel {
        val targets = snapshot?.apps.orEmpty().sortedWith(
            compareBy(
                { target ->
                    when (snapshot?.let { UsageEngine.view(it, target, now).availability }) {
                        AppAvailability.FOCUS_BLOCKED, AppAvailability.MANUAL_BLOCKED,
                        AppAvailability.DAILY_BLOCKED, AppAvailability.GROUP_BLOCKED,
                        AppAvailability.SCHEDULE_BLOCKED -> 0
                        AppAvailability.COOLDOWN -> 1
                        else -> 2
                    }
                },
                { target -> (target.limits.dailyMillis - target.usedTodayMillis).coerceAtLeast(0) },
            ),
        ).take(3).map { target ->
            val view = UsageEngine.view(snapshot!!, target, now)
            WidgetTargetModel(
                target.packageName,
                target.label,
                when (view.availability) {
                    AppAvailability.FOCUS_BLOCKED -> "Odakta"
                    AppAvailability.COOLDOWN -> "${duration(view.cooldownRemainingMillis)} mola"
                    AppAvailability.DAILY_BLOCKED, AppAvailability.GROUP_BLOCKED,
                    AppAvailability.MANUAL_BLOCKED, AppAvailability.SCHEDULE_BLOCKED -> "Kapalı"
                    AppAvailability.INTENTION_REQUIRED -> "Niyet bekliyor"
                    AppAvailability.PLANNED_ACCESS -> "${duration(view.plannedAccessRemainingMillis)} planlı"
                    else -> "${duration((target.limits.dailyMillis - view.usedTodayMillis).coerceAtLeast(0))} kaldı"
                },
            )
        }
        val focusRemaining = snapshot?.focusSession?.block?.remainingMillis(now) ?: 0
        return WidgetModel(
            status = when {
                !runtime.connected -> "Koruma servisi kapalı"
                runtime.problem != null -> "Koruma kontrol edilmeli"
                focusRemaining > 0 -> "Odak açık · ${duration(focusRemaining)} kaldı"
                targets.isEmpty() -> "Henüz korunan uygulama yok"
                else -> "Koruma aktif · ${snapshot?.apps?.size ?: 0} uygulama"
            },
            targets = targets,
        )
    }

    fun duration(millis: Long): String {
        val minutes = ceil(millis.coerceAtLeast(0) / 60_000.0).toLong()
        return if (minutes >= 60) "${minutes / 60} sa ${minutes % 60} dk" else "$minutes dk"
    }
}
