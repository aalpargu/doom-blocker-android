package dev.personal.doomblocker.service

/** Pure policies kept outside Android callbacks so retry and transient-window behavior stay testable. */
internal object EnforcementRetryPolicy {
    private val delays = longArrayOf(600L, 1_000L, 2_000L, 5_000L, 10_000L)

    fun delayAfterAttempt(attempt: Int): Long = delays[(attempt - 1).coerceIn(delays.indices)]
}

internal data class StableForeground(val packageName: String?, val provisional: Boolean)

/** Only trust event metadata when it belongs to the actual foreground application window. */
internal object ForegroundWindowPolicy {
    fun eventPackage(eventPackage: String?, eventWindowId: Int, foregroundWindowId: Int?): String? =
        eventPackage?.takeIf { eventWindowId >= 0 && eventWindowId == foregroundWindowId }
}

internal class GateHandoff {
    private var packageName: String? = null
    private var token: Long? = null

    fun begin(packageName: String, token: Long) {
        this.packageName = packageName
        this.token = token
    }

    fun isWaitingFor(packageName: String) = this.packageName == packageName
    fun acceptFrame(token: Long) = this.token == token
    fun shouldDismissFor(foregroundPackage: String?, blockerPackage: String) =
        token != null && foregroundPackage != null &&
            foregroundPackage != packageName && foregroundPackage != blockerPackage
    fun clear() { packageName = null; token = null }
}

internal class ForegroundStabilizer(private val graceMillis: Long = 1_500L) {
    private var lastReliablePackage: String? = null
    private var lastReliableElapsed: Long = Long.MIN_VALUE

    fun resolve(observedPackage: String?, elapsedMillis: Long): StableForeground {
        if (observedPackage != null) {
            lastReliablePackage = observedPackage
            lastReliableElapsed = elapsedMillis
            return StableForeground(observedPackage, provisional = false)
        }
        val age = if (lastReliableElapsed == Long.MIN_VALUE) Long.MAX_VALUE else elapsedMillis - lastReliableElapsed
        val fallback = lastReliablePackage.takeIf { age in 0..graceMillis }
        if (fallback == null) clear()
        return StableForeground(fallback, provisional = fallback != null)
    }

    fun clear() {
        lastReliablePackage = null
        lastReliableElapsed = Long.MIN_VALUE
    }
}
