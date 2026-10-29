package dev.personal.doomblocker.ui

import dev.personal.doomblocker.core.BlockReason
import dev.personal.doomblocker.core.IntentionalAccessPolicy
import dev.personal.doomblocker.core.PlannedEntry

internal data class EntryChoice(val requestedMinutes: Int, val usageMillis: Long, val capped: Boolean) {
    val label: String get() = entryDuration(usageMillis) + if (capped) " kaldı" else ""
}

internal fun entryChoices(entry: PlannedEntry?): List<EntryChoice> {
    if (entry == null || entry.availableMillis <= 0) return emptyList()
    return IntentionalAccessPolicy.allowedMinutes.sorted().map { minutes ->
        val requested = minutes * entry.unitMillis
        EntryChoice(minutes, minOf(requested, entry.availableMillis), entry.availableMillis < requested)
    }.distinctBy { it.usageMillis }
}

internal fun entryDuration(millis: Long): String {
    val seconds = (millis.coerceAtLeast(0) + 999) / 1_000
    val minutes = seconds / 60
    return when {
        minutes == 0L -> "$seconds sn"
        seconds % 60 == 0L -> "$minutes dk"
        else -> "$minutes dk ${seconds % 60} sn"
    }
}

internal fun entryBlockMessage(entry: PlannedEntry?): String? {
    val block = entry?.block
    return when {
    entry == null -> "Güncel kullanım hakkın yükleniyor."
    block != null -> when (block.reason) {
        BlockReason.DAILY_LIMIT -> "Bugünkü kullanım hakkın doldu."
        BlockReason.GROUP_DAILY_LIMIT -> "Bugünkü ortak kullanım hakkın doldu."
        BlockReason.COOLDOWN, BlockReason.SESSION_LIMIT, BlockReason.PLANNED_SESSION_END ->
            "Molan devam ediyor · ${entryDuration(block.cooldownRemainingMillis)} kaldı."
        BlockReason.SCHEDULED_BLOCK -> "Çalışma programın etkin · ${entryDuration(block.scheduleRemainingMillis)} kaldı."
        BlockReason.FOCUS_SESSION -> "Odak süren devam ediyor. Bitince yeniden açabilirsin."
        BlockReason.MANUAL_LOCK -> "Bu uygulama bugün için kilitli."
        BlockReason.INTENTION_REQUIRED -> null
    }
    entry.availableMillis <= 0 -> "Bugünkü kullanım hakkın doldu."
    else -> null
    }
}
