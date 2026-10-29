package dev.personal.doomblocker.core

object IntentionalAccessPolicy {
    val allowedMinutes = setOf(1, 5, 10, 15)
    const val waitSeconds = 5
    const val maxReasonLength = 80

    fun canStart(minutes: Int, reason: String, waitRemainingSeconds: Int): Boolean =
        minutes in allowedMinutes && reason.isNotBlank() && reason.length <= maxReasonLength && waitRemainingSeconds == 0
}
