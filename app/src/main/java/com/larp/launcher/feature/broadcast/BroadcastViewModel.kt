package com.larp.launcher.feature.broadcast

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.larp.launcher.data.repository.AuthRepository
import com.larp.launcher.data.repository.BroadcastRepository
import com.larp.launcher.domain.model.BroadcastMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class BroadcastUiState(
    val loading: Boolean = false,
    val history: List<BroadcastMessage> = emptyList(),
    val isAdmin: Boolean = false,
    val sending: Boolean = false,
    val lastError: String? = null
)

@HiltViewModel
class BroadcastViewModel @Inject constructor(
    private val broadcastRepository: BroadcastRepository,
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _state = MutableStateFlow(BroadcastUiState(loading = true))
    val state: StateFlow<BroadcastUiState> = _state.asStateFlow()

    init {
        _state.update { it.copy(isAdmin = authRepository.isAdmin()) }
        refresh()
    }

    fun refresh() {
        _state.update { it.copy(loading = true, lastError = null) }
        viewModelScope.launch {
            try {
                val history = broadcastRepository.recent(limit = 50)
                _state.update { it.copy(loading = false, history = history) }
            } catch (t: Throwable) {
                _state.update {
                    it.copy(
                        loading = false,
                        lastError = t.message ?: "Falha ao carregar broadcasts"
                    )
                }
            }
        }
    }

    fun send(message: String, severity: BroadcastMessage.Severity) {
        if (!authRepository.isAdmin()) return
        if (message.isBlank()) return
        _state.update { it.copy(sending = true, lastError = null) }
        viewModelScope.launch {
            try {
                broadcastRepository.send(message, severity)
                _state.update { it.copy(sending = false) }
                refresh()
            } catch (t: Throwable) {
                _state.update {
                    it.copy(
                        sending = false,
                        lastError = t.message ?: "Falha ao enviar broadcast"
                    )
                }
            }
        }
    }
}
