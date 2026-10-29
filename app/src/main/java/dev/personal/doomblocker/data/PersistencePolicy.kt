package dev.personal.doomblocker.data

/** Keeps settings and foreground boundaries durable while allowing active counters to batch writes. */
internal object PersistencePolicy {
    fun shouldPersistImmediately(
        previousForeground: String?,
        nextForeground: String?,
        hasBlockingDecision: Boolean,
    ): Boolean = hasBlockingDecision || previousForeground != nextForeground
}
