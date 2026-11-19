package com.larp.launcher.boot

import android.util.Log

object Boot {

    private const val TAG = "LarpBoot"
    private const val LIB_CORECLR = "coreclr"
    private const val LIB_FUSION = "fusion"

    @JvmStatic
    var stage: Int = 0
        private set

    @JvmStatic
    var message: String = ""
        private set

    @JvmStatic
    fun load(name: String) {
        message = "Loading $name"
        stage = 1
        runCatching {
            when (name) {
                "coreclr" -> System.loadLibrary(LIB_CORECLR)
                "fusion" -> {
                    System.loadLibrary(LIB_CORECLR)
                    System.loadLibrary(LIB_FUSION)
                }
                else -> System.loadLibrary(name)
            }
        }.onSuccess {
            stage = 2
            message = "Loaded $name"
            Log.i(TAG, "Loaded $name")
        }.onFailure {
            stage = -1
            message = "Failed $name: ${it.message}"
            Log.e(TAG, "Failed to load $name", it)
        }
    }
}
