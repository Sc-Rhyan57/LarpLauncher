package com.larp.launcher

import android.content.Context
import android.provider.Settings

object OverlayPermissionHolder {
    private val _state = kotlinx.coroutines.flow.MutableStateFlow(0L)
    val state = _state
    fun bump() {
        _state.value = _state.value + 1
    }
    fun isGranted(context: Context): Boolean = Settings.canDrawOverlays(context)
}
