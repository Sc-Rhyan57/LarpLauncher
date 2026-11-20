package com.larp.launcher.feature.devkit

object DevKitNative {

    const val NOT_LOADED = -100

    private val loaded: Boolean = runCatching { System.loadLibrary("larpdevkit") }.isSuccess

    fun dump(path: String, force: Boolean): Int =
        if (loaded) nativeDump(path, force) else NOT_LOADED

    @JvmStatic
    private external fun nativeDump(path: String, force: Boolean): Int
}
