package com.larp.launcher.feature.mods.browser

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.larp.launcher.data.repository.ModsRepository
import com.larp.launcher.domain.model.ServerMod
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface InstallState {
    data object Idle : InstallState
    data class Downloading(val progress: Float) : InstallState
    data class Installing(val progress: Float) : InstallState
    data object Done : InstallState
    data class Error(val message: String) : InstallState
}

data class ModsUiState(
    val loading: Boolean = false,
    val mods: List<ServerMod> = emptyList(),
    val error: String? = null
)

@HiltViewModel
class ModsViewModel @Inject constructor(
    private val modsRepository: ModsRepository
) : ViewModel() {

    private val _state = MutableStateFlow(ModsUiState(loading = true))
    val state: StateFlow<ModsUiState> = _state.asStateFlow()

    private val _installStates = MutableStateFlow<Map<String, InstallState>>(emptyMap())
    val installStates: StateFlow<Map<String, InstallState>> = _installStates.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val mods = modsRepository.listServerMods()
                _state.update { it.copy(loading = false, mods = mods) }
            } catch (t: Throwable) {
                _state.update { it.copy(loading = false, error = t.message ?: "Falha ao carregar mods") }
            }
        }
    }

    fun install(mod: ServerMod) {
        val current = _installStates.value[mod.id]
        if (current is InstallState.Downloading || current is InstallState.Installing) return
        setInstallState(mod.id, InstallState.Downloading(0f))
        viewModelScope.launch {
            try {
                val ticket = modsRepository.requestTicket(mod.id)
                val file = modsRepository.downloadTicket(ticket) { read, total ->
                    val ratio = if (total > 0) read.toFloat() / total else 0f
                    setInstallState(mod.id, InstallState.Downloading(ratio))
                }
                setInstallState(mod.id, InstallState.Installing(0.5f))
                modsRepository.installMod(mod, file, ticket.key)
                setInstallState(mod.id, InstallState.Done)
                refresh()
            } catch (t: Throwable) {
                setInstallState(mod.id, InstallState.Error(t.message ?: "Falha na instalação"))
            }
        }
    }

    fun uninstall(mod: ServerMod) {
        viewModelScope.launch {
            try {
                modsRepository.uninstallMod(mod.id)
                refresh()
            } catch (t: Throwable) {
                setInstallState(mod.id, InstallState.Error(t.message ?: "Falha ao remover"))
            }
        }
    }

    private fun setInstallState(modId: String, state: InstallState) {
        _installStates.update { it + (modId to state) }
    }
}
