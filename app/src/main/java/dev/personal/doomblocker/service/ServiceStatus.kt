package dev.personal.doomblocker.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class RuntimeStatus(
    val connected: Boolean = false,
    val connectedAtWallMillis: Long? = null,
    val lastEvaluationWallMillis: Long? = null,
    val foregroundPackage: String? = null,
    val homeRequests: Int = 0,
    val lastBlockedPackage: String? = null,
    val lastHomeAccepted: Boolean? = null,
    val pictureInPictureVisible: Boolean = false,
    val problem: String? = null,
    val probePackage: String? = null,
    val probeLabel: String? = null,
    val probeStartedWallMillis: Long? = null,
    val probeResult: ProtectionProbeResult? = null,
)

enum class ProtectionProbeResult { RUNNING, PASSED, FAILED, TIMED_OUT }

class ServiceStatus {
    private val mutable = MutableStateFlow(RuntimeStatus())
    val state = mutable.asStateFlow()
    // Called exclusively on the main thread.
    fun update(transform: (RuntimeStatus) -> RuntimeStatus) { mutable.value = transform(mutable.value) }

    fun beginProbe(packageName: String, label: String) = update {
        it.copy(
            probePackage = packageName,
            probeLabel = label,
            probeStartedWallMillis = System.currentTimeMillis(),
            probeResult = ProtectionProbeResult.RUNNING,
        )
    }

    fun clearProbe() = update {
        it.copy(probePackage = null, probeLabel = null, probeStartedWallMillis = null, probeResult = null)
    }
}
