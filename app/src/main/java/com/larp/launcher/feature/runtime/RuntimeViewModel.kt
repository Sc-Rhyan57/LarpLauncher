package com.larp.launcher.feature.runtime

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.larp.launcher.core.LarpEnvProvider
import com.larp.launcher.patcher.RuntimeDownloader
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RuntimeUiState(
    val dotnetUrl: String = "",
    val bepinexUrl: String = "",
    val hasDotnet: Boolean = false,
    val hasBepinex: Boolean = false,
    val dotnetSize: Long = 0L,
    val bepinexSize: Long = 0L,
    val dotnetBundled: Boolean = false,
    val bepinexBundled: Boolean = false,
    val isDownloading: Boolean = false,
    val progress: Float = 0f,
    val statusMessage: String = "",
    val error: String? = null
)

@HiltViewModel
class RuntimeViewModel @Inject constructor(
    private val runtimeDownloader: RuntimeDownloader,
    private val envProvider: LarpEnvProvider
) : ViewModel() {

    private val _state = MutableStateFlow(RuntimeUiState())
    val state: StateFlow<RuntimeUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        val env = envProvider.current()
        _state.update {
            it.copy(
                dotnetUrl = env.runtimeDotnetUrl,
                bepinexUrl = env.runtimeBepinexUrl,
                hasDotnet = runtimeDownloader.hasDotnet(),
                hasBepinex = runtimeDownloader.hasBepinex(),
                dotnetBundled = runtimeDownloader.hasDotnetAsset(),
                bepinexBundled = runtimeDownloader.hasBepinexAsset(),
                dotnetSize = runtimeDownloader.dotnetZip().takeIf { f -> f.isFile }?.length() ?: 0L,
                bepinexSize = runtimeDownloader.bepinexZip().takeIf { f -> f.isFile }?.length() ?: 0L
            )
        }
    }

    fun updateDotnetUrl(url: String) {
        _state.update { it.copy(dotnetUrl = url) }
    }

    fun updateBepinexUrl(url: String) {
        _state.update { it.copy(bepinexUrl = url) }
    }

    fun saveUrls(onDone: () -> Unit) {
        viewModelScope.launch {
            val current = envProvider.current()
            val updated = current.copy(
                runtimeDotnetUrl = _state.value.dotnetUrl.trim(),
                runtimeBepinexUrl = _state.value.bepinexUrl.trim()
            )
            envProvider.update(updated)
            refresh()
            onDone()
        }
    }

    fun downloadDotnet() {
        if (_state.value.isDownloading) return
        viewModelScope.launch {
            _state.update { it.copy(isDownloading = true, progress = 0f, statusMessage = "Baixando runtime .NET…", error = null) }
            runCatching {
                runtimeDownloader.downloadDotnet { p ->
                    _state.update { it.copy(progress = p) }
                }
            }.onSuccess {
                _state.update { it.copy(isDownloading = false, progress = 1f, statusMessage = "Runtime .NET baixado") }
                refresh()
            }.onFailure { t ->
                _state.update { it.copy(isDownloading = false, error = t.message ?: "Falha ao baixar .NET") }
            }
        }
    }

    fun downloadBepinex() {
        if (_state.value.isDownloading) return
        viewModelScope.launch {
            _state.update { it.copy(isDownloading = true, progress = 0f, statusMessage = "Baixando BepInEx…", error = null) }
            runCatching {
                runtimeDownloader.downloadBepinex { p ->
                    _state.update { it.copy(progress = p) }
                }
            }.onSuccess {
                _state.update { it.copy(isDownloading = false, progress = 1f, statusMessage = "BepInEx baixado") }
                refresh()
            }.onFailure { t ->
                _state.update { it.copy(isDownloading = false, error = t.message ?: "Falha ao baixar BepInEx") }
            }
        }
    }

    fun downloadAll() {
        if (_state.value.isDownloading) return
        viewModelScope.launch {
            _state.update { it.copy(isDownloading = true, progress = 0f, statusMessage = "Baixando runtime de mods…", error = null) }
            runCatching {
                runtimeDownloader.ensureRuntime { msg, p ->
                    _state.update { it.copy(statusMessage = msg, progress = p) }
                }
            }.onSuccess {
                _state.update { it.copy(isDownloading = false, progress = 1f, statusMessage = "Runtime completo") }
                refresh()
            }.onFailure { t ->
                _state.update { it.copy(isDownloading = false, error = t.message ?: "Falha ao baixar runtime") }
            }
        }
    }

    fun deleteDownloaded() {
        runtimeDownloader.deleteDownloaded()
        refresh()
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }
}
