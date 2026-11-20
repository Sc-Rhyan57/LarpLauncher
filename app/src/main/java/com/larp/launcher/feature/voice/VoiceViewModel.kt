package com.larp.launcher.feature.voice

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.larp.launcher.OverlayPermissionHolder
import com.larp.launcher.core.console.Console
import com.larp.launcher.data.repository.AuthRepository
import com.larp.launcher.domain.model.RoomParticipant
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
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
    val canDrawOverlays: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class VoiceViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val authRepository: AuthRepository,
    private val console: Console
) : ViewModel() {

    private val _state = MutableStateFlow(VoiceUiState())
    val state: StateFlow<VoiceUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            OverlayPermissionHolder.state.collect {
                _state.update { it.copy(canDrawOverlays = OverlayPermissionHolder.isGranted(context)) }
            }
        }
    }

    fun connect(roomId: String) {
        if (_state.value.connecting || _state.value.connected) return
        console.i("Voice", "Conectando na sala: $roomId")
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
                setConnectedPref(connected = true)
                FloatingVoiceBubbleService.show(context)
                console.i("Voice", "Conectado; bolinha flutuante iniciada")
            } catch (t: Throwable) {
                console.e("Voice", "Falha ao conectar", t)
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
        console.i("Voice", "Desconectando e fechando bolinha")
        FloatingVoiceBubbleService.hide(context)
        setConnectedPref(connected = false)
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
        val newMuted = !_state.value.muted
        console.d("Voice", "Toggle mute -> $newMuted")
        FloatingVoiceBubbleService.toggleMute(context)
        _state.update {
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

    private fun setConnectedPref(connected: Boolean) {
        context.getSharedPreferences("larp_voice", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("connected", connected)
            .apply()
    }

    override fun onCleared() {
        if (_state.value.connected) {
            FloatingVoiceBubbleService.hide(context)
        }
        super.onCleared()
    }
}


