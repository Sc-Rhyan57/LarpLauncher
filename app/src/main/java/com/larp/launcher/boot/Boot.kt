package com.larp.launcher.boot

import com.larp.launcher.patcher.GameEnv
import com.regis.boot.Boot as Local

object Boot {

    private const val CLASS_NAME = "com.regis.boot.Boot"

    private fun remote(): Class<*>? =
        runCatching { GameEnv.get()?.classLoader?.loadClass(CLASS_NAME) }.getOrNull()

    val stage: Int
        get() = runCatching { remote()?.getField("stage")?.getInt(null) }.getOrNull() ?: Local.stage

    val message: String
        get() = runCatching { remote()?.getField("message")?.get(null) as? String }.getOrNull() ?: Local.message

    @Suppress("UNCHECKED_CAST")
    val lines: List<String>
        get() = runCatching { remote()?.getMethod("lines")?.invoke(null) as? List<String> }.getOrNull() ?: Local.lines()
}
