package dev.personal.doomblocker.core

/** Undo changes rules through the normal delay policy; it never restores old usage counters. */
object RuleRestore {
    fun apply(current: ProtectionSnapshot, previous: ProtectionSnapshot, now: ClockReading): ProtectionSnapshot {
        var state = UsageEngine.tick(current, now).snapshot
        previous.apps.forEach { old ->
            if (state.apps.any { it.packageName == old.packageName }) {
                state = UsageEngine.updatePolicy(state, old.packageName, old.pendingPolicy?.policy ?: old.policy, now)
                state = UsageEngine.setIntentionalOpen(state, old.packageName, old.intentionalOpenEnabled, now)
            }
        }
        state.apps.filter { app -> previous.apps.none { it.packageName == app.packageName } }.forEach {
            state = UsageEngine.requestRemoval(state, it.packageName, now)
        }
        previous.groups.forEach { old ->
            if (state.groups.any { it.id == old.id }) state = UsageEngine.updateGroup(state, old.id, old.pendingPolicy?.policy ?: old.policy, now)
        }
        state.groups.filter { group -> previous.groups.none { it.id == group.id } }.forEach {
            state = UsageEngine.requestGroupRemoval(state, it.id, now)
        }
        previous.schedules.forEach { old ->
            if (state.schedules.any { it.id == old.id }) state = UsageEngine.updateSchedule(state, old.id, old.pendingPolicy?.policy ?: old.policy, now)
        }
        state.schedules.filter { schedule -> previous.schedules.none { it.id == schedule.id } }.forEach {
            state = UsageEngine.requestScheduleRemoval(state, it.id, now)
        }
        if (previous.strictModeEnabled) state = UsageEngine.enableStrictMode(state, now)
        else if (state.strictModeEnabled) state = UsageEngine.requestStrictModeDisable(state, now)
        return state
    }
}
