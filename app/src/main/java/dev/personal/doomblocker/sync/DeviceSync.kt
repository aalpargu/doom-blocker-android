package dev.personal.doomblocker.sync

import android.content.Context
import android.os.Build
import android.os.PowerManager
import dev.personal.doomblocker.BuildConfig
import dev.personal.doomblocker.R
import dev.personal.doomblocker.core.Limits
import dev.personal.doomblocker.core.UsageEngine
import dev.personal.doomblocker.data.ProtectionRepository
import dev.personal.doomblocker.platform.DeviceClock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.time.LocalDate
import java.util.UUID
import javax.net.ssl.HttpsURLConnection

class DeviceSync(
    context: Context, private val scope: CoroutineScope,
    private val repository: ProtectionRepository, private val clock: DeviceClock,
) {
    private val prefs = context.getSharedPreferences("device-sync-v1", Context.MODE_PRIVATE)
    private val vault = SyncSessionStore(context)
    private val power = context.getSystemService(PowerManager::class.java)
    private val labMode = context.resources.getBoolean(R.bool.lab_mode)
    private val mutex = Mutex()
    private val deviceId = prefs.getString("device", null) ?: UUID.randomUUID().toString().also {
        check(prefs.edit().putString("device", it).commit())
    }
    private val mutable = MutableStateFlow(SharedQuotaState(
        linked = prefs.contains("user"), email = prefs.getString("email", null)))
    val state = mutable.asStateFlow()
    val configured get() = !labMode && BuildConfig.SYNC_URL.isNotBlank() && BuildConfig.SYNC_PUBLIC_KEY.isNotBlank()
    private var session: JSONObject? = null
    private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch(Dispatchers.IO) {
            mutex.withLock { session = runCatching { vault.read() }.getOrNull() }
            while (isActive) {
                if (mutable.value.linked) sync()
                delay(if (power.isInteractive) 3_000 else 30_000)
            }
        }
    }

    suspend fun signIn(email: String, password: String, create: Boolean): Result<String> = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching {
                check(configured) { "Bulut bağlantısı bu APK'da yapılandırılmamış." }
                mutable.value = mutable.value.copy(busy = true, error = null)
                val result = request(if (create) "/auth/v1/signup" else "/auth/v1/token?grant_type=password",
                    JSONObject().put("email", email.trim()).put("password", password), null)
                if (!result.has("access_token")) return@runCatching "E-postana gelen doğrulama bağlantısını aç, ardından giriş yap."
                val user = result.getJSONObject("user")
                val uid = user.getString("id")
                val previous = prefs.getString("user", null)
                check(previous == null || previous == uid) { "Bu cihaz başka bir ortak hesaba bağlı. Aynı hesapla giriş yap." }
                normalizeExpiry(result)
                vault.write(result)
                check(prefs.edit().putString("user", uid).putString("email", user.optString("email", email)).commit())
                session = result
                mutable.value = SharedQuotaState(linked = true, email = user.optString("email", email))
                "Hesap bağlandı. Ortak kotan hazırlanıyor."
            }.also { result ->
                mutable.value = mutable.value.copy(busy = false, error = result.exceptionOrNull()?.message)
            }
        }
    }

    suspend fun sync(): Result<Unit> = withContext(Dispatchers.IO) {
        if (!mutex.tryLock()) return@withContext Result.success(Unit)
        try {
            runCatching {
                if (!mutable.value.linked) return@runCatching
                val snapshot = repository.state.value.snapshot ?: return@runCatching
                val sentAt = clock.now()
                val prepared = UsageEngine.tick(snapshot, sentAt).snapshot
                val payload = JSONArray()
                prepared.apps.forEach { app ->
                    val record = JSONObject().put("package", app.packageName).put("label", app.label)
                        .put("day", LocalDate.ofEpochDay(prepared.activeEpochDay).toString())
                        .put("used_ms", app.usedTodayMillis).put("daily_ms", app.limits.dailyMillis)
                    app.cooldown?.takeIf { it.remainingMillis(sentAt) > 0 }?.let {
                        record.put("cooldown_id", "${it.startedAt.bootCount}:${it.startedAt.wallMillis}")
                            .put("cooldown_ms", it.remainingMillis(sentAt))
                    }
                    payload.put(record)
                }
                // Carry the previous day across midnight even if its last checkpoint was not uploaded.
                prepared.history.filter { it.epochDay >= prepared.activeEpochDay - 1 }.forEach { day ->
                    day.apps.forEach historyRecord@ { record ->
                        val app = prepared.apps.firstOrNull { it.packageName == record.packageName } ?: return@historyRecord
                        payload.put(JSONObject().put("package", record.packageName).put("label", record.label)
                            .put("day", LocalDate.ofEpochDay(day.epochDay).toString())
                            .put("used_ms", record.usedMillis).put("daily_ms", app.limits.dailyMillis))
                    }
                }
                val body = JSONObject().put("p_device", deviceId).put("p_name", Build.MODEL.take(80))
                    .put("p_zone", prepared.quotaZoneId).put("p_apps", payload)
                val response = authenticated("/rest/v1/rpc/doom_sync", body)
                val receivedAt = clock.now()
                val appsJson = response.getJSONArray("apps")
                val apps = (0 until appsJson.length()).map { index ->
                    val item = appsJson.getJSONObject(index)
                    SharedApp(item.getString("package"), item.getString("label"), item.getLong("daily_ms"),
                        item.getLong("used_ms"), item.getLong("device_used_ms"), item.getLong("cooldown_ms"),
                        if (item.isNull("pending_daily_ms")) null else item.getLong("pending_daily_ms"),
                        if (item.isNull("pending_day")) null else item.getString("pending_day"))
                }
                // New tablet: install local measurement rules for installed shared targets.
                for (app in apps) {
                    if (repository.state.value.snapshot?.apps?.none { it.packageName == app.packageName } == true) {
                        val daily = (app.dailyMillis / 60_000L).toInt().coerceIn(1, 1440)
                        repository.add(app.packageName, app.label, Limits(daily, minOf(5, daily), 10))
                        // Apps absent on this device are still in the shared summary, never launched.
                    }
                }
                val devices = response.getJSONArray("devices")
                mutable.value = mutable.value.copy(
                    day = LocalDate.parse(response.getString("day")).toEpochDay(),
                    zone = response.getString("zone"), receivedElapsed = sentAt.elapsedMillis,
                    receivedBoot = sentAt.bootCount, cooldownReceivedElapsed = receivedAt.elapsedMillis, apps = apps,
                    devices = (0 until devices.length()).map { devices.getJSONObject(it).getString("name") },
                    error = null,
                )
            }.also {
                if (it.isFailure) mutable.value = mutable.value.copy(
                    error = it.exceptionOrNull()?.message ?: "Ortak kota güncellenemedi.")
            }
        } finally { mutex.unlock() }
    }

    suspend fun setDailyLimit(packageName: String, minutes: Int): Result<Unit> = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching {
                authenticated("/rest/v1/rpc/doom_set_daily_limit",
                    JSONObject().put("p_package", packageName).put("p_minutes", minutes))
                Unit
            }
        }.also { if (it.isSuccess) sync() }
    }

    private fun authenticated(path: String, body: JSONObject): JSONObject {
        var current = session ?: error("Ortak hesaba yeniden giriş yap.")
        if (current.optLong("expires_at", 0) < System.currentTimeMillis() / 1000 + 60) {
            current = request("/auth/v1/token?grant_type=refresh_token",
                JSONObject().put("refresh_token", current.getString("refresh_token")), null)
            normalizeExpiry(current)
            vault.write(current)
            session = current
        }
        return request(path, body, current.getString("access_token"))
    }

    private fun normalizeExpiry(value: JSONObject) {
        if (!value.has("expires_at")) value.put("expires_at",
            System.currentTimeMillis() / 1000 + value.optLong("expires_in", 3600))
    }

    private fun request(path: String, body: JSONObject, token: String?): JSONObject {
        val base = URL(BuildConfig.SYNC_URL)
        check(base.protocol == "https" && base.host.endsWith(".supabase.co")) { "Geçersiz bulut adresi." }
        val connection = URL(base.toString().trimEnd('/') + path).openConnection() as HttpsURLConnection
        try {
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 4_000
            connection.readTimeout = 4_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("apikey", BuildConfig.SYNC_PUBLIC_KEY)
            token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val raw = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                // Never surface tokens, response dumps, or SQL implementation details in the UI/logs.
                error(when (status) {
                    400, 422 -> "Giriş bilgileri, e-posta doğrulaması veya cihazın tarih/saat ayarını kontrol et."
                    401, 403 -> "Oturum doğrulanamadı. Aynı hesapla yeniden giriş yap."
                    429 -> "Çok sık istek gönderildi. Biraz sonra yeniden dene."
                    else -> "Senkronizasyon hizmetine ulaşılamadı (HTTP $status)."
                })
            }
            return if (raw.isBlank() || raw == "null") JSONObject() else JSONObject(raw)
        } finally { connection.disconnect() }
    }
}
