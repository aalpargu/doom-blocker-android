package dev.personal.doomblocker.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import dev.personal.doomblocker.R
import dev.personal.doomblocker.core.BlockReason
import dev.personal.doomblocker.core.ClockReading
import dev.personal.doomblocker.core.EngineResult
import dev.personal.doomblocker.core.UsageEngine
import dev.personal.doomblocker.ui.MainActivity
import dev.personal.doomblocker.platform.BreakSuggestions
import dev.personal.doomblocker.sync.SharedQuota
import kotlin.math.roundToInt

/** Optional, local-only warnings. Keys prevent repeated notifications for the same threshold/session. */
class LimitNotificationManager(private val context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val pendingCooldowns = mutableMapOf<String, String>()

    init {
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Doom Blocker limit uyarıları", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Günlük ve oturum limitleri yaklaşırken yerel uyarılar gösterir."
            },
        )
    }

    fun onEngineResult(result: EngineResult, now: ClockReading) {
        if (!isEnabled(context) || !hasPermission(context)) return
        val snapshot = result.snapshot
        if (SharedQuota.travelActive(snapshot, now)) {
            // Expired pre-trip cooldowns must not emit a delayed "break finished" notice later.
            pendingCooldowns.clear()
            return
        }
        val existing = snapshot.apps.mapTo(mutableSetOf()) { it.packageName }
        pendingCooldowns.keys.retainAll(existing)
        snapshot.apps.forEach { app ->
            val remaining = app.cooldown?.remainingMillis(now) ?: 0L
            if (remaining > 0) {
                pendingCooldowns[app.packageName] = "${snapshot.activeEpochDay}:${app.packageName}:cooldown:${app.cooldown?.startedAt?.wallMillis}"
            } else {
                val key = pendingCooldowns.remove(app.packageName)
                if (key != null && UsageEngine.view(snapshot, app, now).availability in setOf(
                        dev.personal.doomblocker.core.AppAvailability.AVAILABLE,
                        dev.personal.doomblocker.core.AppAvailability.INTENTION_REQUIRED,
                        dev.personal.doomblocker.core.AppAvailability.ACTIVE)) {
                    notifyOnce(key, app.packageName.hashCode() xor 0xC001, "${app.label} molası bitti", "Sınırların içinde yeni bir oturum açabilirsin.")
                }
            }
        }
        result.decision?.let { decision ->
            val target = snapshot.apps.firstOrNull { it.packageName == decision.packageName }
            val suggestion = target?.cooldown?.takeIf {
                decision.reason == BlockReason.SESSION_LIMIT ||
                    decision.reason == BlockReason.PLANNED_SESSION_END ||
                    decision.reason == BlockReason.COOLDOWN
            }?.let { BreakSuggestions.forCooldown(decision.packageName, it.startedAt.wallMillis) }
            val key = "${snapshot.activeEpochDay}:${decision.packageName}:blocked:${decision.reason}:${snapshot.apps.firstOrNull { it.packageName == decision.packageName }?.limitEventsToday}"
            notifyOnce(
                key,
                decision.packageName.hashCode(),
                "${decision.label} şu anda kapalı",
                listOfNotNull(blockExplanation(decision.reason), suggestion?.let { "${it.title}. ${it.message}" })
                    .joinToString("\n\n"),
            )
            return
        }
        val active = snapshot.apps.firstOrNull { it.foregroundSince != null } ?: return
        val view = UsageEngine.view(snapshot, active, now)
        if (view.emergencyAccessRemainingMillis > 0) return
        val dailyRatio = view.usedTodayMillis.toDouble() / active.limits.dailyMillis.coerceAtLeast(1)
        val dailyRemaining = formatRemainingDailyTime(
            (active.limits.dailyMillis - view.usedTodayMillis).coerceAtLeast(0),
        )
        when {
            dailyRatio >= .95 -> notifyOnce(
                "${snapshot.activeEpochDay}:${active.packageName}:daily95",
                active.packageName.hashCode(),
                "${active.label} günlük limitine çok yakın",
                "Günlük sürenin %${(dailyRatio * 100).roundToInt().coerceAtMost(100)} kadarı kullanıldı. Kalan: $dailyRemaining.",
            )
            dailyRatio >= .80 -> notifyOnce(
                "${snapshot.activeEpochDay}:${active.packageName}:daily80",
                active.packageName.hashCode(),
                "${active.label} günlük limitine yaklaşıyor",
                "Günlük sürenin %${(dailyRatio * 100).roundToInt()} kadarı kullanıldı. Kalan: $dailyRemaining.",
            )
        }
        val sessionRemaining = NotificationPolicy.sessionRemaining(active, view)
        if (sessionRemaining in 1..60_000L) {
            val sessionKey = NotificationPolicy.sessionKey(active, snapshot.quotaZoneId) ?: return
            notifyOnce(
                sessionKey,
                active.packageName.hashCode() xor 0x51E55,
                "${active.label} oturumu bitmek üzere",
                "Oturum limitine bir dakikadan az kaldı.",
            )
        }
    }

    private fun formatRemainingDailyTime(remainingMillis: Long): String {
        val totalSeconds = (remainingMillis + 999L) / 1_000L
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        return when {
            minutes == 0L -> "$seconds sn"
            seconds == 0L -> "$minutes dk"
            else -> "$minutes dk $seconds sn"
        }
    }

    private fun notifyOnce(key: String, id: Int, title: String, message: String) {
        // Keep yesterday's session key when a session crosses midnight.
        val newestDay = maxOf(
            key.substringBefore(':').toLongOrNull() ?: Long.MIN_VALUE,
            preferences.getStringSet(SENT_KEYS, emptySet()).orEmpty()
                .mapNotNull { it.substringBefore(':').toLongOrNull() }.maxOrNull() ?: Long.MIN_VALUE,
        )
        val sent = preferences.getStringSet(SENT_KEYS, emptySet()).orEmpty().filterTo(mutableSetOf()) {
            (it.substringBefore(':').toLongOrNull() ?: Long.MIN_VALUE) >= newestDay - 2
        }
        if (key in sent || sent.size >= 512) return
        val openApp = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_blocker)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(Notification.BigTextStyle().bigText(message))
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_REMINDER)
            .build()
        manager.notify(id, notification)
        preferences.edit {
            putStringSet(SENT_KEYS, sent.apply { add(key) })
        }
    }

    private fun blockExplanation(reason: BlockReason) = when (reason) {
        BlockReason.DAILY_LIMIT -> "Günlük süre doldu; yarın yeniden kullanılabilir."
        BlockReason.GROUP_DAILY_LIMIT -> "Ortak günlük limit doldu."
        BlockReason.MANUAL_LOCK -> "Bugünlük odak kilidi etkin."
        BlockReason.SCHEDULED_BLOCK -> "Etkin odak programı erişimi kapatıyor."
        BlockReason.SESSION_LIMIT -> "Oturum süresi doldu; zorunlu mola başladı."
        BlockReason.COOLDOWN -> "Zorunlu mola henüz bitmedi."
        BlockReason.FOCUS_SESSION -> "Süreli odak modu etkin."
        BlockReason.INTENTION_REQUIRED -> "Devam etmeden önce açma amacını seç."
        BlockReason.PLANNED_SESSION_END -> "Seçtiğin aktif kullanım süresi bitti; zorunlu mola başladı."
    }

    companion object {
        private const val PREFERENCES = "limit-notifications"
        private const val ENABLED = "enabled"
        private const val SENT_KEYS = "sent-keys"
        private const val CHANNEL_ID = "limit-warnings-v1"

        fun isEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getBoolean(ENABLED, false)

        fun setEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit { putBoolean(ENABLED, enabled) }
        }

        fun hasPermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }
}
