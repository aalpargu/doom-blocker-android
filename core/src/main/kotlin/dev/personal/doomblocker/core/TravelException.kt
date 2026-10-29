package dev.personal.doomblocker.core

/** A local, one-time pause of enforcement for every protected app. The end is exclusive. */
data class TravelException(val startWallMillis: Long, val endWallMillis: Long) {
    init {
        require(startWallMillis >= 0)
        require(endWallMillis > startWallMillis)
        require(endWallMillis - startWallMillis <= MAX_DURATION_MILLIS)
    }

    fun isActive(wallMillis: Long): Boolean = wallMillis >= startWallMillis && wallMillis < endWallMillis

    fun remainingMillis(wallMillis: Long): Long =
        if (isActive(wallMillis)) endWallMillis - wallMillis else 0L

    fun overlapMillis(startWallMillis: Long, endWallMillis: Long): Long =
        (minOf(endWallMillis, this.endWallMillis) - maxOf(startWallMillis, this.startWallMillis)).coerceAtLeast(0)

    companion object { const val MAX_DURATION_MILLIS = 7L * 24 * 60 * 60 * 1_000 }
}
