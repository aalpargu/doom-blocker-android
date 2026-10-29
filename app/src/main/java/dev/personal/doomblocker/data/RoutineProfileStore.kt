package dev.personal.doomblocker.data

import android.content.Context
import androidx.core.content.edit
import dev.personal.doomblocker.core.Limits
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class RoutineProfile(
    val id: String,
    val label: String,
    val packageNames: Set<String>,
    val dailyMinutes: Int,
    val sessionMinutes: Int,
    val cooldownMinutes: Int,
) {
    init {
        require(id.isNotBlank() && label.isNotBlank() && label.length <= 40)
        require(packageNames.isNotEmpty())
        Limits(dailyMinutes, sessionMinutes, cooldownMinutes)
    }
}

/** Small local profile catalog. Profiles contain configuration only, never usage counters. */
class RoutineProfileStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(load())
    val profiles = mutable.asStateFlow()

    fun save(
        existingId: String?,
        label: String,
        packageNames: Set<String>,
        limits: Limits,
    ): Result<RoutineProfile> = runCatching {
        val profile = RoutineProfile(
            existingId ?: UUID.randomUUID().toString(),
            label.trim(),
            packageNames,
            limits.dailyMinutes,
            limits.sessionMinutes,
            limits.cooldownMinutes,
        )
        mutable.value = (mutable.value.filterNot { it.id == profile.id } + profile).sortedBy { it.label }
        persist()
        profile
    }

    fun remove(id: String): Result<Unit> = runCatching {
        mutable.value = mutable.value.filterNot { it.id == id }
        persist()
    }

    fun enrichRulesBackup(raw: String): String {
        val root = JSONObject(raw)
        root.put("routines", encodeProfiles())
        return root.toString(2)
    }

    fun importRulesBackup(raw: String): Result<Int> = runCatching {
        val array = JSONObject(raw).optJSONArray("routines") ?: return@runCatching 0
        require(array.length() <= 30) { "Yedek en fazla 30 plan profili içerebilir." }
        val imported = decodeProfiles(array)
        preferences.edit { putString(UNDO_KEY, encodeProfiles().toString()) }
        mutable.value = (mutable.value.filterNot { old -> imported.any { it.id == old.id } } + imported)
            .distinctBy { it.id }
            .sortedBy { it.label }
        persist()
        imported.size
    }

    fun validateRulesBackup(raw: String): Result<Int> = runCatching {
        val array = JSONObject(raw).optJSONArray("routines") ?: return@runCatching 0
        require(array.length() <= 30) { "Yedek en fazla 30 plan profili içerebilir." }
        decodeProfiles(array).size
    }

    fun hasImportUndo(): Boolean = preferences.contains(UNDO_KEY)

    fun undoLastImport(): Result<Unit> = runCatching {
        val raw = preferences.getString(UNDO_KEY, null) ?: return@runCatching
        mutable.value = decodeProfiles(JSONArray(raw)).distinctBy { it.id }.take(30).sortedBy { it.label }
        persist()
        preferences.edit { remove(UNDO_KEY) }
    }

    private fun load(): List<RoutineProfile> = runCatching {
        val raw = preferences.getString(KEY, null) ?: return@runCatching emptyList()
        decodeProfiles(JSONArray(raw)).distinctBy { it.id }.take(30)
    }.getOrDefault(emptyList())

    private fun decodeProfiles(array: JSONArray): List<RoutineProfile> =
        (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            RoutineProfile(
                id = item.getString("id"),
                label = item.getString("label"),
                packageNames = item.getJSONArray("packages").let { packages ->
                    (0 until packages.length()).mapTo(linkedSetOf()) { packages.getString(it) }
                },
                dailyMinutes = item.getInt("daily"),
                sessionMinutes = item.getInt("session"),
                cooldownMinutes = item.getInt("cooldown"),
            )
        }

    private fun persist() {
        preferences.edit { putString(KEY, encodeProfiles().toString()) }
    }

    private fun encodeProfiles() = JSONArray().apply {
            mutable.value.forEach { profile ->
                put(
                    JSONObject()
                        .put("id", profile.id)
                        .put("label", profile.label)
                        .put("packages", JSONArray(profile.packageNames.sorted()))
                        .put("daily", profile.dailyMinutes)
                        .put("session", profile.sessionMinutes)
                        .put("cooldown", profile.cooldownMinutes),
                )
            }
        }

    private companion object {
        const val PREFERENCES = "routine-profiles-v1"
        const val KEY = "profiles"
        const val UNDO_KEY = "pre-import-profiles"
    }
}
