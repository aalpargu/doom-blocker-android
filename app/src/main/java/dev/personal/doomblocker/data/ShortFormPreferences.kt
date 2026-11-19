package dev.personal.doomblocker.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDate

/**
 * Optional Reels / Shorts blocking. These switches are deliberately *not* tied to strict mode:
 * they turn on and off immediately, whenever the user wants.
 */
data class ShortFormState(
    val blockReels: Boolean = false,
    val blockShorts: Boolean = false,
    /** Apply protected apps' rules to their websites in browsers. Forced on while strict mode is on. */
    val blockBrowser: Boolean = false,
    /** How many times Reels/Shorts was closed today (local date). */
    val blockedToday: Int = 0,
    /** Wall time at which the next screen-structure capture is taken, or null when not armed. */
    val captureAtWallMillis: Long? = null,
    /** Short description of the last saved capture ("Instagram · 14:05"), or null. */
    val lastCaptureLabel: String? = null,
)

class ShortFormPreferences(private val context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(read())
    val state = mutable.asStateFlow()

    fun setBlockReels(enabled: Boolean) {
        preferences.edit { putBoolean(REELS, enabled) }
        mutable.value = mutable.value.copy(blockReels = enabled)
    }

    fun setBlockShorts(enabled: Boolean) {
        preferences.edit { putBoolean(SHORTS, enabled) }
        mutable.value = mutable.value.copy(blockShorts = enabled)
    }

    fun setBlockBrowser(enabled: Boolean) {
        preferences.edit { putBoolean(BROWSER, enabled) }
        mutable.value = mutable.value.copy(blockBrowser = enabled)
    }

    fun recordBlock() {
        val today = LocalDate.now().toEpochDay()
        val count = if (preferences.getLong(COUNT_DAY, -1) == today) preferences.getInt(COUNT, 0) + 1 else 1
        preferences.edit { putLong(COUNT_DAY, today); putInt(COUNT, count) }
        mutable.value = mutable.value.copy(blockedToday = count)
    }

    /** Arms a one-off capture [delayMillis] from now; expires after two minutes if no target app is open. */
    fun armCapture(delayMillis: Long) {
        val at = System.currentTimeMillis() + delayMillis
        preferences.edit { putLong(CAPTURE_AT, at) }
        mutable.value = mutable.value.copy(captureAtWallMillis = at)
    }

    fun clearCapture() {
        preferences.edit { remove(CAPTURE_AT) }
        mutable.value = mutable.value.copy(captureAtWallMillis = null)
    }

    fun saveCapture(label: String, content: String) {
        runCatching { context.openFileOutput(CAPTURE_FILE, Context.MODE_PRIVATE).bufferedWriter().use { it.write(content) } }
        preferences.edit { remove(CAPTURE_AT); putString(CAPTURE_LABEL, label) }
        mutable.value = mutable.value.copy(captureAtWallMillis = null, lastCaptureLabel = label)
    }

    fun readCapture(): String? = runCatching {
        context.openFileInput(CAPTURE_FILE).bufferedReader().use { it.readText() }
    }.getOrNull()

    private fun read(): ShortFormState {
        val today = LocalDate.now().toEpochDay()
        return ShortFormState(
            blockReels = preferences.getBoolean(REELS, false),
            blockShorts = preferences.getBoolean(SHORTS, false),
            blockBrowser = preferences.getBoolean(BROWSER, false),
            blockedToday = if (preferences.getLong(COUNT_DAY, -1) == today) preferences.getInt(COUNT, 0) else 0,
            captureAtWallMillis = preferences.getLong(CAPTURE_AT, 0L).takeIf { it > 0 },
            lastCaptureLabel = preferences.getString(CAPTURE_LABEL, null),
        )
    }

    private companion object {
        const val PREFERENCES = "short-form"
        const val REELS = "block-reels"
        const val SHORTS = "block-shorts"
        const val BROWSER = "block-browser"
        const val COUNT = "blocked-count"
        const val COUNT_DAY = "blocked-count-day"
        const val CAPTURE_AT = "capture-at"
        const val CAPTURE_LABEL = "capture-label"
        const val CAPTURE_FILE = "screen-structure.txt"
    }
}
