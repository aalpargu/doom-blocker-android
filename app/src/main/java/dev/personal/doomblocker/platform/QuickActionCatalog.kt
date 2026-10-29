package dev.personal.doomblocker.platform

internal data class QuickActionSpec(
    val id: String,
    val shortLabel: String,
    val longLabel: String,
    val action: String,
)

internal object QuickActionCatalog {
    const val ACTION_FOCUS_30 = "dev.personal.doomblocker.action.FOCUS_30"
    const val ACTION_LOCK_TODAY = "dev.personal.doomblocker.action.LOCK_TODAY"
    const val ACTION_ADD_APP = "dev.personal.doomblocker.action.ADD_APP"

    val specs = listOf(
        QuickActionSpec("focus-30", "30 dk odak", "Tüm korunan uygulamalarda 30 dakika odak başlat", ACTION_FOCUS_30),
        QuickActionSpec("lock-today", "Bugün kilitle", "Tüm korunan uygulamaları yarına kadar kilitle", ACTION_LOCK_TODAY),
        QuickActionSpec("add-app", "Uygulama ekle", "Yeni bir korunan uygulama ekle", ACTION_ADD_APP),
    )
}
