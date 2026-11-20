package com.larp.launcher.feature.console

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.larp.launcher.core.console.Console
import com.larp.launcher.core.console.ConsoleEntry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ConsoleViewModel @Inject constructor(
    private val console: Console
) : ViewModel() {

    val entries: StateFlow<List<ConsoleEntry>> = console.entries

    init {
        viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                console.reload()
                delay(1000)
            }
        }
    }

    fun clear() {
        console.clear()
    }
}
