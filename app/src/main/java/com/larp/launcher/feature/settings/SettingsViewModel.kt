package com.larp.launcher.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.larp.launcher.core.LarpEnv
import com.larp.launcher.core.LarpEnvProvider
import com.larp.launcher.data.local.prefs.SettingsPrefs
import com.larp.launcher.data.repository.ModsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val dynamicColor: Boolean = true,
    val darkMode: String = "system",
    val env: LarpEnv = LarpEnv()
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsPrefs: SettingsPrefs,
    private val modsRepository: ModsRepository,
    private val envProvider: LarpEnvProvider
) : ViewModel() {

    private val _envState = MutableStateFlow(envProvider.current())
    val envState: StateFlow<LarpEnv> = _envState.asStateFlow()

    val state: StateFlow<SettingsUiState> = combine(
        settingsPrefs.dynamicColor,
        settingsPrefs.darkMode,
        envState
    ) { dynamicColor, darkMode, env ->
        SettingsUiState(dynamicColor, darkMode, env)
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

    fun saveEnv(env: LarpEnv) {
        viewModelScope.launch {
            envProvider.update(env)
            _envState.value = env
        }
    }

    fun resetEnv() {
        viewModelScope.launch {
            envProvider.resetToDefaults()
            _envState.value = envProvider.current()
        }
    }

    fun clearCache(onResult: (Int) -> Unit) {
        viewModelScope.launch {
            try {
                val count = modsRepository.clearCache()
                onResult(count)
            } catch (_: Throwable) {
                onResult(0)
            }
        }
    }
}
