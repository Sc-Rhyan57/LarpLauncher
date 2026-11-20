package com.larp.launcher.feature.devkit

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.larp.launcher.domain.model.DevKitSnapshot
import com.larp.launcher.domain.model.DevKitVariable
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
    val isLoading: Boolean = true,
    val error: String? = null
)

@HiltViewModel
class DevKitViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val devKitStore: DevKitStore,
    private val devKitBridge: DevKitBridge
) : ViewModel() {

    private val _state = MutableStateFlow(DevKitUiState())
    val state: StateFlow<DevKitUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val variables = devKitStore.loadVariables()
            val snapshot = devKitStore.loadSnapshot()
            _state.update {
                it.copy(
                    variables = variables,
                    snapshot = snapshot,
                    devKitEnabled = DevKitOverlayService.isDevKitEnabled(context),
                    canDrawOverlays = android.provider.Settings.canDrawOverlays(context),
                    isLoading = false
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
                    canDrawOverlays = android.provider.Settings.canDrawOverlays(context)
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
