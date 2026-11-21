package com.larp.launcher.ui

import android.util.Log

object LarpNative {

    @Volatile private var loaded = false

    @Synchronized
    fun load(): Boolean {
        if (loaded) return true
        return try {
            System.loadLibrary("larpui")
            nativeInit()
            loaded = true
            true
        } catch (t: Throwable) {
            Log.w("LarpUi", "larpui indisponivel: ${t.message}")
            false
        }
    }

    @JvmStatic external fun nativeInit()

    @JvmStatic external fun nativeEmit(layer: String, node: String, event: String, value: String)

    @JvmStatic fun onSend(json: String) = LarpOverlay.send(json)

    @JvmStatic fun onDraw(layer: String, node: String, data: IntArray, texts: String) =
        LarpOverlay.draw(layer, node, data, texts)
}
