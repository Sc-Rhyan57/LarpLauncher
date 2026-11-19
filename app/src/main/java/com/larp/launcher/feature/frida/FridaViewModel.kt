package com.larp.launcher.feature.frida

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.larp.launcher.domain.model.PatchProgress
import com.larp.launcher.patcher.GameLauncher
import com.larp.launcher.patcher.frida.FridaAgent
import com.larp.launcher.patcher.frida.FridaInjector
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class FridaUiState(
    val loading: Boolean = true,
    val agents: List<FridaAgent> = emptyList(),
    val gadgetConfig: String = "",
    val gadgetBundled: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class FridaViewModel @Inject constructor(
    private val fridaInjector: FridaInjector,
    private val gameLauncher: GameLauncher
) : ViewModel() {

    private val _state = MutableStateFlow(
        FridaUiState(
            loading = true,
            gadgetBundled = fridaInjector.isGadgetBundled()
        )
    )
    val state: StateFlow<FridaUiState> = _state.asStateFlow()

    private val _launchProgress = MutableStateFlow<PatchProgress?>(null)
    val launchProgress: StateFlow<PatchProgress?> = _launchProgress.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val agents = fridaInjector.listAgents()
                val config = fridaInjector.readGadgetConfig()
                _state.update {
                    it.copy(
                        loading = false,
                        agents = agents,
                        gadgetConfig = config,
                        gadgetBundled = fridaInjector.isGadgetBundled(),
                        error = null
                    )
                }
            } catch (t: Throwable) {
                _state.update {
                    it.copy(
                        loading = false,
                        error = t.message ?: "Falha ao carregar agentes"
                    )
                }
            }
        }
    }

    fun addAgent(
        name: String,
        description: String,
        author: String,
        agentJsBytes: ByteArray
    ) {
        if (name.isBlank()) {
            _state.update { it.copy(error = "Informe um nome para o agente") }
            return
        }
        if (agentJsBytes.isEmpty()) {
            _state.update { it.copy(error = "Selecione um agent.js válido") }
            return
        }
        viewModelScope.launch {
            try {
                fridaInjector.saveAgent(
                    name = name.trim(),
                    agentJs = agentJsBytes,
                    description = description.trim(),
                    author = author.trim()
                )
                refresh()
            } catch (t: Throwable) {
                _state.update {
                    it.copy(error = t.message ?: "Falha ao salvar agente")
                }
            }
        }
    }

    fun deleteAgent(id: String) {
        viewModelScope.launch {
            try {
                val deleted = fridaInjector.deleteAgent(id)
                if (!deleted) {
                    _state.update { it.copy(error = "Agente não encontrado") }
                }
                refresh()
            } catch (t: Throwable) {
                _state.update {
                    it.copy(error = t.message ?: "Falha ao excluir agente")
                }
            }
        }
    }

    fun saveGadgetConfig(json: String) {
        if (json.isBlank()) {
            _state.update { it.copy(error = "Config JSON vazio") }
            return
        }
        viewModelScope.launch {
            try {
                fridaInjector.writeGadgetConfig(json)
                refresh()
            } catch (t: Throwable) {
                _state.update {
                    it.copy(error = t.message ?: "Falha ao salvar config")
                }
            }
        }
    }

    fun uninstallGadget() {
        viewModelScope.launch {
            try {
                fridaInjector.uninstall()
                refresh()
            } catch (t: Throwable) {
                _state.update {
                    it.copy(error = t.message ?: "Falha ao desinstalar gadget")
                }
            }
        }
    }

    fun launchAgent(agent: FridaAgent) {
        if (!fridaInjector.isGadgetBundled()) {
            _state.update {
                it.copy(error = "libfrida-gadget.so não bundled no APK")
            }
            return
        }
        viewModelScope.launch {
            _launchProgress.value = PatchProgress(
                stage = PatchProgress.Stage.Preparing,
                message = "Iniciando injeção…",
                progress = 0f
            )
            try {
                gameLauncher.launchFridaAgent(agent.path).collect { progress ->
                    _launchProgress.value = progress
                    if (progress.stage == PatchProgress.Stage.Ready ||
                        progress.stage == PatchProgress.Stage.Failed) {
                        _launchProgress.value = null
                    }
                }
            } catch (t: Throwable) {
                _launchProgress.value = null
                _state.update {
                    it.copy(error = t.message ?: "Falha ao iniciar agente")
                }
            }
        }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    companion object {
        private const val TAG = "FridaViewModel"
    }
}
