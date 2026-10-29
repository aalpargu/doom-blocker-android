package dev.personal.doomblocker.platform

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import dev.personal.doomblocker.R
import dev.personal.doomblocker.ui.MainActivity

object AppQuickActions {
    fun install(context: Context) {
        val manager = context.getSystemService(ShortcutManager::class.java)
        val actions = QuickActionCatalog.specs.map { shortcut(context, it) }
        runCatching { manager.dynamicShortcuts = actions }
    }

    private fun shortcut(context: Context, spec: QuickActionSpec) = ShortcutInfo.Builder(context, spec.id)
        .setShortLabel(spec.shortLabel)
        .setLongLabel(spec.longLabel)
        .setIcon(Icon.createWithResource(context, R.drawable.ic_blocker))
        .setIntent(Intent(context, MainActivity::class.java).setAction(spec.action))
        .build()
}
