package dev.personal.doomblocker.ui

import dev.personal.doomblocker.core.AppAvailability
import dev.personal.doomblocker.core.BlockReason

internal fun showsCooldownScreen(reason: BlockReason) =
    reason == BlockReason.COOLDOWN || reason == BlockReason.SESSION_LIMIT || reason == BlockReason.PLANNED_SESSION_END

/** Finishing a cooldown does not imply that a daily quota or another lock has ended. */
internal fun afterCooldownMessage(availability: AppAvailability): String = when (availability) {
    AppAvailability.DAILY_BLOCKED -> "Günlük hakkın doldu. Uygulama yarın yeniden kullanılabilir."
    AppAvailability.GROUP_BLOCKED -> "Ortak günlük hakkın doldu. Uygulama yarın yeniden kullanılabilir."
    AppAvailability.MANUAL_BLOCKED -> "Bugünlük kilidin devam ediyor."
    AppAvailability.FOCUS_BLOCKED -> "Odak süren devam ediyor. Uygulama bu süre boyunca kapalı."
    AppAvailability.SCHEDULE_BLOCKED -> "Çalışma programın devam ediyor. Uygulama şu an kapalı."
    AppAvailability.INTENTION_REQUIRED -> "Yeniden açtığında amacını ve kullanım süreni seçebilirsin."
    AppAvailability.EMERGENCY_WAIT -> "Acil erişim beklemesi devam ediyor."
    AppAvailability.REMOVAL_PENDING -> "Kaldırma isteğin bekliyor. Güncel kurallarını uygulama içinden görebilirsin."
    AppAvailability.COOLDOWN -> "Güncel mola bilgisi yenileniyor."
    else -> "İhtiyacın olduğunda uygulamayı yeniden açabilirsin. Diğer sınırların geçerlidir."
}
