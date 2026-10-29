package dev.personal.doomblocker.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.personal.doomblocker.BlockerApplication
import dev.personal.doomblocker.core.UsageEngine
import dev.personal.doomblocker.sync.SharedQuota
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class DeviceSyncActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val app = application as BlockerApplication
            val preferences by app.uiPreferences.state.collectAsStateWithLifecycle()
            val cloud by app.deviceSync.state.collectAsStateWithLifecycle()
            val local by app.repository.state.collectAsStateWithLifecycle()
            val scope = rememberCoroutineScope()
            var email by rememberSaveable { mutableStateOf(cloud.email.orEmpty()) }
            // Password deliberately excluded from saved state.
            var password by remember { mutableStateOf("") }
            var message by remember { mutableStateOf<String?>(null) }
            var editing by remember { mutableStateOf(false) }
            var now by remember { mutableStateOf(app.clock.now()) }
            LaunchedEffect(Unit) { while (true) { now = app.clock.now(); delay(1_000) } }
            DoomBlockerTheme(preferences.amoledBlack, preferences.accent) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()
                        .verticalScroll(rememberScrollState()).padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        TextButton(onClick = ::finish) { Text("‹ Geri") }
                        Text("Cihazlarım", style = MaterialTheme.typography.headlineLarge)
                        Text("Tek günlük hak, telefon ve tablette ortak kullanım.",
                            style = MaterialTheme.typography.bodyLarge)
                        if (!app.deviceSync.configured) {
                            Text("Bu sürümün bulut bağlantısı henüz yapılandırılmamış. Yerel koruma devam ediyor.")
                        } else if (!cloud.linked || editing) {
                            Text("Önce telefonda hesap oluştur, ardından tablette aynı e-posta ve parolayla giriş yap.")
                            Text("İlk kurulumda Supabase hesabında kullandığın e-posta adresini kullan. Uygulama için ayrı bir parola belirle (en az 8 karakter).", style = MaterialTheme.typography.bodySmall)
                            Text("Bağlandığında korunan uygulamaların adları, günlük süreleri, kullanım toplamları, mola bilgisi ve cihaz modeli kendi bulut hesabına gönderilir. Ekran içeriği ve açma nedenlerin gönderilmez.",
                                style = MaterialTheme.typography.bodySmall)
                            OutlinedTextField(email, { email = it }, label = { Text("E-posta") },
                                modifier = Modifier.fillMaxWidth(), singleLine = true,
                                enabled = !cloud.busy && !cloud.linked,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
                            OutlinedTextField(password, { password = it }, label = { Text("Parola") },
                                modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !cloud.busy,
                                visualTransformation = PasswordVisualTransformation())
                            fun authenticate(create: Boolean) {
                                scope.launch {
                                    val result = app.deviceSync.signIn(email, password, create)
                                    password = ""
                                    message = result.getOrElse { it.message ?: "Giriş yapılamadı." }
                                    if (result.isSuccess && app.deviceSync.state.value.linked) {
                                        editing = false
                                        app.deviceSync.sync()
                                    }
                                }
                            }
                            Button(onClick = { authenticate(false) },
                                enabled = !cloud.busy && email.isNotBlank() && password.isNotBlank(),
                                modifier = Modifier.fillMaxWidth()) { Text(if (cloud.busy) "Bağlanıyor…" else "Giriş yap") }
                            if (!cloud.linked) OutlinedButton(onClick = { authenticate(true) },
                                enabled = !cloud.busy && email.isNotBlank() && password.length >= 8,
                                modifier = Modifier.fillMaxWidth()) { Text("Hesap oluştur ve bağlan") }
                        } else {
                            Text(cloud.email.orEmpty(), style = MaterialTheme.typography.titleMedium)
                            val snapshot = local.snapshot
                            val fresh = snapshot != null && SharedQuota.fresh(cloud, snapshot, now)
                            Text(if (fresh) "Senkronizasyon güncel" else "Ortak kota güncelleniyor…",
                                color = if (fresh) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                            Text("Bağlı cihazlar: " + cloud.devices.joinToString().ifBlank { "Hazırlanıyor" })
                            Text("Ortak süre uygulama başınadır. Yerel oturum, program ve daha sıkı limitler de geçerlidir. Kullanım birkaç saniyede bir eşitlenir.",
                                style = MaterialTheme.typography.bodySmall)
                            for (target in cloud.apps) {
                                val own = snapshot?.apps?.firstOrNull { it.packageName == target.packageName }
                                val used = if (snapshot != null && own != null) UsageEngine.view(snapshot, own, now).usedTodayMillis else 0L
                                val remaining = SharedQuota.remaining(target, used)
                                val totalUsed = target.usedMillis + (used - target.deviceUsedMillis).coerceAtLeast(0)
                                var minutes by rememberSaveable(target.packageName, target.dailyMillis) {
                                    mutableStateOf((target.dailyMillis / 60_000).toString())
                                }
                                Surface(color = MaterialTheme.colorScheme.surfaceContainer,
                                    shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Text(target.label, style = MaterialTheme.typography.titleLarge)
                                        Text("Toplam ${duration(totalUsed)} / ${duration(target.dailyMillis)} · Kalan ${duration(remaining)}")
                                        val rest = SharedQuota.cooldown(target, cloud, now)
                                        if (rest > 0) Text("Ortak mola: ${duration(rest)}")
                                        target.pendingDailyMillis?.let {
                                            Text("${target.pendingDay}: ${duration(it)} günlük hak uygulanacak.")
                                        }
                                        OutlinedTextField(minutes, { minutes = it.filter(Char::isDigit).take(4) },
                                            label = { Text("Ortak günlük dakika") }, singleLine = true,
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                            modifier = Modifier.fillMaxWidth())
                                        Button(onClick = {
                                            scope.launch {
                                                message = app.deviceSync.setDailyLimit(target.packageName, minutes.toInt()).fold(
                                                    onSuccess = { "Kaydedildi. Artışlar yarın, azaltmalar hemen geçerli." },
                                                    onFailure = { it.message ?: "Kaydedilemedi." })
                                            }
                                        }, enabled = minutes.toIntOrNull() in 1..1440) { Text("Ortak limiti kaydet") }
                                    }
                                }
                            }
                            if (cloud.apps.isEmpty()) Text("Önce telefondaki ana ekrandan korunacak uygulamaları ekle.")
                            OutlinedButton(onClick = { scope.launch { app.deviceSync.sync() } }) { Text("Şimdi eşitle") }
                            TextButton(onClick = { email = cloud.email.orEmpty(); editing = true }) { Text("Oturumu yenile") }
                        }
                        (message ?: cloud.error)?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                    }
                }
            }
        }
    }
    private fun duration(ms: Long): String = "${ms / 60_000} dk ${ms / 1000 % 60} sn"
}
