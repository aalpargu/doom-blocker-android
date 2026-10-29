package dev.personal.doomblocker.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel

/** Keeps navigation stable across rotation and process recreation. Domain state remains in the repository. */
internal class DoomBlockerViewModel(private val savedStateHandle: SavedStateHandle) : ViewModel() {
    val selectedSectionIndex = savedStateHandle.getStateFlow(SECTION_KEY, 0)

    fun selectSection(index: Int) {
        savedStateHandle[SECTION_KEY] = index
    }

    private companion object {
        const val SECTION_KEY = "selected_section"
    }
}
