package dev.personal.doomblocker.platform

import dev.personal.doomblocker.core.UsageInterval

internal enum class UsageEventKind { RESUMED, PAUSED, STOPPED, SCREEN_ON, SCREEN_OFF, LOCKED, UNLOCKED, SHUTDOWN, STARTUP, OTHER }

/** Streaming parser; events before the accounting boundary establish state but add no usage. */
internal class UsageEventAccumulator(
    private val packages: Set<String>,
    private val start: Long,
    private val end: Long,
) {
    private var active: String? = null
    private var activeClass: String? = null
    private var interactive = true
    private var locked = false
    private var cursor = start
    private val intervals = mutableListOf<UsageInterval>()
    private var sawEvent = false

    fun accept(time: Long, kind: UsageEventKind, packageName: String? = null, className: String? = null) {
        if (time > end) return
        sawEvent = true
        accrue(time.coerceIn(start, end))
        when (kind) {
            UsageEventKind.RESUMED -> { active = packageName; activeClass = className }
            UsageEventKind.PAUSED, UsageEventKind.STOPPED -> if (active == packageName &&
                (activeClass == null || className == null || activeClass == className)) {
                active = null; activeClass = null
            }
            UsageEventKind.SCREEN_ON -> interactive = true
            UsageEventKind.SCREEN_OFF -> interactive = false
            UsageEventKind.LOCKED -> locked = true
            UsageEventKind.UNLOCKED -> locked = false
            UsageEventKind.SHUTDOWN, UsageEventKind.STARTUP -> {
                active = null; activeClass = null; interactive = false; locked = true
            }
            UsageEventKind.OTHER -> Unit
        }
    }

    private fun accrue(until: Long) {
        if (until <= cursor) return
        val target = active
        if (interactive && !locked && target != null && target in packages) {
            val previous = intervals.lastOrNull()
            if (previous?.packageName == target && previous.endWallMillis == cursor) {
                intervals[intervals.lastIndex] = previous.copy(endWallMillis = until)
            } else intervals += UsageInterval(target, cursor, until)
        }
        cursor = until
    }

    fun finish(): UsageRecovery {
        accrue(end)
        return UsageRecovery(
            intervals.groupBy { it.packageName }.mapValues { (_, spans) -> spans.sumOf { it.durationMillis } },
            intervals.toList(), active.takeIf { interactive && !locked },
            if (!sawEvent) "Android bu aralık için olay döndürmedi; eksiksiz ölçüm doğrulanamıyor." else null,
        )
    }
}
