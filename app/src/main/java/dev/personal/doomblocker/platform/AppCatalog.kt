package dev.personal.doomblocker.platform

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.provider.Telephony
import android.telecom.TelecomManager
import android.view.inputmethod.InputMethodManager
import dev.personal.doomblocker.core.TargetPolicy

data class InstalledApp(val packageName: String, val label: String, val icon: Drawable)

class AppCatalog(private val context: Context) {
    private val pm get() = context.packageManager

    private fun excludedRoles(): Set<String> = buildSet {
        val homes = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        pm.queryIntentActivities(homes, PackageManager.ResolveInfoFlags.of(0)).forEach { add(it.activityInfo.packageName) }
        context.getSystemService(InputMethodManager::class.java).inputMethodList.forEach { add(it.packageName) }
        context.getSystemService(TelecomManager::class.java).defaultDialerPackage?.let { add(it) }
        Telephony.Sms.getDefaultSmsPackage(context)?.let { add(it) }
    }

    private fun eligible(info: ApplicationInfo, roles: Set<String>): Boolean = info.enabled &&
        TargetPolicy.mayProtect(
            info.packageName, context.packageName,
            info.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0,
            roles,
        )

    fun canProtect(packageName: String): Boolean = runCatching {
        val info = pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
        eligible(info, excludedRoles()) && pm.getLaunchIntentForPackage(packageName) != null
    }.getOrDefault(false)

    fun installedApps(): List<InstalledApp> {
        val roles = excludedRoles()
        return pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), PackageManager.ResolveInfoFlags.of(0),
        ).map { it.activityInfo.applicationInfo }.distinctBy { it.packageName }
            .filter { eligible(it, roles) }
            .map { InstalledApp(it.packageName, pm.getApplicationLabel(it).toString(), pm.getApplicationIcon(it)) }
            .sortedBy { it.label.lowercase(java.util.Locale.getDefault()) }
    }
}
