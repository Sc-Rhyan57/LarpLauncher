package com.larp.launcher.feature.console

import androidx.lifecycle.ViewModel
import com.larp.launcher.core.console.Console
import com.larp.launcher.core.console.ConsoleEntry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class ConsoleViewModel @Inject constructor(
    private val console: Console
) : ViewModel() {

    val entries: StateFlow<List<ConsoleEntry>> = console.entries

    fun clear() {
        console.clear()
    }
}
