package com.larp.launcher.feature.launcher

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.larp.launcher.data.repository.AuthRepository
import com.larp.launcher.data.repository.GameEnvRepository
import com.larp.launcher.domain.model.BroadcastMessage
import com.larp.launcher.domain.model.GameEnv
import com.larp.launcher.domain.model.Room
import com.larp.launcher.domain.model.User
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class LarpDestination {
    Mods, MyMods, Rooms, Mural, Broadcast, Voice, Malum, Frida, Settings
}

data class LauncherUiState(
    val currentUser: User? = null,
    val gameEnv: GameEnv? = null,
    val activeRoom: Room? = null,
    val recentBroadcast: BroadcastMessage? = null,
    val isLoading: Boolean = true,
    val error: String? = null
)

@HiltViewModel
class LauncherViewModel @Inject constructor(
    private val authRepo: AuthRepository,
    private val gameEnvRepo: GameEnvRepository
) : ViewModel() {

    private val _state = MutableStateFlow(LauncherUiState())
    val state: StateFlow<LauncherUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _state.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            try {
                val user = authRepo.currentUser()
                val env = runCatching { gameEnvRepo.detect() }.getOrNull()
                _state.update {
                    it.copy(
                        currentUser = user,
                        gameEnv = env,
                        isLoading = false
                    )
                }
            } catch (t: Throwable) {
                _state.update {
                    it.copy(
                        isLoading = false,
                        error = t.message ?: "Falha ao carregar"
                    )
                }
            }
        }
    }

    fun logout() {
        authRepo.logout()
        _state.update { it.copy(currentUser = null) }
    }
}
