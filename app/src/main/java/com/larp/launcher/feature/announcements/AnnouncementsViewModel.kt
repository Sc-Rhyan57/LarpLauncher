package com.larp.launcher.feature.announcements

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.larp.launcher.core.console.Console
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
    private val authRepository: AuthRepository,
    private val console: Console
) : ViewModel() {

    private val _state = MutableStateFlow(AnnouncementsUiState(loading = true))
    val state: StateFlow<AnnouncementsUiState> = _state.asStateFlow()

    init {
        _state.update { it.copy(isAdmin = authRepository.isAdmin()) }
        refresh()
    }

    fun refresh() {
        _state.update { it.copy(loading = true, error = null) }
        console.i("Mural", "Carregando avisos…")
        viewModelScope.launch {
            try {
                val items = announcementsRepository.list()
                console.i("Mural", "${items.size} avisos carregados")
                _state.update { it.copy(loading = false, items = items) }
            } catch (t: Throwable) {
                console.e("Mural", "Falha ao carregar avisos", t)
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
        if (title.isBlank() || body.isBlank()) {
            console.w("Mural", "publish recusado: título ou corpo vazio")
            return
        }
        console.i("Mural", "Publicando aviso: $title")
        viewModelScope.launch {
            try {
                announcementsRepository.publish(
                    title = title,
                    body = body,
                    pinned = pinned
                )
                console.i("Mural", "Aviso publicado")
                refresh()
            } catch (t: Throwable) {
                console.e("Mural", "Falha ao publicar aviso", t)
                _state.update { it.copy(error = t.message ?: "Falha ao publicar aviso") }
            }
        }
    }

    fun delete(id: String) {
        if (!authRepository.isAdmin()) {
            console.w("Mural", "delete recusado: usuário não é admin")
            return
        }
        console.i("Mural", "Excluindo aviso: $id")
        viewModelScope.launch {
            try {
                announcementsRepository.delete(id)
                console.i("Mural", "Aviso excluído")
                refresh()
            } catch (t: Throwable) {
                console.e("Mural", "Falha ao excluir aviso", t)
                _state.update { it.copy(error = t.message ?: "Falha ao excluir aviso") }
            }
        }
    }
}

