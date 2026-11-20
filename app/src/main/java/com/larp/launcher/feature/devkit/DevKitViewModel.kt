package com.larp.launcher.feature.devkit

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.larp.launcher.OverlayPermissionHolder
import com.larp.launcher.domain.model.DevKitSnapshot
import com.larp.launcher.domain.model.DevKitVariable
import com.larp.launcher.hook.LarpHookEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DevKitUiState(
    val variables: List<DevKitVariable> = emptyList(),
    val snapshot: DevKitSnapshot? = null,
    val devKitEnabled: Boolean = false,
    val canDrawOverlays: Boolean = false,
    val shadowHookReady: Boolean = false,
    val byteHookReady: Boolean = false,
    val isLoading: Boolean = true,
    val error: String? = null
)

@HiltViewModel
class DevKitViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val devKitStore: DevKitStore,
    private val devKitBridge: DevKitBridge,
    private val hookEngine: LarpHookEngine
) : ViewModel() {

    private val _state = MutableStateFlow(DevKitUiState())
    val state: StateFlow<DevKitUiState> = _state.asStateFlow()

    init {
        refresh()
        viewModelScope.launch {
            OverlayPermissionHolder.state.collect {
                _state.update {
                    it.copy(canDrawOverlays = OverlayPermissionHolder.isGranted(context))
                }
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            val variables = devKitStore.loadVariables()
            val snapshot = devKitStore.loadSnapshot()
            hookEngine.ensureInitialized()
            _state.update {
                it.copy(
                    variables = variables,
                    snapshot = snapshot,
                    devKitEnabled = DevKitOverlayService.isDevKitEnabled(context),
                    canDrawOverlays = OverlayPermissionHolder.isGranted(context),
                    shadowHookReady = hookEngine.shadowReady(),
                    byteHookReady = hookEngine.byteReady(),
                    isLoading = false
                )
            }
        }
    }

    fun initHooks() {
        viewModelScope.launch {
            hookEngine.ensureInitialized()
            _state.update {
                it.copy(
                    shadowHookReady = hookEngine.shadowReady(),
                    byteHookReady = hookEngine.byteReady()
                )
            }
        }
    }

    fun setDevKitEnabled(enabled: Boolean) {
        viewModelScope.launch {
            DevKitOverlayService.setEnabled(context, enabled)
            _state.update {
                it.copy(
                    devKitEnabled = enabled,
                    canDrawOverlays = OverlayPermissionHolder.isGranted(context)
                )
            }
        }
    }

    fun addVariable(name: String, address: String, module: String, size: Int, format: String) {
        if (name.isBlank() || address.isBlank()) {
            _state.update { it.copy(error = "Nome e endereço são obrigatórios") }
            return
        }
        viewModelScope.launch {
            val variable = DevKitVariable(
                id = devKitStore.newId(),
                name = name.trim(),
                address = address.trim(),
                module = module.trim().ifBlank { "libil2cpp.so" },
                size = if (size in 1..64) size else 8,
                format = format,
                enabled = true
            )
            val updated = devKitStore.addVariable(variable)
            _state.update { it.copy(variables = updated, error = null) }
        }
    }

    fun updateVariable(variable: DevKitVariable) {
        viewModelScope.launch {
            val updated = devKitStore.updateVariable(variable)
            _state.update { it.copy(variables = updated) }
        }
    }

    fun toggleEnabled(variable: DevKitVariable, enabled: Boolean) {
        viewModelScope.launch {
            val updated = devKitStore.updateVariable(variable.copy(enabled = enabled))
            _state.update { it.copy(variables = updated) }
        }
    }

    fun deleteVariable(id: String) {
        viewModelScope.launch {
            val updated = devKitStore.deleteVariable(id)
            _state.update { it.copy(variables = updated) }
        }
    }

    fun takeSnapshotNow() {
        viewModelScope.launch {
            val variables = devKitStore.loadVariables()
            val snapshot = devKitBridge.snapshot(variables, devKitStore.newId())
            devKitStore.saveSnapshot(snapshot)
            _state.update { it.copy(snapshot = snapshot) }
        }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }
}

