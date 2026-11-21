package com.larp.launcher.boot

import android.util.Log
import java.lang.reflect.InvocationTargetException
import com.regis.boot.Boot as Native

object Boot {

    private const val TAG = "LarpBoot"
    private const val GAME_BOOT = "com.larp.launcher.boot.GameBoot"

    val stage: Int get() = Native.stage
    val message: String get() = Native.message
    val lines: List<String> get() = Native.lines()
    @Volatile var failure: Throwable? = null
        private set

    @JvmStatic
    fun load(classLoader: ClassLoader): Boolean {
        failure = null
        Native.clear()
        Native.status(1, "Loading fusion")
        return try {
            val type = Class.forName(GAME_BOOT, true, classLoader)
            val report = type.getMethod("run").invoke(null) as? String ?: ""
            val entries = report.lineSequence().filter { it.isNotBlank() }.toList()
            entries.forEach { line ->
                Log.i(TAG, line)
                Native.note(line)
            }
            val fusionError = entries.firstOrNull { it.startsWith("fusion:") }
            if (fusionError != null) {
                fail(IllegalStateException(fusionError))
                false
            } else {
                Log.i(TAG, "fusion carregado (stage=${Native.stage})")
                true
            }
        } catch (t: Throwable) {
            fail(if (t is InvocationTargetException) t.targetException ?: t else t)
            false
        }
    }

    private fun fail(t: Throwable) {
        failure = t
        Native.status(-1, "Failed: ${t.message}")
        Log.e(TAG, "Failed to load fusion", t)
    }
}
