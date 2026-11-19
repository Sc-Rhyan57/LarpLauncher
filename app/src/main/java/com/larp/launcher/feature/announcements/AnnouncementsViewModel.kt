package com.larp.launcher.feature.announcements

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.larp.launcher.data.repository.AnnouncementsRepository
import com.larp.launcher.data.repository.AuthRepository
import com.larp.launcher.domain.model.Announcement
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AnnouncementsUiState(
    val loading: Boolean = false,
    val items: List<Announcement> = emptyList(),
    val error: String? = null,
    val isAdmin: Boolean = false
)

@HiltViewModel
class AnnouncementsViewModel @Inject constructor(
    private val announcementsRepository: AnnouncementsRepository,
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _state = MutableStateFlow(AnnouncementsUiState(loading = true))
    val state: StateFlow<AnnouncementsUiState> = _state.asStateFlow()

    init {
        _state.update { it.copy(isAdmin = authRepository.isAdmin()) }
        refresh()
    }

    fun refresh() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val items = announcementsRepository.list()
                _state.update { it.copy(loading = false, items = items) }
            } catch (t: Throwable) {
                _state.update {
                    it.copy(
                        loading = false,
                        error = t.message ?: "Falha ao carregar avisos"
                    )
                }
            }
        }
    }

    fun publish(title: String, body: String, pinned: Boolean) {
        if (title.isBlank() || body.isBlank()) return
        viewModelScope.launch {
            try {
                announcementsRepository.publish(
                    title = title,
                    body = body,
                    pinned = pinned
                )
                refresh()
            } catch (t: Throwable) {
                _state.update { it.copy(error = t.message ?: "Falha ao publicar aviso") }
            }
        }
    }

    fun delete(id: String) {
        if (!authRepository.isAdmin()) return
        viewModelScope.launch {
            try {
                announcementsRepository.delete(id)
                refresh()
            } catch (t: Throwable) {
                _state.update { it.copy(error = t.message ?: "Falha ao excluir aviso") }
            }
        }
    }
}
