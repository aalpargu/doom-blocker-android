package dev.personal.doomblocker.data

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class DiagnosticEvent(
    val wallMillis: Long,
    val kind: String,
    val message: String,
    val packageName: String? = null,
)

/** Small local-only ring buffer. Package transitions are diagnostic metadata, never screen content. */
class DiagnosticJournal(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "diagnostics-v1.json"))
    private val mutex = Mutex()
    private val mutable = MutableStateFlow(load())
    val events = mutable.asStateFlow()

    suspend fun add(kind: String, message: String, packageName: String? = null) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val updated = (mutable.value + DiagnosticEvent(System.currentTimeMillis(), kind, message, packageName))
                .takeLast(MAX_EVENTS)
            // Diagnostic I/O must never interrupt foreground accounting or enforcement.
            runCatching { persist(updated) }
            mutable.value = updated
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        mutex.withLock {
            persist(emptyList())
            mutable.value = emptyList()
        }
    }

    fun exportText(zoneId: String = ZoneId.systemDefault().id): String {
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        val zone = ZoneId.of(zoneId)
        return buildString {
            appendLine("Doom Blocker yerel tanılama")
            events.value.forEach { event ->
                val time = Instant.ofEpochMilli(event.wallMillis).atZone(zone).format(formatter)
                append(time).append("  ").append(event.kind).append("  ")
                event.packageName?.let { append(it).append("  ") }
                appendLine(event.message)
            }
        }
    }

    private fun load(): List<DiagnosticEvent> = runCatching {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return@runCatching emptyList()
        val array = JSONArray(file.openRead().bufferedReader().use { it.readText() })
        (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            DiagnosticEvent(
                item.getLong("wall"), item.getString("kind"), item.getString("message"),
                item.optString("package").takeIf { it.isNotBlank() },
            )
        }.takeLast(MAX_EVENTS)
    }.getOrDefault(emptyList())

    private fun persist(events: List<DiagnosticEvent>) {
        val payload = JSONArray().apply {
            events.forEach { event ->
                put(JSONObject().put("wall", event.wallMillis).put("kind", event.kind)
                    .put("message", event.message).put("package", event.packageName))
            }
        }.toString().toByteArray(Charsets.UTF_8)
        val stream = file.startWrite()
        try {
            stream.write(payload)
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }

    companion object { const val MAX_EVENTS = 200 }
}
