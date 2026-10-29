package dev.personal.doomblocker.platform

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.core.net.toUri
import dev.personal.doomblocker.core.ClockReading
import dev.personal.doomblocker.service.BlockerAccessibilityService

class DeviceClock(private val context: Context) {
    fun now() = ClockReading(
        System.currentTimeMillis(), SystemClock.elapsedRealtime(),
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1),
    )
}

data class PermissionState(val usageAccess: Boolean, val accessibilityEnabled: Boolean, val batteryExempt: Boolean)

object DeviceAccess {
    fun hasUsageAccess(context: Context): Boolean = context.getSystemService(AppOpsManager::class.java)
        .checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED

    fun permissions(context: Context): PermissionState {
        val manager = context.getSystemService(AccessibilityManager::class.java)
        val expected = ComponentName(context, BlockerAccessibilityService::class.java)
        val enabled = manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { ComponentName(it.resolveInfo.serviceInfo.packageName, it.resolveInfo.serviceInfo.name) == expected }
        return PermissionState(
            hasUsageAccess(context), enabled,
            context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName),
        )
    }

    fun usageSettings(context: Context) = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
        data = "package:${context.packageName}".toUri()
    }
    fun accessibilitySettings() = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
    fun appSettings(context: Context) = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())

    /** Diagnostic only. Never added to live usage, preventing two competing ledgers. */
    fun latestUsageEvent(context: Context): String {
        if (!hasUsageAccess(context)) return "Kullanım erişimi kapalı."
        val end = System.currentTimeMillis()
        val events = context.getSystemService(UsageStatsManager::class.java).queryEvents(end - 86_400_000, end)
            ?: return "Cihaz kilitli veya olay geçmişi kullanılamıyor."
        val event = UsageEvents.Event()
        var latest: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) latest = event.packageName
        }
        return latest?.let { "Son ACTIVITY_RESUMED: $it" } ?: "Son 24 saatte erişilebilir olay yok."
    }
}
