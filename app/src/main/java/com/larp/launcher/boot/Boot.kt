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

    @JvmStatic
    fun load(name: String) {
        failure = null
        Native.clear()
        Native.status(1, "Loading $name")
        try {
            if (name == "fusion") {
                runCatching { System.loadLibrary("coreclr") }
                System.loadLibrary("fusion")
            } else {
                System.loadLibrary(name)
            }
            if (Native.stage < 1) Native.status(1, "Loaded $name")
            Log.i(TAG, "Loaded $name")
        } catch (t: Throwable) {
            failure = t
            Native.status(-1, "Failed $name: ${t.message}")
            Log.e(TAG, "Failed to load $name", t)
        }
    }
}
