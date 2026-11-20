package com.larp.launcher.hook

import android.content.Context
import com.bytedance.android.bytehook.ByteHook
import com.bytedance.shadowhook.ShadowHook
import com.larp.launcher.core.console.Console
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LarpHookEngine @Inject constructor(
    private val context: Context,
    private val console: Console
) {

    @Volatile private var shadowReady = false
    @Volatile private var byteReady = false

    fun ensureInitialized() {
        if (!shadowReady) {
            runCatching {
                ShadowHook.init(
                    ShadowHook.ConfigBuilder()
                        .setMode(ShadowHook.Mode.UNIQUE)
                        .build()
                )
                shadowReady = true
                console.i("LarpHook", "ShadowHook inicializado (inline hook)")
            }.onFailure { t -> console.e("LarpHook", "Falha ShadowHook init", t) }
        }
        if (!byteReady) {
            runCatching {
                ByteHook.init()
                byteReady = true
                console.i("LarpHook", "ByteHook inicializado (PLT hook)")
            }.onFailure { t -> console.e("LarpHook", "Falha ByteHook init", t) }
        }
    }

    fun shadowReady(): Boolean = shadowReady
    fun byteReady(): Boolean = byteReady

    companion object {
        private const val TAG = "LarpHook"

        init {
            runCatching {
                System.loadLibrary("shadowhook")
            }
            runCatching {
                System.loadLibrary("bytehook")
            }
        }
    }
}
