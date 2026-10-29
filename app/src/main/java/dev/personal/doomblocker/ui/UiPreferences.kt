package dev.personal.doomblocker.ui

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AccentOption(val label: String) { MINT("Mint"), BLUE("Mavi"), PURPLE("Mor") }

data class UiPreferenceState(
    val amoledBlack: Boolean = false,
    val accent: AccentOption = AccentOption.MINT,
)

class UiPreferences(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(read())
    val state = mutable.asStateFlow()

    fun setAmoledBlack(enabled: Boolean) {
        preferences.edit { putBoolean(AMOLED, enabled) }
        mutable.value = mutable.value.copy(amoledBlack = enabled)
    }

    fun setAccent(accent: AccentOption) {
        preferences.edit { putString(ACCENT, accent.name) }
        mutable.value = mutable.value.copy(accent = accent)
    }

    private fun read() = UiPreferenceState(
        amoledBlack = preferences.getBoolean(AMOLED, false),
        accent = runCatching { AccentOption.valueOf(preferences.getString(ACCENT, null).orEmpty()) }
            .getOrDefault(AccentOption.MINT),
    )

    private companion object {
        const val PREFERENCES = "ui-preferences"
        const val AMOLED = "amoled-black"
        const val ACCENT = "accent"
    }
}
