package com.larp.launcher.feature.voice

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.larp.launcher.data.repository.AuthRepository
import com.larp.launcher.domain.model.RoomParticipant
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class VoiceUiState(
    val connected: Boolean = false,
    val connecting: Boolean = false,
    val roomId: String? = null,
    val participants: List<RoomParticipant> = emptyList(),
    val muted: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class VoiceViewModel @Inject constructor(
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _state = MutableStateFlow(VoiceUiState())
    val state: StateFlow<VoiceUiState> = _state.asStateFlow()

    fun connect(roomId: String) {
        if (_state.value.connecting || _state.value.connected) return
        _state.update {
            it.copy(
                connecting = true,
                roomId = roomId,
                error = null,
                muted = false
            )
        }
        viewModelScope.launch {
            try {
                delay(800)
                val me = authRepository.currentUser()
                val participants = buildList {
                    add(
                        RoomParticipant(
                            userId = me?.id ?: "me",
                            name = me?.name ?: "Você",
                            avatar = me?.avatar,
                            muted = false,
                            speaking = false,
                            isAdmin = me?.isAdmin == true
                        )
                    )
                    add(
                        RoomParticipant(
                            userId = "demo-1",
                            name = "Participante Demo",
                            avatar = null,
                            muted = false,
                            speaking = false
                        )
                    )
                }
                _state.update {
                    it.copy(
                        connecting = false,
                        connected = true,
                        participants = participants
                    )
                }
            } catch (t: Throwable) {
                _state.update {
                    it.copy(
                        connecting = false,
                        error = t.message ?: "Falha ao conectar"
                    )
                }
            }
        }
    }

    fun disconnect() {
        _state.update {
            it.copy(
                connected = false,
                connecting = false,
                roomId = null,
                participants = emptyList(),
                muted = false,
                error = null
            )
        }
    }

    fun toggleMute() {
        _state.update {
            val newMuted = !it.muted
            it.copy(
                muted = newMuted,
                participants = it.participants.map { p ->
                    if (p.userId == (authRepository.currentUser()?.id ?: "me")) {
                        p.copy(muted = newMuted)
                    } else {
                        p
                    }
                }
            )
        }
    }
}
