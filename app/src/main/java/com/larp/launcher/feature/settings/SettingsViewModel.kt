package com.larp.launcher.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.larp.launcher.data.local.prefs.SettingsPrefs
import com.larp.launcher.data.repository.ModsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val dynamicColor: Boolean = true,
    val darkMode: String = "system",
    val apiUrl: String = ""
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsPrefs: SettingsPrefs,
    private val modsRepository: ModsRepository
) : ViewModel() {

    val state: StateFlow<SettingsUiState> = combine(
        settingsPrefs.dynamicColor,
        settingsPrefs.darkMode,
        settingsPrefs.apiUrl
    ) { dynamicColor, darkMode, apiUrl ->
        SettingsUiState(
            dynamicColor = dynamicColor,
            darkMode = darkMode,
            apiUrl = apiUrl
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = SettingsUiState()
    )

    fun setDynamicColor(enabled: Boolean) {
        viewModelScope.launch { settingsPrefs.setDynamicColor(enabled) }
    }

    fun setDarkMode(mode: String) {
        viewModelScope.launch { settingsPrefs.setDarkMode(mode) }
    }

    fun saveApiUrl(url: String) {
        viewModelScope.launch { settingsPrefs.setApiUrl(url) }
    }

    fun clearCache(onResult: (Int) -> Unit) {
        viewModelScope.launch {
            try {
                val count = modsRepository.clearCache()
                onResult(count)
            } catch (t: Throwable) {
                onResult(0)
            }
        }
    }
}
