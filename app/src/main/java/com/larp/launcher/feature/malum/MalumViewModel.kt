package com.larp.launcher.feature.malum

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

data class MalumStep(
    val title: String,
    val body: String,
    val iconTag: String
)

data class MalumUiState(
    val steps: List<MalumStep> = emptyList(),
    val currentStep: Int = 0
)

@HiltViewModel
class MalumViewModel @Inject constructor() : ViewModel() {

    private val _state = MutableStateFlow(
        MalumUiState(steps = defaultSteps(), currentStep = 0)
    )
    val state: StateFlow<MalumUiState> = _state.asStateFlow()

    fun setCurrentStep(index: Int) {
        _state.value = _state.value.copy(currentStep = index.coerceIn(0, _state.value.steps.lastIndex))
    }

    private fun defaultSteps(): List<MalumStep> = listOf(
        MalumStep(
            title = "1. Instale o jogo base",
            body = "Instale o jogo suportado a partir da loja oficial do fabricante.",
            iconTag = "install"
        ),
        MalumStep(
            title = "2. Conceda permissão de armazenamento",
            body = "O Larp precisa de acesso ao armazenamento para selecionar DLLs de mods locais.",
            iconTag = "storage"
        ),
        MalumStep(
            title = "3. Selecione os mods em \"Mods\"",
            body = "Vá na aba Mods, escolha os mods do servidor e toque em Instalar.",
            iconTag = "mods"
        ),
        MalumStep(
            title = "4. Toque em \"Iniciar jogo\"",
            body = "Volte para a tela inicial e toque no botão Iniciar jogo.",
            iconTag = "play"
        ),
        MalumStep(
            title = "5. Aguarde o BepInEx inicializar",
            body = "A primeira inicialização pode demorar enquanto o runtime .NET é preparado.",
            iconTag = "wait"
        ),
        MalumStep(
            title = "6. Abra o menu in-game (geralmente LShift+F1)",
            body = "Use o atalho dentro do jogo para abrir o menu de mods. Alguns mods usam atalhos diferentes.",
            iconTag = "menu"
        )
    )
}
