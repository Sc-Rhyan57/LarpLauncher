package com.larp.launcher.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.larp.launcher.data.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface LoginStatus {
    data object Idle : LoginStatus
    data object Loading : LoginStatus
    data object Success : LoginStatus
    data class Error(val message: String) : LoginStatus
}

data class LoginUiState(
    val status: LoginStatus = LoginStatus.Idle,
    val pendingAuthUrl: String? = null
)

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val authRepo: AuthRepository
) : ViewModel() {

    private val _state = MutableStateFlow(LoginUiState())
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    val pendingAuthUrl: StateFlow<String?> = _state
        .map { it.pendingAuthUrl }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )

    fun loginWithDiscord() {
        if (_state.value.status is LoginStatus.Loading) return
        _state.update { it.copy(status = LoginStatus.Loading) }
        viewModelScope.launch {
            try {
                val challenge = authRepo.beginLogin()
                val dto = authRepo.discordLoginUrl(challenge)
                _state.update {
                    it.copy(
                        status = LoginStatus.Idle,
                        pendingAuthUrl = dto.url
                    )
                }
            } catch (t: Throwable) {
                _state.update {
                    it.copy(status = LoginStatus.Error(t.message ?: "Falha ao iniciar login"))
                }
            }
        }
    }

    fun consumePendingUrl() {
        _state.update { it.copy(pendingAuthUrl = null) }
    }

    fun clearError() {
        if (_state.value.status is LoginStatus.Error) {
            _state.update { it.copy(status = LoginStatus.Idle) }
        }
    }
}
