package com.larp.launcher.feature.mods.mine

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.larp.launcher.data.repository.ModsRepository
import com.larp.launcher.domain.model.LocalMod
import com.larp.launcher.patcher.GameLauncher
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import com.larp.launcher.domain.model.PatchProgress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

data class MyModsUiState(
    val mods: List<LocalMod> = emptyList(),
    val isLoading: Boolean = true
)

data class MyModEditResult(
    val dllUri: Uri?,
    val coverUri: Uri?,
    val name: String,
    val description: String,
    val author: String,
    val version: String
)

@HiltViewModel
class MyModsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val modsRepository: ModsRepository,
    private val gameLauncher: GameLauncher
) : ViewModel() {

    val state: StateFlow<MyModsUiState> = modsRepository.observeLocalMods()
        .map { MyModsUiState(mods = it, isLoading = false) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = MyModsUiState(isLoading = true)
        )

    fun addMod(
        dllUri: Uri,
        coverUri: Uri?,
        name: String,
        description: String,
        author: String,
        version: String
    ) {
        viewModelScope.launch {
            try {
                val dllBytes = readBytes(dllUri) ?: return@launch
                val coverBytes = coverUri?.let { readBytes(it) }
                val id = UUID.randomUUID().toString()
                val now = System.currentTimeMillis()
                val mod = LocalMod(
                    id = id,
                    name = name.ifBlank { "Mod sem nome" },
                    description = description,
                    author = author,
                    version = version.ifBlank { "1.0.0" },
                    dllPath = "",
                    coverPath = null,
                    createdAt = now,
                    updatedAt = now,
                    fileSize = dllBytes.size.toLong(),
                    checksum = "",
                    enabled = true
                )
                modsRepository.addLocalMod(mod, dllBytes, coverBytes)
            } catch (_: Throwable) {
            }
        }
    }

    fun updateMod(mod: LocalMod, coverUri: Uri? = null) {
        viewModelScope.launch {
            val coverBytes = coverUri?.let { readBytes(it) }
            runCatching { modsRepository.updateLocalMod(mod, coverBytes) }
        }
    }

    fun deleteMod(id: String) {
        viewModelScope.launch {
            runCatching { modsRepository.deleteLocalMod(id) }
        }
    }

    fun toggleEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch {
            runCatching { modsRepository.toggleLocalMod(id, enabled) }
        }
    }

    private val _launch = MutableStateFlow<PatchProgress?>(null)
    val launch: StateFlow<PatchProgress?> = _launch.asStateFlow()

    private var launchJob: kotlinx.coroutines.Job? = null

    fun dismissLaunch() {
        launchJob?.cancel()
        launchJob = null
        _launch.value = null
    }

    fun launchWithLocalMods(onStarted: () -> Unit = {}) {
        val current = _launch.value
        if (current != null && current.stage != PatchProgress.Stage.Failed) return
        launchJob = viewModelScope.launch {
            val enabled = state.value.mods.filter { it.enabled && it.dllPath.isNotBlank() }
            if (enabled.isEmpty()) {
                val msg = "Nenhum mod ativado. Ative pelo menos um mod para iniciar com mods."
                _launch.value = PatchProgress(PatchProgress.Stage.Failed, msg, 0f, IllegalStateException(msg))
                return@launch
            }
            val paths = enabled.map { it.dllPath }
            gameLauncher.launchLocalMods(paths).collect { progress ->
                if (progress.stage == PatchProgress.Stage.Ready) {
                    // jogo foi aberto: o diálogo não pode ficar para trás (travava "carregando" para sempre)
                    _launch.value = null
                    onStarted()
                } else {
                    _launch.value = progress
                }
            }
            // fluxo terminou sem Ready/Failed (ex.: cancelado): nunca deixar diálogo preso
            if (_launch.value?.stage != PatchProgress.Stage.Failed) _launch.value = null
        }
    }

    private fun readBytes(uri: Uri): ByteArray? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
    }.getOrNull()
}
