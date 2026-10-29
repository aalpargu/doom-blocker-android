package dev.personal.doomblocker.core

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.max
import kotlin.math.min

data class ClockReading(val wallMillis: Long, val elapsedMillis: Long, val bootCount: Int)

data class UsageInterval(val packageName: String, val startWallMillis: Long, val endWallMillis: Long) {
    init {
        require(packageName.isNotBlank())
        require(endWallMillis >= startWallMillis)
    }
    val durationMillis get() = endWallMillis - startWallMillis
}

data class TimeAnchor(val wallMillis: Long, val elapsedMillis: Long, val bootCount: Int) {
    companion object {
        fun from(now: ClockReading, trustedWallMillis: Long = now.wallMillis) =
            TimeAnchor(trustedWallMillis, now.elapsedMillis, now.bootCount)
    }
}

data class TimedBlock(val startedAt: TimeAnchor, val durationMillis: Long) {
    init { require(durationMillis in 1..MAX_DURATION_MILLIS) }

    fun remainingMillis(now: ClockReading): Long {
        val age = if (startedAt.bootCount >= 0 && startedAt.bootCount == now.bootCount &&
            now.elapsedMillis >= startedAt.elapsedMillis
        ) now.elapsedMillis - startedAt.elapsedMillis
        else now.wallMillis - startedAt.wallMillis
        return (durationMillis - age.coerceAtLeast(0)).coerceIn(0, durationMillis)
    }

    fun rebase(now: ClockReading): TimedBlock? {
        val remaining = remainingMillis(now)
        if (remaining == 0L) return null
        if (startedAt.bootCount >= 0 && startedAt.bootCount == now.bootCount &&
            now.elapsedMillis >= startedAt.elapsedMillis
        ) return this
        return TimedBlock(TimeAnchor.from(now), remaining)
    }

    companion object { const val MAX_DURATION_MILLIS = 7L * 24 * 60 * 60 * 1_000 }
}

data class Limits(
    val dailyMinutes: Int = 20,
    val sessionMinutes: Int = 5,
    val cooldownMinutes: Int = 10,
    val unitMillis: Long = 60_000L,
) {
    init {
        require(dailyMinutes in 1..1_440)
        require(sessionMinutes in 1..dailyMinutes)
        require(cooldownMinutes in 1..1_440)
        require(unitMillis in 1_000L..60_000L)
    }
    val dailyMillis get() = dailyMinutes * unitMillis
    val sessionMillis get() = sessionMinutes * unitMillis
    val cooldownMillis get() = cooldownMinutes * unitMillis
}

data class AppPolicy(
    val weekdayLimits: Limits = Limits(),
    val weekendLimits: Limits? = null,
) {
    init { require(weekendLimits == null || weekendLimits.unitMillis == weekdayLimits.unitMillis) }

    fun limitsFor(epochDay: Long): Limits = when (LocalDate.ofEpochDay(epochDay).dayOfWeek) {
        DayOfWeek.SATURDAY, DayOfWeek.SUNDAY -> weekendLimits ?: weekdayLimits
        else -> weekdayLimits
    }

    fun tightenedWith(desired: AppPolicy): AppPolicy {
        require(weekdayLimits.unitMillis == desired.weekdayLimits.unitMillis)
        val weekday = tighten(weekdayLimits, desired.weekdayLimits)
        val hasWeekend = weekendLimits != null || desired.weekendLimits != null
        val weekend = if (hasWeekend) tighten(
            weekendLimits ?: weekdayLimits,
            desired.weekendLimits ?: desired.weekdayLimits,
        ) else null
        return AppPolicy(weekday, weekend)
    }

    private fun tighten(current: Limits, desired: Limits) = Limits(
        dailyMinutes = min(current.dailyMinutes, desired.dailyMinutes),
        // Session duration edits apply now, but cannot exceed today's active daily limit.
        sessionMinutes = min(desired.sessionMinutes, min(current.dailyMinutes, desired.dailyMinutes)),
        cooldownMinutes = max(current.cooldownMinutes, desired.cooldownMinutes),
        unitMillis = current.unitMillis,
    )
}

data class PendingPolicy(
    val policy: AppPolicy,
    val effectiveEpochDay: Long,
    val effectiveWallMillis: Long,
)

data class PendingRemoval(val effectiveEpochDay: Long, val effectiveWallMillis: Long)

data class GroupPolicy(
    val label: String,
    val packageNames: Set<String>,
    val dailyMinutes: Int,
    val unitMillis: Long = 60_000L,
) {
    init {
        require(label.isNotBlank())
        require(packageNames.size >= 2)
        require(packageNames.none { it.isBlank() })
        require(dailyMinutes in 1..1_440)
        require(unitMillis in 1_000L..60_000L)
    }

    val dailyMillis get() = dailyMinutes * unitMillis

    fun tightenedWith(desired: GroupPolicy): GroupPolicy {
        require(unitMillis == desired.unitMillis)
        return GroupPolicy(
            label = desired.label,
            packageNames = packageNames + desired.packageNames,
            dailyMinutes = min(dailyMinutes, desired.dailyMinutes),
            unitMillis = unitMillis,
        )
    }
}

data class PendingGroupPolicy(
    val policy: GroupPolicy,
    val effectiveEpochDay: Long,
    val effectiveWallMillis: Long,
)

data class ProtectedGroup(
    val id: String,
    val policy: GroupPolicy,
    val pendingPolicy: PendingGroupPolicy? = null,
    val pendingRemoval: PendingRemoval? = null,
) {
    init { require(id.isNotBlank()) }
}

data class SchedulePolicy(
    val label: String,
    val packageNames: Set<String>,
    val daysMask: Int,
    val startMinuteOfDay: Int,
    val endMinuteOfDay: Int,
) {
    init {
        require(label.isNotBlank())
        require(packageNames.isNotEmpty() && packageNames.none { it.isBlank() })
        require(daysMask in 1..127)
        require(startMinuteOfDay in 0..1_439)
        require(endMinuteOfDay in 0..1_439)
        require(startMinuteOfDay != endMinuteOfDay)
    }

    fun isActive(wallMillis: Long, zoneId: String): Boolean {
        val local = Instant.ofEpochMilli(wallMillis).atZone(ZoneId.of(zoneId))
        val minute = local.hour * 60 + local.minute
        return if (startMinuteOfDay < endMinuteOfDay) {
            includes(local.dayOfWeek) && minute in startMinuteOfDay until endMinuteOfDay
        } else {
            (includes(local.dayOfWeek) && minute >= startMinuteOfDay) ||
                (includes(local.dayOfWeek.minus(1)) && minute < endMinuteOfDay)
        }
    }

    fun activeRemainingMillis(wallMillis: Long, zoneId: String): Long {
        if (!isActive(wallMillis, zoneId)) return 0
        val zone = ZoneId.of(zoneId)
        val local = Instant.ofEpochMilli(wallMillis).atZone(zone)
        val minute = local.hour * 60 + local.minute
        val endDate = when {
            startMinuteOfDay < endMinuteOfDay -> local.toLocalDate()
            minute >= startMinuteOfDay -> local.toLocalDate().plusDays(1)
            else -> local.toLocalDate()
        }
        val endWall = endDate.atTime(LocalTime.of(endMinuteOfDay / 60, endMinuteOfDay % 60))
            .atZone(zone).toInstant().toEpochMilli()
        return (endWall - wallMillis).coerceAtLeast(0)
    }

    private fun includes(day: DayOfWeek) = daysMask and (1 shl (day.value - 1)) != 0

    fun nextStartWallMillis(wallMillis: Long, zoneId: String): Long {
        val zone = ZoneId.of(zoneId)
        val date = Instant.ofEpochMilli(wallMillis).atZone(zone).toLocalDate()
        return (0L..7L).map { date.plusDays(it) }.filter { includes(it.dayOfWeek) }
            .map { it.atTime(startMinuteOfDay / 60, startMinuteOfDay % 60).atZone(zone).toInstant().toEpochMilli() }
            .first { it > wallMillis }
    }
}

data class PendingSchedulePolicy(
    val policy: SchedulePolicy,
    val effectiveEpochDay: Long,
    val effectiveWallMillis: Long,
)

data class ProtectedSchedule(
    val id: String,
    val policy: SchedulePolicy,
    val pendingPolicy: PendingSchedulePolicy? = null,
    val pendingRemoval: PendingRemoval? = null,
) {
    init { require(id.isNotBlank()) }
}

data class DailyAppRecord(
    val packageName: String,
    val label: String,
    val usedMillis: Long,
    val limitEvents: Int,
    val longestSessionMillis: Long = 0,
)

data class DailyRecord(val epochDay: Long, val apps: List<DailyAppRecord>)

data class OpenEvent(val packageName: String, val wallMillis: Long) {
    init { require(packageName.isNotBlank() && wallMillis >= 0) }
}

data class FocusSession(
    val packageNames: Set<String>,
    val block: TimedBlock,
) {
    init { require(packageNames.isNotEmpty() && packageNames.none { it.isBlank() }) }
}

data class ProtectedApp(
    val packageName: String,
    val label: String,
    val limits: Limits,
    val policy: AppPolicy = AppPolicy(limits),
    val pendingPolicy: PendingPolicy? = null,
    val pendingRemoval: PendingRemoval? = null,
    val usedTodayMillis: Long = 0,
    val sessionUsedMillis: Long = 0,
    /** First entry of the current logical session; short background gaps keep this identity. */
    val sessionStartedAt: TimeAnchor? = null,
    val foregroundSince: TimeAnchor? = null,
    val backgroundSince: TimeAnchor? = null,
    val cooldown: TimedBlock? = null,
    val dailyBlocked: Boolean = false,
    val limitEventsToday: Int = 0,
    val needsLimitReview: Boolean = false,
    val manualBlocked: Boolean = false,
    val emergencyRequest: TimedBlock? = null,
    val emergencyAccess: TimedBlock? = null,
    val emergencyUsedEpochDay: Long? = null,
    val emergencyReason: String? = null,
    val longestSessionTodayMillis: Long = 0,
    val intentionalOpenEnabled: Boolean = false,
    /** Legacy wall-time grant; converted once at the last saved checkpoint. */
    val plannedAccess: TimedBlock? = null,
    val plannedUsageRemainingMillis: Long? = null,
    val plannedReason: String? = null,
    val intentionalDisableAtWallMillis: Long? = null,
) {
    init {
        require(packageName.isNotBlank() && label.isNotBlank())
        require(usedTodayMillis >= 0 && sessionUsedMillis >= 0 && limitEventsToday >= 0 && longestSessionTodayMillis >= 0)
        require(plannedUsageRemainingMillis == null || plannedUsageRemainingMillis in 0..TimedBlock.MAX_DURATION_MILLIS)
        require(limits.unitMillis == policy.weekdayLimits.unitMillis)
    }
}

data class ProtectionSnapshot(
    val quotaZoneId: String,
    val activeEpochDay: Long,
    val clockAnchor: TimeAnchor,
    val recoveryCursorWallMillis: Long,
    val apps: List<ProtectedApp> = emptyList(),
    val history: List<DailyRecord> = emptyList(),
    val strictModeEnabled: Boolean = false,
    val strictModeDisableAtWallMillis: Long? = null,
    val groups: List<ProtectedGroup> = emptyList(),
    val schedules: List<ProtectedSchedule> = emptyList(),
    val focusSession: FocusSession? = null,
    val openEvents: List<OpenEvent> = emptyList(),
    val travelException: TravelException? = null,
    /** A tombstone prevents the bundled one-time trip from returning after cancellation. */
    val travelExceptionConfigured: Boolean = false,
)

enum class BlockReason {
    DAILY_LIMIT, GROUP_DAILY_LIMIT, MANUAL_LOCK, SCHEDULED_BLOCK, SESSION_LIMIT, COOLDOWN,
    FOCUS_SESSION, INTENTION_REQUIRED, PLANNED_SESSION_END,
}

data class BlockDecision(
    val packageName: String,
    val label: String,
    val reason: BlockReason,
    val usedTodayMillis: Long,
    val dailyLimitMillis: Long,
    val sessionLimitMillis: Long,
    val cooldownRemainingMillis: Long = 0,
    val groupLabel: String? = null,
    val groupUsedMillis: Long = 0,
    val groupLimitMillis: Long = 0,
    val scheduleLabel: String? = null,
    val scheduleRemainingMillis: Long = 0,
)

data class EngineResult(
    val snapshot: ProtectionSnapshot,
    val decision: BlockDecision? = null,
    val nextWakeupMillis: Long? = null,
)

enum class AppAvailability {
    AVAILABLE, ACTIVE, EMERGENCY_WAIT, EMERGENCY_ACCESS, COOLDOWN, DAILY_BLOCKED, GROUP_BLOCKED, MANUAL_BLOCKED, SCHEDULE_BLOCKED,
    FOCUS_BLOCKED, INTENTION_REQUIRED, PLANNED_ACCESS, REMOVAL_PENDING
}

data class AppView(
    val usedTodayMillis: Long,
    val sessionUsedMillis: Long,
    val cooldownRemainingMillis: Long,
    val availability: AppAvailability,
    val groupLabel: String? = null,
    val groupUsedMillis: Long = 0,
    val groupLimitMillis: Long = 0,
    val scheduleLabel: String? = null,
    val scheduleRemainingMillis: Long = 0,
    val emergencyWaitRemainingMillis: Long = 0,
    val emergencyAccessRemainingMillis: Long = 0,
    val focusRemainingMillis: Long = 0,
    val plannedAccessRemainingMillis: Long = 0,
)

object UsageEngine {
    const val SESSION_GRACE_MILLIS = 60_000L
    const val CHECKPOINT_MILLIS = 5_000L
    const val STRICT_DELAY_MILLIS = 24 * 60 * 60_000L
    const val HISTORY_DAYS = 30
    const val EMERGENCY_WAIT_MILLIS = 60_000L
    const val EMERGENCY_ACCESS_MILLIS = 5 * 60_000L
    const val OPEN_EVENT_RETENTION_MILLIS = 30L * 24 * 60 * 60_000L
    const val OPEN_EVENT_DEDUPE_MILLIS = 15_000L
    const val MAX_OPEN_EVENTS = 5_000

    fun newSnapshot(now: ClockReading, zoneId: String): ProtectionSnapshot {
        val day = epochDay(now.wallMillis, zoneId)
        return ProtectionSnapshot(zoneId, day, TimeAnchor.from(now), now.wallMillis)
    }

    /** A process gap is reconstructed later from UsageEvents, never guessed here. */
    fun restore(snapshot: ProtectionSnapshot, now: ClockReading): ProtectionSnapshot {
        // Older records have no explicit identity. Derive it once from the saved
        // checkpoint before rebase clears a foreground anchor, then persist it.
        val withSessionStarts = snapshot.copy(apps = snapshot.apps.map { app ->
            if (app.sessionStartedAt != null) app else {
                val last = app.foregroundSince ?: app.backgroundSince?.takeIf { app.sessionUsedMillis > 0 }
                app.copy(sessionStartedAt = last?.let {
                    it.copy(
                        wallMillis = (it.wallMillis - app.sessionUsedMillis).coerceAtLeast(0),
                        elapsedMillis = (it.elapsedMillis - app.sessionUsedMillis).coerceAtLeast(0),
                    )
                })
            }
        })
        var state = rebaseClock(withSessionStarts, now)
        val trusted = trustedWall(state, now)
        val anchor = TimeAnchor.from(now, trusted)
        state = state.copy(apps = state.apps.map { app ->
            app.copy(
                foregroundSince = null,
                backgroundSince = if (app.foregroundSince != null) anchor else app.backgroundSince,
                cooldown = app.cooldown?.rebase(now),
                emergencyRequest = app.emergencyRequest?.rebase(now),
                emergencyAccess = app.emergencyAccess?.rebase(now),
                plannedAccess = null,
            )
        }, focusSession = state.focusSession?.let { session ->
            session.block.rebase(now)?.let { session.copy(block = it) }
        })
        state = advanceDays(state, trusted, now)
        state = applyMatured(state, trusted, now)
        return state.copy(clockAnchor = anchor)
    }

    fun addApp(
        snapshot: ProtectionSnapshot,
        packageName: String,
        label: String,
        limits: Limits,
        initialUsedMillis: Long,
        now: ClockReading,
    ): ProtectionSnapshot = addApp(snapshot, packageName, label, AppPolicy(limits), initialUsedMillis, now)

    fun addApp(
        snapshot: ProtectionSnapshot,
        packageName: String,
        label: String,
        policy: AppPolicy,
        initialUsedMillis: Long,
        now: ClockReading,
    ): ProtectionSnapshot {
        val state = prepare(snapshot, now, accrue = true).snapshot
        require(state.apps.none { it.packageName == packageName }) { "Bu uygulama zaten korunuyor." }
        val limits = policy.limitsFor(state.activeEpochDay)
        val used = initialUsedMillis.coerceAtLeast(0)
        val app = ProtectedApp(
            packageName = packageName,
            label = label,
            limits = limits,
            policy = policy,
            usedTodayMillis = used,
            dailyBlocked = used >= limits.dailyMillis,
        )
        return state.copy(
            apps = state.apps + app,
            clockAnchor = TimeAnchor.from(now, trustedWall(state, now)),
            recoveryCursorWallMillis = now.wallMillis,
        )
    }

    fun updateLimits(
        snapshot: ProtectionSnapshot,
        packageName: String,
        desired: Limits,
        now: ClockReading,
    ): ProtectionSnapshot = updatePolicy(snapshot, packageName, AppPolicy(desired), now)

    fun setTravelException(
        snapshot: ProtectionSnapshot,
        startWallMillis: Long,
        endWallMillis: Long,
        now: ClockReading,
    ): ProtectionSnapshot {
        val exception = TravelException(startWallMillis, endWallMillis)
        require(endWallMillis > trustedWall(snapshot, now)) { "Yolculuk istisnasının bitişi geçmişte olamaz." }
        val prepared = prepare(snapshot.copy(
            travelException = exception,
            travelExceptionConfigured = true,
        ), now, accrue = true).snapshot
        return prepared.copy(
            clockAnchor = TimeAnchor.from(now, trustedWall(prepared, now)),
            recoveryCursorWallMillis = now.wallMillis,
        )
    }

    fun clearTravelException(snapshot: ProtectionSnapshot, now: ClockReading): ProtectionSnapshot {
        val prepared = prepare(snapshot, now, accrue = true).snapshot
        return prepared.copy(
            travelException = null,
            travelExceptionConfigured = true,
            clockAnchor = TimeAnchor.from(now, trustedWall(prepared, now)),
            recoveryCursorWallMillis = now.wallMillis,
        )
    }

    fun requestEmergencyAccess(
        snapshot: ProtectionSnapshot,
        packageName: String,
        reason: String,
        now: ClockReading,
    ): ProtectionSnapshot {
        var state = prepare(snapshot, now, accrue = true).snapshot
        val app = state.apps.firstOrNull { it.packageName == packageName }
            ?: error("Korunan uygulama bulunamadı.")
        require(reason.isNotBlank() && reason.length <= 80) { "Acil erişim nedeni seçilmelidir." }
        require(state.focusSession?.let { packageName in it.packageNames && it.block.remainingMillis(now) > 0 } != true) {
            "Aktif odak oturumu acil erişimle aşılamaz."
        }
        require(app.emergencyUsedEpochDay != state.activeEpochDay) { "Acil erişim bugün zaten kullanıldı." }
        require(blockFor(state, app, now) != null) { "Acil erişim yalnız engelli bir hedef için kullanılabilir." }
        val anchor = TimeAnchor.from(now, trustedWall(state, now))
        state = state.update(packageName) {
            it.copy(
                emergencyRequest = TimedBlock(anchor, EMERGENCY_WAIT_MILLIS + EMERGENCY_ACCESS_MILLIS),
                emergencyAccess = null,
                emergencyUsedEpochDay = state.activeEpochDay,
                emergencyReason = reason,
            )
        }
        return state.copy(clockAnchor = anchor, recoveryCursorWallMillis = now.wallMillis)
    }

    fun updatePolicy(
        snapshot: ProtectionSnapshot,
        packageName: String,
        desired: AppPolicy,
        now: ClockReading,
    ): ProtectionSnapshot {
        var state = prepare(snapshot, now, accrue = true).snapshot
        val old = state.apps.firstOrNull { it.packageName == packageName } ?: return state
        require(old.policy.weekdayLimits.unitMillis == desired.weekdayLimits.unitMillis)
        val tightened = old.policy.tightenedWith(desired)
        val trusted = trustedWall(state, now)
        val pending = if (tightened == desired) null else PendingPolicy(
            desired,
            state.activeEpochDay + 1,
            if (state.strictModeEnabled) trusted + STRICT_DELAY_MILLIS else nextDayWall(state),
        )
        val activeLimits = tightened.limitsFor(state.activeEpochDay)
        val oldRemaining = old.cooldown?.remainingMillis(now) ?: 0
        val cooldown = when {
            oldRemaining == 0L -> null
            activeLimits.cooldownMillis > old.limits.cooldownMillis -> {
                val elapsed = (old.cooldown!!.durationMillis - oldRemaining).coerceAtLeast(0)
                TimedBlock(TimeAnchor.from(now, trusted), (activeLimits.cooldownMillis - elapsed).coerceAtLeast(1))
            }
            else -> old.cooldown
        }
        var updated = old.copy(
            limits = activeLimits,
            policy = tightened,
            pendingPolicy = pending,
            cooldown = cooldown,
            dailyBlocked = old.dailyBlocked || old.usedTodayMillis >= activeLimits.dailyMillis,
            needsLimitReview = false,
        )
        if (updated.dailyBlocked) {
            updated = updated.copy(
                foregroundSince = null,
                sessionUsedMillis = 0,
                limitEventsToday = if (old.dailyBlocked) old.limitEventsToday else old.limitEventsToday + 1,
            )
        } else if (!updated.intentionalOpenEnabled && updated.sessionUsedMillis >= activeLimits.sessionMillis) {
            updated = updated.copy(
                foregroundSince = null,
                sessionUsedMillis = 0,
                cooldown = TimedBlock(TimeAnchor.from(now, trusted), activeLimits.cooldownMillis),
                limitEventsToday = old.limitEventsToday + 1,
            )
        }
        state = state.update(packageName) { updated }
        return state.copy(clockAnchor = TimeAnchor.from(now, trusted), recoveryCursorWallMillis = now.wallMillis)
    }

    fun requestRemoval(snapshot: ProtectionSnapshot, packageName: String, now: ClockReading): ProtectionSnapshot {
        val prepared = prepare(snapshot, now, accrue = true).snapshot
        val app = prepared.apps.firstOrNull { it.packageName == packageName } ?: return prepared
        val canRemoveNow = !prepared.strictModeEnabled && !app.manualBlocked &&
            prepared.focusSession?.let { packageName in it.packageNames && it.block.remainingMillis(now) > 0 } != true &&
            activeSchedule(prepared, packageName, trustedWall(prepared, now)) == null &&
            prepared.groups.none { packageName in it.policy.packageNames && groupUsage(prepared, it) >= it.policy.dailyMillis } &&
            app.usedTodayMillis == 0L && app.sessionUsedMillis == 0L &&
            (app.cooldown?.remainingMillis(now) ?: 0L) == 0L && app.foregroundSince == null
        val trusted = trustedWall(prepared, now)
        val updated = if (canRemoveNow) {
            val apps = prepared.apps.filterNot { it.packageName == packageName }
            val remaining = apps.mapTo(mutableSetOf()) { it.packageName }
            prepared.copy(
                apps = apps,
                groups = pruneGroups(prepared.groups, remaining),
                schedules = pruneSchedules(prepared.schedules, remaining),
            )
        }
        else prepared.update(packageName) {
            it.copy(pendingRemoval = it.pendingRemoval ?: PendingRemoval(
                prepared.activeEpochDay + 1,
                if (prepared.strictModeEnabled) trusted + STRICT_DELAY_MILLIS else nextDayWall(prepared),
            ))
        }
        return updated.copy(clockAnchor = TimeAnchor.from(now, trusted))
    }

    fun cancelRemoval(snapshot: ProtectionSnapshot, packageName: String, now: ClockReading): ProtectionSnapshot {
        val prepared = prepare(snapshot, now, accrue = true).snapshot
        val trusted = trustedWall(prepared, now)
        return prepared.update(packageName) { it.copy(pendingRemoval = null) }
            .copy(clockAnchor = TimeAnchor.from(now, trusted))
    }

    fun addGroup(
        snapshot: ProtectionSnapshot,
        id: String,
        policy: GroupPolicy,
        now: ClockReading,
    ): ProtectionSnapshot {
        val prepared = prepare(snapshot, now, accrue = true).snapshot
        require(prepared.groups.none { it.id == id }) { "Bu ortak limit zaten var." }
        validateGroupPolicy(prepared, policy)
        require(prepared.groups.none {
            (it.policy.packageNames + it.pendingPolicy?.policy?.packageNames.orEmpty()).any(policy.packageNames::contains)
        }) {
            "Bir uygulama yalnız bir ortak günlük limite eklenebilir."
        }
        val trusted = trustedWall(prepared, now)
        return prepared.copy(
            groups = prepared.groups + ProtectedGroup(id, policy),
            clockAnchor = TimeAnchor.from(now, trusted),
            recoveryCursorWallMillis = now.wallMillis,
        )
    }

    fun updateGroup(
        snapshot: ProtectionSnapshot,
        id: String,
        desired: GroupPolicy,
        now: ClockReading,
    ): ProtectionSnapshot {
        val prepared = prepare(snapshot, now, accrue = true).snapshot
        val current = prepared.groups.firstOrNull { it.id == id } ?: return prepared
        validateGroupPolicy(prepared, desired)
        require(prepared.groups.none {
            it.id != id && (it.policy.packageNames + it.pendingPolicy?.policy?.packageNames.orEmpty())
                .any(desired.packageNames::contains)
        }) {
            "Bir uygulama yalnız bir ortak günlük limite eklenebilir."
        }
        val tightened = current.policy.tightenedWith(desired)
        val trusted = trustedWall(prepared, now)
        val pending = if (tightened == desired) null else PendingGroupPolicy(
            desired,
            prepared.activeEpochDay + 1,
            if (prepared.strictModeEnabled) trusted + STRICT_DELAY_MILLIS else nextDayWall(prepared),
        )
        return prepared.copy(
            groups = prepared.groups.map {
                if (it.id == id) it.copy(policy = tightened, pendingPolicy = pending, pendingRemoval = null) else it
            },
            clockAnchor = TimeAnchor.from(now, trusted),
            recoveryCursorWallMillis = now.wallMillis,
        )
    }

    fun requestGroupRemoval(snapshot: ProtectionSnapshot, id: String, now: ClockReading): ProtectionSnapshot {
        val prepared = prepare(snapshot, now, accrue = true).snapshot
        val group = prepared.groups.firstOrNull { it.id == id } ?: return prepared
        val trusted = trustedWall(prepared, now)
        val used = groupUsage(prepared, group)
        val groups = if (!prepared.strictModeEnabled && used == 0L) {
            prepared.groups.filterNot { it.id == id }
        } else prepared.groups.map {
            if (it.id == id) it.copy(pendingRemoval = it.pendingRemoval ?: PendingRemoval(
                prepared.activeEpochDay + 1,
                if (prepared.strictModeEnabled) trusted + STRICT_DELAY_MILLIS else nextDayWall(prepared),
            )) else it
        }
        return prepared.copy(groups = groups, clockAnchor = TimeAnchor.from(now, trusted))
    }

    fun cancelGroupRemoval(snapshot: ProtectionSnapshot, id: String, now: ClockReading): ProtectionSnapshot {
        val prepared = prepare(snapshot, now, accrue = true).snapshot
        val trusted = trustedWall(prepared, now)
        return prepared.copy(
            groups = prepared.groups.map { if (it.id == id) it.copy(pendingRemoval = null) else it },
            clockAnchor = TimeAnchor.from(now, trusted),
        )
    }

    fun addSchedule(
        snapshot: ProtectionSnapshot,
        id: String,
        policy: SchedulePolicy,
        now: ClockReading,
    ): ProtectionSnapshot {
        val prepared = prepare(snapshot, now, accrue = true).snapshot
        require(prepared.schedules.none { it.id == id }) { "Bu program zaten var." }
        validateSchedulePolicy(prepared, policy)
        val trusted = trustedWall(prepared, now)
        return prepared.copy(
            schedules = prepared.schedules + ProtectedSchedule(id, policy),
            clockAnchor = TimeAnchor.from(now, trusted),
            recoveryCursorWallMillis = now.wallMillis,
        )
    }

    /** Program gevşetme ve saat değişiklikleri ertesi güne kadar mevcut korumayı değiştirmez. */
    fun updateSchedule(
        snapshot: ProtectionSnapshot,
        id: String,
        desired: SchedulePolicy,
        now: ClockReading,
    ): ProtectionSnapshot {
        val prepared = prepare(snapshot, now, accrue = true).snapshot
        val current = prepared.schedules.firstOrNull { it.id == id } ?: return prepared
        validateSchedulePolicy(prepared, desired)
        if (current.policy == desired && current.pendingPolicy == null) return prepared
        val trusted = trustedWall(prepared, now)
        val pending = PendingSchedulePolicy(
            desired,
            prepared.activeEpochDay + 1,
            if (prepared.strictModeEnabled) trusted + STRICT_DELAY_MILLIS else nextDayWall(prepared),
        )
        return prepared.copy(
            schedules = prepared.schedules.map {
                if (it.id == id) it.copy(pendingPolicy = pending, pendingRemoval = null) else it
            },
            clockAnchor = TimeAnchor.from(now, trusted),
            recoveryCursorWallMillis = now.wallMillis,
        )
    }

    fun requestScheduleRemoval(snapshot: ProtectionSnapshot, id: String, now: ClockReading): ProtectionSnapshot {
        val prepared = prepare(snapshot, now, accrue = true).snapshot
        if (prepared.schedules.none { it.id == id }) return prepared
        val trusted = trustedWall(prepared, now)
        return prepared.copy(
            schedules = prepared.schedules.map {
                if (it.id == id) it.copy(pendingRemoval = it.pendingRemoval ?: PendingRemoval(
                    prepared.activeEpochDay + 1,
                    if (prepared.strictModeEnabled) trusted + STRICT_DELAY_MILLIS else nextDayWall(prepared),
                )) else it
            },
            clockAnchor = TimeAnchor.from(now, trusted),
        )
    }

    fun cancelScheduleRemoval(snapshot: ProtectionSnapshot, id: String, now: ClockReading): ProtectionSnapshot {
        val prepared = prepare(snapshot, now, accrue = true).snapshot
        val trusted = trustedWall(prepared, now)
        return prepared.copy(
            schedules = prepared.schedules.map { if (it.id == id) it.copy(pendingRemoval = null) else it },
            clockAnchor = TimeAnchor.from(now, trusted),
        )
    }

    fun lockAllForToday(snapshot: ProtectionSnapshot, now: ClockReading): ProtectionSnapshot {
        val prepared = prepare(snapshot, now, accrue = true).snapshot
        val trusted = trustedWall(prepared, now)
        return prepared.copy(
            apps = prepared.apps.map { app ->
                app.copy(
                    manualBlocked = true,
                    foregroundSince = null,
                    sessionUsedMillis = 0,
                    cooldown = null,
                )
            },
            clockAnchor = TimeAnchor.from(now, trusted),
            recoveryCursorWallMillis = now.wallMillis,
        )
    }

    fun lockAppForToday(snapshot: ProtectionSnapshot, packageName: String, now: ClockReading): ProtectionSnapshot {
        val prepared = prepare(snapshot, now, accrue = true).snapshot
        val trusted = trustedWall(prepared, now)
        return prepared.update(packageName) { app ->
            app.copy(
                manualBlocked = true,
                foregroundSince = null,
                sessionUsedMillis = 0,
                cooldown = null,
            )
        }.copy(
            clockAnchor = TimeAnchor.from(now, trusted),
            recoveryCursorWallMillis = now.wallMillis,
        )
    }

    fun startFocusSession(
        snapshot: ProtectionSnapshot,
        packageNames: Set<String>,
        durationMillis: Long,
        now: ClockReading,
    ): ProtectionSnapshot {
        val prepared = prepare(snapshot, now, accrue = true).snapshot
        val known = prepared.apps.mapTo(mutableSetOf()) { it.packageName }
        require(packageNames.isNotEmpty() && packageNames.all(known::contains)) {
            "Odak modu için en az bir korunan uygulama seçilmelidir."
        }
        require(durationMillis in 1..TimedBlock.MAX_DURATION_MILLIS)
        val anchor = TimeAnchor.from(now, trustedWall(prepared, now))
        val activeFocus = prepared.focusSession?.takeIf { it.block.remainingMillis(now) > 0 }
        val protectedPackages = activeFocus?.packageNames.orEmpty() + packageNames
        val protectedDuration = max(activeFocus?.block?.remainingMillis(now) ?: 0L, durationMillis)
        return prepared.copy(
            apps = prepared.apps.map { app ->
                if (app.packageName in protectedPackages) app.copy(foregroundSince = null) else app
            },
            focusSession = FocusSession(protectedPackages, TimedBlock(anchor, protectedDuration)),
            clockAnchor = anchor,
            recoveryCursorWallMillis = now.wallMillis,
        )
    }

    fun setIntentionalOpen(
        snapshot: ProtectionSnapshot,
        packageName: String,
        enabled: Boolean,
        now: ClockReading,
    ): ProtectionSnapshot {
        val prepared = prepare(snapshot, now, accrue = true).snapshot
        val trusted = trustedWall(prepared, now)
        return prepared.update(packageName) { app ->
            val changed = app.intentionalOpenEnabled != enabled
            app.copy(
                intentionalOpenEnabled = enabled,
                intentionalDisableAtWallMillis = null,
                // Switching modes must not resurrect an earlier planned session.
                plannedAccess = if (changed) null else app.plannedAccess,
                plannedUsageRemainingMillis = if (changed) null else app.plannedUsageRemainingMillis,
                plannedReason = if (changed) null else app.plannedReason,
            )
        }.copy(clockAnchor = TimeAnchor.from(now, trusted), recoveryCursorWallMillis = now.wallMillis)
    }

    fun plannedEntry(snapshot: ProtectionSnapshot, packageName: String, now: ClockReading): PlannedEntry? {
        val prepared = prepare(snapshot, now, accrue = true).snapshot
        val app = prepared.apps.firstOrNull { it.packageName == packageName } ?: return null
        val wall = trustedWall(prepared, now)
        val next = nextScheduledBlock(prepared, packageName, wall)
        return PlannedEntry(remainingQuota(prepared, app), plannedRemaining(app, now),
            app.limits.unitMillis, app.limits.cooldownMillis, hardBlockFor(prepared, app, now), next)
    }

    private fun remainingQuota(snapshot: ProtectionSnapshot, app: ProtectedApp): Long = minOf(
        (app.limits.dailyMillis - app.usedTodayMillis).coerceAtLeast(0),
        snapshot.groups.filter { app.packageName in it.policy.packageNames }
            .minOfOrNull { (it.policy.dailyMillis - groupUsage(snapshot, it)).coerceAtLeast(0) } ?: Long.MAX_VALUE,
    )

    fun startPlannedAccess(
        snapshot: ProtectionSnapshot,
        packageName: String,
        durationMillis: Long,
        reason: String,
        now: ClockReading,
    ): ProtectionSnapshot {
        val prepared = prepare(snapshot, now, accrue = true).snapshot
        val app = prepared.apps.firstOrNull { it.packageName == packageName }
            ?: error("Korunan uygulama bulunamadı.")
        require(app.intentionalOpenEnabled) { "Bilinçli giriş bu uygulama için açık değil." }
        require(durationMillis in 1..TimedBlock.MAX_DURATION_MILLIS)
        require(reason.trim().isNotEmpty() && reason.trim().length <= IntentionalAccessPolicy.maxReasonLength) {
            "Açma nedeni yazılmalıdır."
        }
        require(hardBlockFor(prepared, app, now) == null) { "Uygulama şu anda başka bir kural nedeniyle engelli." }
        require((plannedRemaining(app, now)) == 0L) { "Planlı giriş zaten açık; süresi uzatılamaz." }
        val budget = remainingQuota(prepared, app)
        require(budget > 0) { "Günlük veya ortak kullanım hakkın doldu." }
        val anchor = TimeAnchor.from(now, trustedWall(prepared, now))
        return prepared.update(packageName) {
            it.copy(
                sessionUsedMillis = 0,
                sessionStartedAt = anchor,
                plannedAccess = null,
                plannedUsageRemainingMillis = minOf(durationMillis, budget),
                plannedReason = reason.trim(),
            )
        }.copy(clockAnchor = anchor, recoveryCursorWallMillis = now.wallMillis)
    }

    fun enableStrictMode(snapshot: ProtectionSnapshot, now: ClockReading): ProtectionSnapshot {
        val prepared = prepare(snapshot, now, accrue = true).snapshot
        return prepared.copy(
            strictModeEnabled = true,
            strictModeDisableAtWallMillis = null,
            clockAnchor = TimeAnchor.from(now, trustedWall(prepared, now)),
        )
    }

    fun requestStrictModeDisable(snapshot: ProtectionSnapshot, now: ClockReading): ProtectionSnapshot {
        val prepared = prepare(snapshot, now, accrue = true).snapshot
        if (!prepared.strictModeEnabled) return prepared
        val trusted = trustedWall(prepared, now)
        return prepared.copy(
            strictModeDisableAtWallMillis = prepared.strictModeDisableAtWallMillis
                ?: trusted + STRICT_DELAY_MILLIS,
            clockAnchor = TimeAnchor.from(now, trusted),
        )
    }

    /** Replays only intervals proven by UsageEvents and also repairs past-day history. */
    fun recoverUsage(
        snapshot: ProtectionSnapshot,
        intervals: List<UsageInterval>,
        accountingStartWallMillis: Long,
        initialForegroundPackage: String?,
        finalForegroundPackage: String?,
        now: ClockReading,
    ): ProtectionSnapshot {
        val recoveryStart = max(accountingStartWallMillis, snapshot.recoveryCursorWallMillis)
        var state = prepare(snapshot, now, accrue = false).snapshot
        val trustedNow = trustedWall(state, now)
        val nowAnchor = TimeAnchor.from(now, trustedNow)
        val usageByDay = usageByDay(intervals, recoveryStart, trustedNow, state.quotaZoneId)
        val recoveredHistory = state.history.map { record ->
            val additions = usageByDay[record.epochDay].orEmpty()
            val merged = mergeRecordUsage(record.apps, additions, state.apps)
            val dayStart = LocalDate.ofEpochDay(record.epochDay).atStartOfDay(ZoneId.of(state.quotaZoneId)).toInstant().toEpochMilli()
            val dayEnd = LocalDate.ofEpochDay(record.epochDay + 1).atStartOfDay(ZoneId.of(state.quotaZoneId)).toInstant().toEpochMilli()
            record.copy(apps = merged.map { app ->
                val longestObserved = intervals.filter { it.packageName == app.packageName }.maxOfOrNull {
                    (min(it.endWallMillis, dayEnd) - maxOf(it.startWallMillis, dayStart, recoveryStart)).coerceAtLeast(0)
                } ?: 0
                app.copy(longestSessionMillis = max(app.longestSessionMillis, longestObserved))
            })
        }
        val todayStart = dayStartWall(state)
        state = state.copy(
            history = recoveredHistory,
            apps = state.apps.map { app ->
                val recovered = intervals.asSequence()
                    .filter { it.packageName == app.packageName && it.endWallMillis > max(todayStart, recoveryStart) }
                    .sortedBy { it.startWallMillis }.toList()
                val addedDaily = usageByDay[state.activeEpochDay]?.get(app.packageName) ?: 0
                var session = app.sessionUsedMillis
                var sessionStartedAt = app.sessionStartedAt
                var longestSession = app.longestSessionTodayMillis
                var limitEvents = app.limitEventsToday
                var lastSessionEnd = if (initialForegroundPackage == app.packageName && recoveryStart >= todayStart)
                    recoveryStart else app.backgroundSince?.wallMillis
                var observedEnd: Long? = null
                recovered.forEach { interval ->
                    val start = maxOf(interval.startWallMillis, recoveryStart, todayStart)
                    val end = min(interval.endWallMillis, trustedNow)
                    if (end > start) {
                        val travel = state.travelException
                        val boundaries = listOfNotNull(
                            start,
                            travel?.startWallMillis?.takeIf { it in (start + 1) until end },
                            travel?.endWallMillis?.takeIf { it in (start + 1) until end },
                            end,
                        ).sorted()
                        boundaries.zipWithNext().forEach { (segmentStart, segmentEnd) ->
                            if (travel?.isActive(segmentStart) == true) {
                                // Keep the observed daily use; the trip cannot create a break or warning event.
                                session = 0
                                sessionStartedAt = null
                            } else {
                                if (lastSessionEnd == null || segmentStart - lastSessionEnd!! >= SESSION_GRACE_MILLIS ||
                                    sessionStartedAt == null) {
                                    session = 0
                                    val samePlannedSession = app.intentionalOpenEnabled &&
                                        (app.plannedUsageRemainingMillis != null || app.plannedAccess != null)
                                    if (!samePlannedSession || sessionStartedAt == null)
                                        sessionStartedAt = anchorAtWall(now, trustedNow, segmentStart)
                                }
                                val previousSession = session
                                session += segmentEnd - segmentStart
                                longestSession = max(longestSession, session)
                                // A missing service cannot have enforced imaginary breaks inside measured use.
                                if (!app.intentionalOpenEnabled && previousSession < app.limits.sessionMillis &&
                                    session >= app.limits.sessionMillis) limitEvents++
                            }
                            lastSessionEnd = segmentEnd
                            observedEnd = segmentEnd
                        }
                        longestSession = max(longestSession, end - start)
                    }
                }
                val used = app.usedTodayMillis + addedDaily
                val dailyBlocked = app.dailyBlocked || used >= app.limits.dailyMillis
                val travelActiveNow = state.travelException?.isActive(trustedNow) == true
                if (dailyBlocked && !app.dailyBlocked && !travelActiveNow) limitEvents++
                val recoveredUsage = intervals.asSequence().filter { it.packageName == app.packageName }.sumOf { interval ->
                    val start = max(interval.startWallMillis, recoveryStart)
                    val end = min(interval.endWallMillis, trustedNow)
                    (end - start).coerceAtLeast(0) - (state.travelException?.overlapMillis(start, end) ?: 0)
                }
                val plannedLeft = app.plannedUsageRemainingMillis?.let { (it - recoveredUsage).coerceAtLeast(0) }
                val plannedEnded = app.intentionalOpenEnabled &&
                    (app.plannedUsageRemainingMillis ?: 0) > 0 && plannedLeft == 0L
                val overSession = !travelActiveNow && !app.intentionalOpenEnabled &&
                    session >= app.limits.sessionMillis && observedEnd != null
                // Recovery cannot pretend a break was enforced halfway through observed use.
                val lastPlannedUse = if (plannedEnded) intervals.asSequence()
                    .filter { it.packageName == app.packageName && min(it.endWallMillis, trustedNow) > max(it.startWallMillis, recoveryStart) }
                    .maxOfOrNull { min(it.endWallMillis, trustedNow) } else null
                val endedAt = if (travelActiveNow) null
                    else if (plannedEnded) lastPlannedUse else if (overSession) observedEnd else null
                val cooldownRemaining = maxOf(app.cooldown?.remainingMillis(now) ?: 0,
                    endedAt?.let { (it + app.limits.cooldownMillis - trustedNow).coerceAtLeast(0) } ?: 0)
                if (plannedEnded && !dailyBlocked && !travelActiveNow &&
                    lastPlannedUse != null && lastPlannedUse >= todayStart) limitEvents++
                if (lastSessionEnd != null && trustedNow - lastSessionEnd!! >= SESSION_GRACE_MILLIS &&
                    finalForegroundPackage != app.packageName) session = 0
                app.copy(
                    usedTodayMillis = used,
                    plannedUsageRemainingMillis = if (plannedEnded) null else plannedLeft,
                    plannedReason = if (plannedEnded) null else app.plannedReason,
                    sessionUsedMillis = if (travelActiveNow || dailyBlocked || overSession || plannedEnded) 0 else session,
                    sessionStartedAt = if (travelActiveNow) null else sessionStartedAt,
                    foregroundSince = null,
                    backgroundSince = observedEnd?.let { anchorAtWall(now, trustedNow, it) } ?: app.backgroundSince,
                    cooldown = if (cooldownRemaining > 0)
                        TimedBlock(nowAnchor, cooldownRemaining.coerceAtMost(TimedBlock.MAX_DURATION_MILLIS)) else null,
                    dailyBlocked = dailyBlocked,
                    limitEventsToday = limitEvents,
                    longestSessionTodayMillis = longestSession,
                )
            },
            clockAnchor = nowAnchor,
            recoveryCursorWallMillis = now.wallMillis,
        )
        return state
    }

    fun onForeground(
        snapshot: ProtectionSnapshot,
        packageName: String?,
        interactive: Boolean,
        now: ClockReading,
    ): EngineResult {
        val previouslyActive = snapshot.apps.firstOrNull { it.foregroundSince != null }?.packageName
        val prepared = prepare(snapshot, now, accrue = true)
        var state = prepared.snapshot
        if (prepared.decision != null && previouslyActive == packageName && interactive) return prepared

        val anchor = TimeAnchor.from(now, trustedWall(state, now))
        state = state.copy(apps = state.apps.map { app ->
            if (app.foregroundSince != null && app.packageName != packageName)
                app.copy(foregroundSince = null, backgroundSince = anchor)
            else app
        })
        if (!interactive || packageName == null) {
            val updatedApps = state.apps.map { app ->
                if (app.foregroundSince != null) app.copy(foregroundSince = null, backgroundSince = anchor) else app
            }
            state = state.copy(
                apps = updatedApps,
                clockAnchor = anchor,
                recoveryCursorWallMillis = if (updatedApps != state.apps) now.wallMillis else state.recoveryCursorWallMillis,
            )
            return EngineResult(state)
        }
        var candidate = state.apps.firstOrNull { it.packageName == packageName }
            ?: return EngineResult(state)

        if (previouslyActive != packageName) {
            val trustedNow = trustedWall(state, now)
            val duplicate = state.openEvents.lastOrNull()?.let {
                it.packageName == packageName && trustedNow - it.wallMillis in 0 until OPEN_EVENT_DEDUPE_MILLIS
            } == true
            if (!duplicate) state = state.copy(
                openEvents = (state.openEvents + OpenEvent(packageName, trustedNow)).takeLast(MAX_OPEN_EVENTS),
            )
        }

        if (candidate.cooldown != null && candidate.cooldown.remainingMillis(now) == 0L) {
            candidate = candidate.copy(cooldown = null)
            state = state.update(packageName) { candidate }
        }
        blockFor(state, candidate, now)?.let { return EngineResult(state, it) }

        var updated = candidate
        if (candidate.foregroundSince == null) {
            val gap = candidate.backgroundSince?.let { elapsedBetween(it, anchor) } ?: Long.MAX_VALUE
            val samePlannedSession = candidate.intentionalOpenEnabled &&
                (candidate.plannedUsageRemainingMillis != null || candidate.plannedAccess != null)
            val travelActive = state.travelException?.isActive(anchor.wallMillis) == true
            updated = candidate.copy(
                sessionUsedMillis = if (travelActive || gap >= SESSION_GRACE_MILLIS) 0 else candidate.sessionUsedMillis,
                sessionStartedAt = if (travelActive) null
                    else if ((gap >= SESSION_GRACE_MILLIS && !samePlannedSession) || candidate.sessionStartedAt == null)
                        anchor else candidate.sessionStartedAt,
                foregroundSince = anchor,
                backgroundSince = null,
            )
            state = state.update(packageName) { updated }
        }
        return EngineResult(
            state.copy(clockAnchor = anchor, recoveryCursorWallMillis = now.wallMillis),
            nextWakeupMillis = nextWakeup(updated, state, now),
        )
    }

    fun tick(snapshot: ProtectionSnapshot, now: ClockReading): EngineResult = prepare(snapshot, now, accrue = true)

    fun view(snapshot: ProtectionSnapshot, app: ProtectedApp, now: ClockReading): AppView {
        val trusted = trustedWall(snapshot, now)
        val anchor = TimeAnchor.from(now, trusted)
        val delta = app.foregroundSince?.let { elapsedBetween(it, anchor) } ?: 0
        val used = app.usedTodayMillis + delta
        val session = app.foregroundSince?.let { sessionAfterAccrual(snapshot, app, it, anchor, now).first }
            ?: app.sessionUsedMillis
        val cooldown = app.cooldown?.remainingMillis(now) ?: 0
        val group = snapshot.groups.firstOrNull { app.packageName in it.policy.packageNames }
        val groupUsed = group?.let { liveGroupUsage(snapshot, it, now) } ?: 0
        val groupLimit = group?.policy?.dailyMillis ?: 0
        val schedule = activeSchedule(snapshot, app.packageName, trustedWall(snapshot, now))
        val scheduleRemaining = schedule?.policy?.activeRemainingMillis(
            trustedWall(snapshot, now), snapshot.quotaZoneId,
        ) ?: 0
        val emergencyWindow = app.emergencyRequest?.remainingMillis(now) ?: 0
        val emergencyWait = (emergencyWindow - EMERGENCY_ACCESS_MILLIS).coerceAtLeast(0)
        val emergencyAccess = when {
            emergencyWindow in 1..EMERGENCY_ACCESS_MILLIS -> emergencyWindow
            else -> app.emergencyAccess?.remainingMillis(now) ?: 0
        }
        val focusRemaining = snapshot.focusSession
            ?.takeIf { app.packageName in it.packageNames }
            ?.block?.remainingMillis(now) ?: 0
        val travelOverlap = app.foregroundSince?.let {
            snapshot.travelException?.overlapMillis(it.wallMillis, anchor.wallMillis)
        } ?: 0
        val plannedRemaining = plannedRemaining(app, now, (delta - travelOverlap).coerceAtLeast(0))
        val availability = when {
            snapshot.travelException?.isActive(trusted) == true ->
                if (app.foregroundSince != null) AppAvailability.ACTIVE else AppAvailability.AVAILABLE
            focusRemaining > 0 -> AppAvailability.FOCUS_BLOCKED
            emergencyAccess > 0 -> AppAvailability.EMERGENCY_ACCESS
            emergencyWait > 0 -> AppAvailability.EMERGENCY_WAIT
            app.manualBlocked -> AppAvailability.MANUAL_BLOCKED
            app.dailyBlocked || used >= app.limits.dailyMillis -> AppAvailability.DAILY_BLOCKED
            group != null && groupUsed >= groupLimit -> AppAvailability.GROUP_BLOCKED
            schedule != null -> AppAvailability.SCHEDULE_BLOCKED
            cooldown > 0 -> AppAvailability.COOLDOWN
            app.pendingRemoval != null -> AppAvailability.REMOVAL_PENDING
            plannedRemaining > 0 -> AppAvailability.PLANNED_ACCESS
            app.foregroundSince != null -> AppAvailability.ACTIVE
            app.intentionalOpenEnabled -> AppAvailability.INTENTION_REQUIRED
            else -> AppAvailability.AVAILABLE
        }
        return AppView(
            used, session, cooldown, availability, group?.policy?.label, groupUsed, groupLimit,
            schedule?.policy?.label, scheduleRemaining, emergencyWait, emergencyAccess,
            focusRemaining, plannedRemaining,
        )
    }

    fun trustedWall(snapshot: ProtectionSnapshot, now: ClockReading): Long =
        if (snapshot.clockAnchor.bootCount >= 0 && snapshot.clockAnchor.bootCount == now.bootCount &&
            now.elapsedMillis >= snapshot.clockAnchor.elapsedMillis
        ) snapshot.clockAnchor.wallMillis + (now.elapsedMillis - snapshot.clockAnchor.elapsedMillis)
        else max(now.wallMillis, snapshot.clockAnchor.wallMillis)

    fun dayStartWall(snapshot: ProtectionSnapshot): Long =
        LocalDate.ofEpochDay(snapshot.activeEpochDay).atStartOfDay(ZoneId.of(snapshot.quotaZoneId))
            .toInstant().toEpochMilli()

    fun nextDayWall(snapshot: ProtectionSnapshot): Long =
        LocalDate.ofEpochDay(snapshot.activeEpochDay + 1).atStartOfDay(ZoneId.of(snapshot.quotaZoneId))
            .toInstant().toEpochMilli()

    private fun prepare(snapshot: ProtectionSnapshot, now: ClockReading, accrue: Boolean): EngineResult {
        var state = rebaseClock(snapshot, now)
        val trusted = trustedWall(state, now)
        state = advanceDays(state, trusted, now)
        state = applyMatured(state, trusted, now)
        if (state.focusSession?.block?.remainingMillis(now) == 0L) state = state.copy(focusSession = null)
        state = state.copy(
            openEvents = state.openEvents.filter { trusted - it.wallMillis <= OPEN_EVENT_RETENTION_MILLIS },
        )
        if (!accrue) return EngineResult(state)
        val active = state.apps.firstOrNull { it.foregroundSince != null } ?: return EngineResult(state)
        val anchor = TimeAnchor.from(now, trustedWall(state, now))
        val delta = elapsedBetween(active.foregroundSince!!, anchor)
        val travelOverlap = state.travelException?.overlapMillis(active.foregroundSince.wallMillis, anchor.wallMillis) ?: 0
        val (sessionAfter, sessionStartAfter) = sessionAfterAccrual(state, active, active.foregroundSince, anchor, now)
        var updated = active.copy(
            usedTodayMillis = active.usedTodayMillis + delta,
            plannedUsageRemainingMillis = active.plannedUsageRemainingMillis
                ?.let { (it - (delta - travelOverlap).coerceAtLeast(0)).coerceAtLeast(0) },
            sessionUsedMillis = sessionAfter,
            sessionStartedAt = sessionStartAfter,
            longestSessionTodayMillis = max(active.longestSessionTodayMillis, active.sessionUsedMillis + delta),
            foregroundSince = anchor,
        )
        var decision: BlockDecision? = null
        val emergencyWindow = updated.emergencyRequest?.remainingMillis(now) ?: 0
        val emergencyActive = emergencyWindow in 1..EMERGENCY_ACCESS_MILLIS ||
            (updated.emergencyAccess?.remainingMillis(now) ?: 0) > 0
        val focusActive = state.focusSession?.let {
            active.packageName in it.packageNames && it.block.remainingMillis(now) > 0
        } == true
        val plannedRemaining = plannedRemaining(updated, now)
        if (state.travelException?.isActive(trusted) == true) {
            // Count observed use but suspend every protected-app block and session break.
        } else if (emergencyActive && !focusActive) {
            // Usage still accrues, but all blocking reasons are temporarily bypassed.
        } else if (focusActive) {
            updated = updated.copy(foregroundSince = null)
            decision = decision(updated, BlockReason.FOCUS_SESSION, now, snapshot = state)
        } else if (updated.manualBlocked) {
            updated = updated.copy(foregroundSince = null, sessionUsedMillis = 0, cooldown = null)
            decision = decision(updated, BlockReason.MANUAL_LOCK, now)
        } else if (updated.dailyBlocked || updated.usedTodayMillis >= updated.limits.dailyMillis) {
            val wasBlocked = updated.dailyBlocked
            updated = updated.copy(
                dailyBlocked = true,
                foregroundSince = null,
                sessionUsedMillis = 0,
                limitEventsToday = if (wasBlocked) updated.limitEventsToday else updated.limitEventsToday + 1,
            )
            decision = decision(updated, BlockReason.DAILY_LIMIT, now)
        } else {
            val stateWithUsage = state.update(active.packageName) { updated }
            val group = stateWithUsage.groups.firstOrNull { active.packageName in it.policy.packageNames }
            if (group != null && groupUsage(stateWithUsage, group) >= group.policy.dailyMillis) {
                updated = updated.copy(
                    foregroundSince = null,
                    sessionUsedMillis = 0,
                    limitEventsToday = updated.limitEventsToday + 1,
                )
                decision = decision(updated, BlockReason.GROUP_DAILY_LIMIT, now, group, stateWithUsage)
            } else {
                val schedule = activeSchedule(
                    stateWithUsage,
                    active.packageName,
                    trustedWall(stateWithUsage, now),
                )
                if (schedule != null) {
                    updated = updated.copy(foregroundSince = null, sessionUsedMillis = 0)
                    decision = decision(
                        updated, BlockReason.SCHEDULED_BLOCK, now,
                        schedule = schedule, snapshot = stateWithUsage,
                    )
                } else if (!updated.intentionalOpenEnabled && updated.sessionUsedMillis >= updated.limits.sessionMillis) {
                    updated = updated.copy(
                        sessionUsedMillis = 0, foregroundSince = null,
                        cooldown = TimedBlock(anchor, updated.limits.cooldownMillis),
                        limitEventsToday = updated.limitEventsToday + 1,
                    )
                    decision = decision(updated, BlockReason.SESSION_LIMIT, now)
                } else if (updated.intentionalOpenEnabled && plannedRemaining == 0L) {
                    val reason = if (updated.plannedUsageRemainingMillis == null) BlockReason.INTENTION_REQUIRED
                    else BlockReason.PLANNED_SESSION_END
                    updated = updated.copy(foregroundSince = null,
                        limitEventsToday = updated.limitEventsToday + if (reason == BlockReason.PLANNED_SESSION_END) 1 else 0)
                    decision = decision(updated, reason, now)

                }
            }
        }
        if (decision != null && updated.intentionalOpenEnabled && updated.plannedUsageRemainingMillis == 0L) {
            updated = updated.copy(sessionUsedMillis = 0, plannedAccess = null,
                plannedUsageRemainingMillis = null, plannedReason = null,
                cooldown = TimedBlock(anchor, updated.limits.cooldownMillis))
            decision = decision.copy(cooldownRemainingMillis = updated.limits.cooldownMillis)
        }
        state = state.update(active.packageName) { updated }.copy(
            clockAnchor = anchor,
            recoveryCursorWallMillis = now.wallMillis,
        )
        return EngineResult(state, decision, if (decision == null) nextWakeup(updated, state, now) else null)
    }

    private fun applyMatured(snapshot: ProtectionSnapshot, trustedWallMillis: Long, now: ClockReading): ProtectionSnapshot {
        val strictOff = snapshot.strictModeDisableAtWallMillis?.let { it <= trustedWallMillis } == true
        val apps = snapshot.apps.mapNotNull { original ->
            var app = original
            if (app.pendingRemoval?.isMature(snapshot.activeEpochDay, trustedWallMillis) == true &&
                snapshot.focusSession?.let { app.packageName in it.packageNames && it.block.remainingMillis(now) > 0 } != true) null
            else {
                // Honor disabling requests saved by older versions without the former delay.
                if (app.intentionalDisableAtWallMillis != null) {
                    app = app.copy(intentionalOpenEnabled = false, intentionalDisableAtWallMillis = null,
                        plannedAccess = null, plannedUsageRemainingMillis = null, plannedReason = null)
                }
                if (app.emergencyRequest != null && app.emergencyRequest.remainingMillis(now) == 0L) {
                    app = app.copy(emergencyRequest = null)
                }
                if (app.emergencyAccess != null && app.emergencyAccess.remainingMillis(now) == 0L) {
                    app = app.copy(emergencyAccess = null)
                }
                val pending = app.pendingPolicy?.takeIf {
                    it.effectiveEpochDay <= snapshot.activeEpochDay && it.effectiveWallMillis <= trustedWallMillis
                }
                if (pending == null) app else {
                    val limits = pending.policy.limitsFor(snapshot.activeEpochDay)
                    app.copy(
                        policy = pending.policy,
                        limits = limits,
                        pendingPolicy = null,
                        dailyBlocked = app.dailyBlocked || app.usedTodayMillis >= limits.dailyMillis,
                        sessionUsedMillis = if (app.intentionalOpenEnabled) app.sessionUsedMillis
                            else min(app.sessionUsedMillis, limits.sessionMillis),
                    )
                }
            }
        }
        val remainingPackages = apps.mapTo(mutableSetOf()) { it.packageName }
        val groups = snapshot.groups.mapNotNull { group ->
            if (group.pendingRemoval?.isMature(snapshot.activeEpochDay, trustedWallMillis) == true) null
            else {
                val pending = group.pendingPolicy?.takeIf {
                    it.effectiveEpochDay <= snapshot.activeEpochDay && it.effectiveWallMillis <= trustedWallMillis
                }
                val source = pending?.policy ?: group.policy
                val packages = source.packageNames.intersect(remainingPackages)
                if (packages.size < 2) null else group.copy(
                    policy = source.copy(packageNames = packages),
                    pendingPolicy = if (pending != null) null else group.pendingPolicy,
                )
            }
        }
        val schedules = snapshot.schedules.mapNotNull { schedule ->
            if (schedule.pendingRemoval?.isMature(snapshot.activeEpochDay, trustedWallMillis) == true) null
            else {
                val pending = schedule.pendingPolicy?.takeIf {
                    it.effectiveEpochDay <= snapshot.activeEpochDay &&
                        it.effectiveWallMillis <= trustedWallMillis
                }
                val source = pending?.policy ?: schedule.policy
                val packages = source.packageNames.intersect(remainingPackages)
                if (packages.isEmpty()) null else schedule.copy(
                    policy = source.copy(packageNames = packages),
                    pendingPolicy = if (pending != null) null else schedule.pendingPolicy?.let {
                        val pendingPackages = it.policy.packageNames.intersect(remainingPackages)
                        if (pendingPackages.isEmpty()) null
                        else it.copy(policy = it.policy.copy(packageNames = pendingPackages))
                    },
                )
            }
        }
        return snapshot.copy(
            strictModeEnabled = if (strictOff) false else snapshot.strictModeEnabled,
            strictModeDisableAtWallMillis = if (strictOff) null else snapshot.strictModeDisableAtWallMillis,
            apps = apps,
            groups = groups,
            schedules = schedules,
            focusSession = snapshot.focusSession?.let { session ->
                val packages = session.packageNames.intersect(remainingPackages)
                if (packages.isEmpty() || session.block.remainingMillis(now) == 0L) null
                else session.copy(packageNames = packages)
            },
        )
    }

    private fun advanceDays(snapshot: ProtectionSnapshot, trustedWallMillis: Long, now: ClockReading): ProtectionSnapshot {
        val today = epochDay(trustedWallMillis, snapshot.quotaZoneId)
        if (today <= snapshot.activeEpochDay) return snapshot
        var state = snapshot
        while (state.activeEpochDay < today) {
            val nextDay = state.activeEpochDay + 1
            val boundaryWall = LocalDate.ofEpochDay(nextDay).atStartOfDay(ZoneId.of(state.quotaZoneId))
                .toInstant().toEpochMilli()
            val boundaryAnchor = anchorAtWall(now, trustedWallMillis, boundaryWall)
            val activePackage = state.apps.firstOrNull { it.foregroundSince != null }?.packageName
            if (activePackage != null) {
                state = state.update(activePackage) { app ->
                    val delta = elapsedBetween(app.foregroundSince!!, boundaryAnchor)
                    val travelOverlap = state.travelException?.overlapMillis(
                        app.foregroundSince.wallMillis, boundaryWall,
                    ) ?: 0
                    val (sessionAtBoundary, sessionStartAtBoundary) = sessionAfterAccrual(
                        state, app, app.foregroundSince, boundaryAnchor, now,
                    )
                    app.copy(
                        usedTodayMillis = app.usedTodayMillis + delta,
                        plannedUsageRemainingMillis = app.plannedUsageRemainingMillis
                            ?.let { (it - (delta - travelOverlap).coerceAtLeast(0)).coerceAtLeast(0) },
                        sessionUsedMillis = sessionAtBoundary,
                        sessionStartedAt = sessionStartAtBoundary,
                        longestSessionTodayMillis = max(app.longestSessionTodayMillis, sessionAtBoundary),
                        foregroundSince = boundaryAnchor,
                    )
                }
            }
            state = recordCurrentDay(state)
            state = state.copy(activeEpochDay = nextDay)
            state = applyMatured(state, boundaryWall, now)
            state = state.copy(apps = state.apps.map { app ->
                val limits = app.policy.limitsFor(nextDay)
                app.copy(
                    limits = limits,
                    usedTodayMillis = 0,
                    dailyBlocked = false,
                    manualBlocked = false,
                    emergencyRequest = null,
                    emergencyAccess = null,
                    emergencyUsedEpochDay = null,
                    emergencyReason = null,
                    limitEventsToday = 0,
                    longestSessionTodayMillis = 0,
                    foregroundSince = if (app.packageName == activePackage) boundaryAnchor else null,
                    backgroundSince = if (app.packageName == activePackage) null else app.backgroundSince,
                )
            })
        }
        return state
    }

    private fun recordCurrentDay(snapshot: ProtectionSnapshot): ProtectionSnapshot {
        val record = DailyRecord(snapshot.activeEpochDay, snapshot.apps.map {
            DailyAppRecord(it.packageName, it.label, it.usedTodayMillis, it.limitEventsToday, it.longestSessionTodayMillis)
        })
        val history = (snapshot.history.filterNot { it.epochDay == record.epochDay } + record)
            .sortedBy { it.epochDay }
            .takeLast(HISTORY_DAYS)
        return snapshot.copy(history = history)
    }

    private fun hardBlockFor(snapshot: ProtectionSnapshot, app: ProtectedApp, now: ClockReading): BlockDecision? = when {
        snapshot.travelException?.isActive(trustedWall(snapshot, now)) == true -> null
        snapshot.focusSession?.let { app.packageName in it.packageNames && it.block.remainingMillis(now) > 0 } == true ->
            decision(app, BlockReason.FOCUS_SESSION, now, snapshot = snapshot)
        (app.emergencyRequest?.remainingMillis(now) ?: 0) in 1..EMERGENCY_ACCESS_MILLIS -> null
        (app.emergencyAccess?.remainingMillis(now) ?: 0) > 0 -> null
        app.manualBlocked -> decision(app, BlockReason.MANUAL_LOCK, now)
        app.dailyBlocked || app.usedTodayMillis >= app.limits.dailyMillis -> decision(app, BlockReason.DAILY_LIMIT, now)
        snapshot.groups.any { app.packageName in it.policy.packageNames && groupUsage(snapshot, it) >= it.policy.dailyMillis } -> {
            val group = snapshot.groups.first { app.packageName in it.policy.packageNames }
            decision(app, BlockReason.GROUP_DAILY_LIMIT, now, group, snapshot)
        }
        activeSchedule(snapshot, app.packageName, trustedWall(snapshot, now)) != null -> {
            val schedule = activeSchedule(snapshot, app.packageName, trustedWall(snapshot, now))!!
            decision(app, BlockReason.SCHEDULED_BLOCK, now, schedule = schedule, snapshot = snapshot)
        }
        (app.cooldown?.remainingMillis(now) ?: 0) > 0 -> decision(app, BlockReason.COOLDOWN, now)
        else -> null
    }

    private fun blockFor(snapshot: ProtectionSnapshot, app: ProtectedApp, now: ClockReading): BlockDecision? =
        if (snapshot.travelException?.isActive(trustedWall(snapshot, now)) == true) null
        else hardBlockFor(snapshot, app, now) ?: when {
            (app.emergencyRequest?.remainingMillis(now) ?: 0) in 1..EMERGENCY_ACCESS_MILLIS ||
                (app.emergencyAccess?.remainingMillis(now) ?: 0) > 0 -> null
            app.intentionalOpenEnabled && (plannedRemaining(app, now)) == 0L ->
                decision(app, BlockReason.INTENTION_REQUIRED, now)
            else -> null
        }

    private fun decision(
        app: ProtectedApp,
        reason: BlockReason,
        now: ClockReading,
        group: ProtectedGroup? = null,
        snapshot: ProtectionSnapshot? = null,
        schedule: ProtectedSchedule? = null,
    ) = BlockDecision(
        app.packageName,
        app.label,
        reason,
        app.usedTodayMillis,
        app.limits.dailyMillis,
        app.limits.sessionMillis,
        app.cooldown?.remainingMillis(now) ?: 0,
        group?.policy?.label,
        if (group != null && snapshot != null) groupUsage(snapshot, group) else 0,
        group?.policy?.dailyMillis ?: 0,
        schedule?.policy?.label,
        if (schedule != null && snapshot != null) schedule.policy.activeRemainingMillis(
            trustedWall(snapshot, now), snapshot.quotaZoneId,
        ) else 0,
    )

    private fun nextWakeup(app: ProtectedApp, snapshot: ProtectionSnapshot, now: ClockReading): Long? {
        if (app.foregroundSince == null) return null
        val travelRemaining = snapshot.travelException?.remainingMillis(trustedWall(snapshot, now)) ?: 0
        if (travelRemaining > 0) return minOf(CHECKPOINT_MILLIS, travelRemaining)
        val plannedRemaining = plannedRemaining(app, now)
        val dayRemaining = millisUntilNextDay(trustedWall(snapshot, now), snapshot.quotaZoneId)
        val emergencyRemaining = max(
            (app.emergencyRequest?.remainingMillis(now) ?: 0).takeIf { it in 1..EMERGENCY_ACCESS_MILLIS } ?: 0,
            app.emergencyAccess?.remainingMillis(now) ?: 0,
        )
        if (emergencyRemaining > 0) return minOf(CHECKPOINT_MILLIS, emergencyRemaining, dayRemaining.coerceAtLeast(1))
        val groupRemaining = snapshot.groups.firstOrNull { app.packageName in it.policy.packageNames }
            ?.let { (it.policy.dailyMillis - groupUsage(snapshot, it)).coerceAtLeast(1) }
            ?: Long.MAX_VALUE
        return minOf(
            CHECKPOINT_MILLIS,
            (app.limits.dailyMillis - app.usedTodayMillis).coerceAtLeast(1),
            if (app.intentionalOpenEnabled) Long.MAX_VALUE else (app.limits.sessionMillis - app.sessionUsedMillis).coerceAtLeast(1),
            groupRemaining,
            app.emergencyRequest?.remainingMillis(now)?.let { remaining ->
                if (remaining > EMERGENCY_ACCESS_MILLIS) remaining - EMERGENCY_ACCESS_MILLIS else remaining
            }?.coerceAtLeast(1) ?: Long.MAX_VALUE,
            (app.emergencyAccess?.remainingMillis(now) ?: Long.MAX_VALUE).coerceAtLeast(1),
            if (plannedRemaining > 0) plannedRemaining.coerceAtLeast(1) else Long.MAX_VALUE,
            dayRemaining.coerceAtLeast(1),
        )
    }

    private fun plannedRemaining(app: ProtectedApp, now: ClockReading, liveDelta: Long = 0): Long =
        app.plannedUsageRemainingMillis?.let { (it - liveDelta).coerceAtLeast(0) }
            ?: app.plannedAccess?.remainingMillis(now) ?: 0L

    /** Upgrade only the grant. Daily/session counters, policies, history and locks stay intact. */
    fun migratePlannedUsage(snapshot: ProtectionSnapshot): ProtectionSnapshot {
        if (snapshot.apps.none { it.plannedAccess != null }) return snapshot
        val saved = snapshot.clockAnchor
        val checkpoint = ClockReading(saved.wallMillis, saved.elapsedMillis, saved.bootCount)
        return snapshot.copy(apps = snapshot.apps.map { app ->
            if (app.plannedAccess == null) app else app.copy(
                plannedUsageRemainingMillis = app.plannedUsageRemainingMillis ?: app.plannedAccess.remainingMillis(checkpoint),
                plannedAccess = null,
            )
        })
    }

    private fun rebaseClock(stored: ProtectionSnapshot, now: ClockReading): ProtectionSnapshot {
        val snapshot = migratePlannedUsage(stored)
        if (snapshot.clockAnchor.bootCount == now.bootCount && now.elapsedMillis >= snapshot.clockAnchor.elapsedMillis) return snapshot
        val trusted = max(now.wallMillis, snapshot.clockAnchor.wallMillis)
        return snapshot.copy(
            clockAnchor = TimeAnchor.from(now, trusted),
            apps = snapshot.apps.map {
                it.copy(
                    cooldown = it.cooldown?.rebase(now),
                    plannedAccess = null,
                    foregroundSince = null,
                )
            },
            focusSession = snapshot.focusSession?.let { session ->
                session.block.rebase(now)?.let { session.copy(block = it) }
            },
        )
    }

    private fun PendingRemoval.isMature(day: Long, wall: Long) =
        effectiveEpochDay <= day && effectiveWallMillis <= wall

    private fun anchorAtWall(now: ClockReading, trustedNowWall: Long, targetWall: Long): TimeAnchor {
        val distance = (trustedNowWall - targetWall).coerceAtLeast(0)
        return TimeAnchor(targetWall, (now.elapsedMillis - distance).coerceAtLeast(0), now.bootCount)
    }

    private fun cooldownDeadlineWall(block: TimedBlock, trustedNowWall: Long, now: ClockReading): Long =
        if (block.startedAt.bootCount >= 0 && block.startedAt.bootCount == now.bootCount) {
            trustedNowWall + (block.startedAt.elapsedMillis + block.durationMillis - now.elapsedMillis)
        } else block.startedAt.wallMillis + block.durationMillis

    private fun usageByDay(
        intervals: List<UsageInterval>,
        startWall: Long,
        endWall: Long,
        zoneId: String,
    ): Map<Long, Map<String, Long>> {
        val zone = ZoneId.of(zoneId)
        val result = mutableMapOf<Long, MutableMap<String, Long>>()
        intervals.forEach { interval ->
            var cursor = max(interval.startWallMillis, startWall)
            val end = min(interval.endWallMillis, endWall)
            while (cursor < end) {
                val day = epochDay(cursor, zoneId)
                val boundary = LocalDate.ofEpochDay(day + 1).atStartOfDay(zone).toInstant().toEpochMilli()
                val segmentEnd = min(end, boundary)
                val dayMap = result.getOrPut(day) { mutableMapOf() }
                dayMap[interval.packageName] = (dayMap[interval.packageName] ?: 0) + (segmentEnd - cursor)
                cursor = segmentEnd
            }
        }
        return result
    }

    private fun mergeRecordUsage(
        existing: List<DailyAppRecord>,
        additions: Map<String, Long>,
        currentApps: List<ProtectedApp>,
    ): List<DailyAppRecord> {
        val labels = (existing.map { it.packageName to it.label } + currentApps.map { it.packageName to it.label }).toMap()
        val records = existing.associateBy { it.packageName }.toMutableMap()
        additions.forEach { (packageName, duration) ->
            val old = records[packageName]
            records[packageName] = if (old != null) old.copy(usedMillis = old.usedMillis + duration)
            else DailyAppRecord(packageName, labels[packageName] ?: packageName, duration, 0)
        }
        return records.values.sortedBy { it.label }
    }

    private fun validateGroupPolicy(snapshot: ProtectionSnapshot, policy: GroupPolicy) {
        val protectedPackages = snapshot.apps.mapTo(mutableSetOf()) { it.packageName }
        require(policy.packageNames.all(protectedPackages::contains)) {
            "Ortak limit yalnız korunan uygulamaları içerebilir."
        }
    }

    private fun validateSchedulePolicy(snapshot: ProtectionSnapshot, policy: SchedulePolicy) {
        val protectedPackages = snapshot.apps.mapTo(mutableSetOf()) { it.packageName }
        require(policy.packageNames.all(protectedPackages::contains)) {
            "Program yalnız korunan uygulamaları içerebilir."
        }
    }

    private fun pruneGroups(groups: List<ProtectedGroup>, remainingPackages: Set<String>): List<ProtectedGroup> =
        groups.mapNotNull { group ->
            val packages = group.policy.packageNames.intersect(remainingPackages)
            if (packages.size < 2) null else {
                val pending = group.pendingPolicy?.let {
                    val pendingPackages = it.policy.packageNames.intersect(remainingPackages)
                    if (pendingPackages.size < 2) null
                    else it.copy(policy = it.policy.copy(packageNames = pendingPackages))
                }
                group.copy(policy = group.policy.copy(packageNames = packages), pendingPolicy = pending)
            }
        }

    private fun pruneSchedules(
        schedules: List<ProtectedSchedule>,
        remainingPackages: Set<String>,
    ): List<ProtectedSchedule> = schedules.mapNotNull { schedule ->
        val packages = schedule.policy.packageNames.intersect(remainingPackages)
        if (packages.isEmpty()) null else {
            val pending = schedule.pendingPolicy?.let {
                val pendingPackages = it.policy.packageNames.intersect(remainingPackages)
                if (pendingPackages.isEmpty()) null
                else it.copy(policy = it.policy.copy(packageNames = pendingPackages))
            }
            schedule.copy(policy = schedule.policy.copy(packageNames = packages), pendingPolicy = pending)
        }
    }

    private fun activeSchedule(
        snapshot: ProtectionSnapshot,
        packageName: String,
        trustedWallMillis: Long,
    ): ProtectedSchedule? = snapshot.schedules.firstOrNull {
        packageName in it.policy.packageNames && it.policy.isActive(trustedWallMillis, snapshot.quotaZoneId)
    }

    private fun groupUsage(snapshot: ProtectionSnapshot, group: ProtectedGroup): Long =
        snapshot.apps.asSequence()
            .filter { it.packageName in group.policy.packageNames }
            .sumOf { it.usedTodayMillis }

    private fun liveGroupUsage(
        snapshot: ProtectionSnapshot,
        group: ProtectedGroup,
        now: ClockReading,
    ): Long {
        val anchor = TimeAnchor.from(now, trustedWall(snapshot, now))
        return snapshot.apps.asSequence()
            .filter { it.packageName in group.policy.packageNames }
            .sumOf { app -> app.usedTodayMillis + (app.foregroundSince?.let { elapsedBetween(it, anchor) } ?: 0) }
    }

    private fun ProtectionSnapshot.update(packageName: String, transform: (ProtectedApp) -> ProtectedApp) =
        copy(apps = apps.map { if (it.packageName == packageName) transform(it) else it })

    private fun elapsedBetween(start: TimeAnchor, end: TimeAnchor): Long =
        if (start.bootCount >= 0 && start.bootCount == end.bootCount && end.elapsedMillis >= start.elapsedMillis)
            end.elapsedMillis - start.elapsedMillis
        else (end.wallMillis - start.wallMillis).coerceAtLeast(0)

    private fun sessionAfterAccrual(
        snapshot: ProtectionSnapshot,
        app: ProtectedApp,
        start: TimeAnchor,
        end: TimeAnchor,
        now: ClockReading,
    ): Pair<Long, TimeAnchor?> {
        val travel = snapshot.travelException
        val overlap = travel?.overlapMillis(start.wallMillis, end.wallMillis) ?: 0
        if (overlap == 0L) return app.sessionUsedMillis + elapsedBetween(start, end) to app.sessionStartedAt
        // The trip begins a fresh logical session. Only post-trip use can trigger a break.
        val postTrip = (end.wallMillis - maxOf(start.wallMillis, travel!!.endWallMillis)).coerceAtLeast(0)
        val startAnchor = if (end.wallMillis >= travel.endWallMillis)
            anchorAtWall(now, end.wallMillis, maxOf(start.wallMillis, travel.endWallMillis)) else null
        return postTrip to startAnchor
    }

    private fun epochDay(wallMillis: Long, zoneId: String) =
        Instant.ofEpochMilli(wallMillis).atZone(ZoneId.of(zoneId)).toLocalDate().toEpochDay()

    private fun millisUntilNextDay(wallMillis: Long, zoneId: String): Long {
        val zone = ZoneId.of(zoneId)
        return Instant.ofEpochMilli(wallMillis).atZone(zone).toLocalDate().plusDays(1)
            .atStartOfDay(zone).toInstant().toEpochMilli() - wallMillis
    }
}

/** Defense in depth: launcher discovery and every mutation use this policy. */
object TargetPolicy {
    private val essential = setOf(
        "android", "com.android.systemui", "com.android.settings", "com.android.phone",
        "com.android.server.telecom", "com.android.permissioncontroller",
        "com.google.android.permissioncontroller", "com.android.packageinstaller",
        "com.google.android.packageinstaller", "com.samsung.android.packageinstaller",
        "com.samsung.android.honeyboard", "com.samsung.android.dialer",
        "com.samsung.android.incallui", "com.google.android.dialer",
        "com.android.emergency", "com.sec.android.emergencylauncher",
    )
    private val allowedPreinstalled = setOf(
        "com.google.android.youtube", "com.instagram.android", "com.zhiliaoapp.musically",
        "com.ss.android.ugc.trill",
    )

    fun mayProtect(packageName: String, ownPackage: String, systemApp: Boolean, rolePackages: Set<String>): Boolean =
        packageName.isNotBlank() && packageName != ownPackage && packageName !in essential &&
            packageName !in rolePackages && (!systemApp || packageName in allowedPreinstalled)
}
