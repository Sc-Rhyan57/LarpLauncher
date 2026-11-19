package com.larp.launcher.feature.rooms

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.larp.launcher.data.repository.AuthRepository
import com.larp.launcher.data.repository.RoomsRepository
import com.larp.launcher.domain.model.Room
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RoomsUiState(
    val loading: Boolean = false,
    val rooms: List<Room> = emptyList(),
    val error: String? = null,
    val currentRoomId: String? = null,
    val isAdmin: Boolean = false
)

@HiltViewModel
class RoomsViewModel @Inject constructor(
    private val roomsRepository: RoomsRepository,
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _state = MutableStateFlow(RoomsUiState(loading = true))
    val state: StateFlow<RoomsUiState> = _state.asStateFlow()

    init {
        _state.update { it.copy(isAdmin = authRepository.isAdmin()) }
        refresh()
    }

    fun refresh() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val rooms = roomsRepository.listRooms()
                _state.update { it.copy(loading = false, rooms = rooms) }
            } catch (t: Throwable) {
                _state.update {
                    it.copy(
                        loading = false,
                        error = t.message ?: "Falha ao carregar salas"
                    )
                }
            }
        }
    }

    fun createRoom(name: String, game: String, maxPlayers: Int, voiceEnabled: Boolean) {
        if (name.isBlank() || game.isBlank()) return
        viewModelScope.launch {
            try {
                roomsRepository.createRoom(
                    name = name,
                    game = game,
                    mods = emptyList(),
                    maxPlayers = maxPlayers,
                    locked = false,
                    voiceEnabled = voiceEnabled
                )
                refresh()
            } catch (t: Throwable) {
                _state.update { it.copy(error = t.message ?: "Falha ao criar sala") }
            }
        }
    }

    fun joinRoom(id: String) {
        viewModelScope.launch {
            try {
                roomsRepository.joinRoom(id)
                _state.update { it.copy(currentRoomId = id) }
                refresh()
            } catch (t: Throwable) {
                _state.update { it.copy(error = t.message ?: "Falha ao entrar na sala") }
            }
        }
    }

    fun leaveCurrentRoom() {
        val current = _state.value.currentRoomId ?: return
        viewModelScope.launch {
            try {
                roomsRepository.leaveRoom(current)
                _state.update { it.copy(currentRoomId = null) }
                refresh()
            } catch (t: Throwable) {
                _state.update { it.copy(error = t.message ?: "Falha ao sair da sala") }
            }
        }
    }

    fun deleteRoom(id: String) {
        if (!authRepository.isAdmin()) return
        viewModelScope.launch {
            try {
                roomsRepository.deleteRoom(id)
                if (_state.value.currentRoomId == id) {
                    _state.update { it.copy(currentRoomId = null) }
                }
                refresh()
            } catch (t: Throwable) {
                _state.update { it.copy(error = t.message ?: "Falha ao excluir sala") }
            }
        }
    }
}
