package com.larp.launcher.boot

import android.util.Log
import com.regis.boot.Boot as Native

object Boot {

    private const val TAG = "LarpBoot"

    val stage: Int get() = Native.stage
    val message: String get() = Native.message
    val lines: List<String> get() = Native.lines()
    @Volatile var failure: Throwable? = null
        private set

    // Ordem importa: libcoreclr depende (via DT_NEEDED) das libSystem.*, libcrypto, libssl, etc.
    // Pré-carregar tudo evita problemas de resolução de símbolos no dlopen do libfusion.so.
    private val PRELOAD_ORDER = listOf(
        "crypto",
        "ssl",
        "dobby",
        "System.Native",
        "System.Globalization.Native",
        "System.IO.Compression.Native",
        "System.Security.Cryptography.Native.OpenSsl",
        "clrjit",
        "coreclr"
    )

    @JvmStatic
    fun load(name: String) {
        failure = null
        Native.clear()
        Native.status(1, "Loading $name")
        try {
            if (name == "fusion") {
                preloadRuntimeLibraries()
                System.loadLibrary("fusion")
            } else {
                System.loadLibrary(name)
            }
            if (Native.stage < 1) Native.status(1, "Loaded $name")
            Log.i(TAG, "Loaded $name (stage=${Native.stage})")
        } catch (t: Throwable) {
            failure = t
            Native.status(-1, "Failed $name: ${t.message}")
            Log.e(TAG, "Failed to load $name", t)
        }
    }

    private fun preloadRuntimeLibraries() {
        val failures = mutableListOf<String>()
        for (libBase in PRELOAD_ORDER) {
            val result = runCatching { System.loadLibrary(libBase) }
            if (result.isSuccess) {
                Log.i(TAG, "preload: lib$libBase.so OK")
            } else {
                val msg = result.exceptionOrNull()?.message ?: "?"
                Log.w(TAG, "preload: lib$libBase.so falhou: $msg")
                failures += "lib$libBase.so ($msg)"
            }
        }
        if (failures.isNotEmpty()) {
            // Não abortamos — algumas libs podem não estar no APK dependendo do build.
            // O libfusion.so vai decidir se consegue seguir sem elas.
            Native.status(0, "Preload parcial: ${failures.size}/${PRELOAD_ORDER.size} libs falharam")
            Log.w(TAG, "preload parcial: $failures")
        }
    }
}
